package com.robbanhoglund.springbootanalyzer.analyzer.configuration;

import com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.ConfigurationPropertiesClass;
import com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.CustomPropertyDefinition;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.type.Type;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public class ConfigurationPropertiesClassAnalyzer {

    private static final Set<String> VALIDATION_ANNOTATIONS = Set.of(
            "NotBlank",
            "NotNull",
            "Min",
            "Max",
            "DecimalMin",
            "DecimalMax",
            "Positive",
            "PositiveOrZero",
            "DurationMin",
            "DurationMax"
    );

    private final JavaParser javaParser;
    private final PropertyNameNormalizer propertyNameNormalizer;

    public ConfigurationPropertiesClassAnalyzer(PropertyNameNormalizer propertyNameNormalizer) {
        this.propertyNameNormalizer = propertyNameNormalizer;
        this.javaParser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25)
                .setCharacterEncoding(StandardCharsets.UTF_8));
    }

    public List<ConfigurationPropertiesClass> analyze(Path repositoryRoot) {
        Path sourceRoot = repositoryRoot.resolve("src/main/java");
        if (Files.notExists(sourceRoot)) {
            return List.of();
        }

        List<ConfigurationPropertiesClass> classes = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted(Comparator.naturalOrder())
                    .forEach(path -> analyzeSourceFile(repositoryRoot, path, classes));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to scan @ConfigurationProperties classes under " + sourceRoot, exception);
        }
        return List.copyOf(classes);
    }

    private void analyzeSourceFile(Path repositoryRoot, Path sourceFile, List<ConfigurationPropertiesClass> classes) {
        try {
            var parseResult = javaParser.parse(sourceFile);
            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                return;
            }

            CompilationUnit compilationUnit = parseResult.getResult().orElseThrow();
            String packageName = compilationUnit.getPackageDeclaration()
                    .map(declaration -> declaration.getNameAsString())
                    .orElse("");
            Map<String, TypeDeclaration<?>> localTypes = indexLocalTypes(compilationUnit);

            for (TypeDeclaration<?> typeDeclaration : compilationUnit.findAll(TypeDeclaration.class)) {
                findConfigurationPropertiesClass(repositoryRoot, sourceFile, packageName, typeDeclaration, localTypes)
                        .ifPresent(classes::add);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read source file: " + sourceFile, exception);
        }
    }

    private Optional<ConfigurationPropertiesClass> findConfigurationPropertiesClass(
            Path repositoryRoot,
            Path sourceFile,
            String packageName,
            TypeDeclaration<?> typeDeclaration,
            Map<String, TypeDeclaration<?>> localTypes
    ) {
        String prefix = null;
        for (AnnotationExpr annotation : typeDeclaration.getAnnotations()) {
            if (!"ConfigurationProperties".equals(simpleName(annotation.getNameAsString()))) {
                continue;
            }
            prefix = extractPrefix(annotation).orElse("");
            break;
        }

        if (prefix == null) {
            return Optional.empty();
        }

        List<CustomPropertyDefinition> properties = collectProperties(typeDeclaration, "", localTypes, new LinkedHashSet<>());

        String qualifiedClassName = packageName.isBlank()
                ? typeDeclaration.getNameAsString()
                : packageName + "." + typeDeclaration.getNameAsString();

        return Optional.of(new ConfigurationPropertiesClass(
                prefix,
                qualifiedClassName,
                normalizePath(repositoryRoot, sourceFile),
                cleanJavadoc(typeDeclaration.getJavadocComment().map(comment -> comment.parse().toText()).orElse(null)),
                properties
        ));
    }

    private List<CustomPropertyDefinition> collectProperties(
            TypeDeclaration<?> typeDeclaration,
            String currentPrefix,
            Map<String, TypeDeclaration<?>> localTypes,
            Set<String> visitedTypes
    ) {
        List<CustomPropertyDefinition> properties = new ArrayList<>();

        if (!visitedTypes.add(typeDeclaration.getNameAsString())) {
            return List.of();
        }

        if (typeDeclaration instanceof RecordDeclaration recordDeclaration) {
            for (Parameter parameter : recordDeclaration.getParameters()) {
                String propertySegment = propertyNameNormalizer.toKebabCase(parameter.getNameAsString());
                String propertyName = joinPrefix(currentPrefix, propertySegment);
                TypeDeclaration<?> nestedType = resolveNestedType(parameter.getType(), localTypes);
                if (nestedType != null) {
                    properties.addAll(collectProperties(nestedType, propertyName, localTypes, new LinkedHashSet<>(visitedTypes)));
                } else {
                    properties.add(new CustomPropertyDefinition(
                            propertyName,
                            parameter.getNameAsString(),
                            parameter.getType().asString(),
                            validationAnnotations(parameter.getAnnotations()),
                            null
                    ));
                }
            }
            return List.copyOf(properties);
        }

        for (FieldDeclaration field : typeDeclaration.getFields()) {
            field.getVariables().forEach(variable -> {
                String propertySegment = propertyNameNormalizer.toKebabCase(variable.getNameAsString());
                String propertyName = joinPrefix(currentPrefix, propertySegment);
                TypeDeclaration<?> nestedType = resolveNestedType(variable.getType(), localTypes);
                if (nestedType != null) {
                    properties.addAll(collectProperties(nestedType, propertyName, localTypes, new LinkedHashSet<>(visitedTypes)));
                } else {
                    properties.add(new CustomPropertyDefinition(
                            propertyName,
                            variable.getNameAsString(),
                            variable.getType().asString(),
                            validationAnnotations(field.getAnnotations()),
                            cleanJavadoc(field.getJavadocComment().map(comment -> comment.parse().toText()).orElse(null))
                    ));
                }
            });
        }
        return List.copyOf(properties);
    }

    private Map<String, TypeDeclaration<?>> indexLocalTypes(CompilationUnit compilationUnit) {
        Map<String, TypeDeclaration<?>> types = new LinkedHashMap<>();
        for (TypeDeclaration<?> declaration : compilationUnit.findAll(TypeDeclaration.class)) {
            types.putIfAbsent(declaration.getNameAsString(), declaration);
        }
        return types;
    }

    private TypeDeclaration<?> resolveNestedType(Type type, Map<String, TypeDeclaration<?>> localTypes) {
        String simpleTypeName = type.asString()
                .replace("[]", "");
        int genericStart = simpleTypeName.indexOf('<');
        if (genericStart >= 0) {
            simpleTypeName = simpleTypeName.substring(0, genericStart);
        }
        int packageSeparator = simpleTypeName.lastIndexOf('.');
        if (packageSeparator >= 0) {
            simpleTypeName = simpleTypeName.substring(packageSeparator + 1);
        }
        return localTypes.get(simpleTypeName);
    }

    private String joinPrefix(String prefix, String propertySegment) {
        if (prefix == null || prefix.isBlank()) {
            return propertySegment;
        }
        return prefix + "." + propertySegment;
    }

    private List<String> validationAnnotations(NodeList<AnnotationExpr> annotations) {
        List<String> values = new ArrayList<>();
        for (AnnotationExpr annotation : annotations) {
            String name = simpleName(annotation.getNameAsString());
            if (VALIDATION_ANNOTATIONS.contains(name)) {
                values.add(name);
            }
        }
        return List.copyOf(values);
    }

    private Optional<String> extractPrefix(AnnotationExpr annotation) {
        if (annotation.isSingleMemberAnnotationExpr()) {
            return stringValue(annotation.asSingleMemberAnnotationExpr().getMemberValue());
        }
        if (annotation.isNormalAnnotationExpr()) {
            for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
                if ("prefix".equals(pair.getNameAsString()) || "value".equals(pair.getNameAsString())) {
                    return stringValue(pair.getValue());
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> stringValue(Expression expression) {
        if (expression.isStringLiteralExpr()) {
            return Optional.of(expression.asStringLiteralExpr().asString());
        }
        return Optional.empty();
    }

    private String cleanJavadoc(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.replace(System.lineSeparator(), " ").trim();
    }

    private String simpleName(String name) {
        int separatorIndex = name.lastIndexOf('.');
        return separatorIndex < 0 ? name : name.substring(separatorIndex + 1);
    }

    private String normalizePath(Path repositoryRoot, Path sourceFile) {
        return repositoryRoot.relativize(sourceFile).toString().replace('\\', '/');
    }
}
