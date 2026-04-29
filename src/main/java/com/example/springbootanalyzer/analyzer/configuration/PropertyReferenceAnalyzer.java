package com.example.springbootanalyzer.analyzer.configuration;

import com.example.springbootanalyzer.analyzer.model.configuration.PropertyReference;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public class PropertyReferenceAnalyzer {

    private static final Pattern VALUE_PATTERN = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}");
    private final JavaParser javaParser;
    private final PropertyNameNormalizer propertyNameNormalizer;

    public PropertyReferenceAnalyzer(PropertyNameNormalizer propertyNameNormalizer) {
        this.propertyNameNormalizer = propertyNameNormalizer;
        this.javaParser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25)
                .setCharacterEncoding(StandardCharsets.UTF_8));
    }

    public List<PropertyReference> analyze(Path repositoryRoot) {
        Path sourceRoot = repositoryRoot.resolve("src/main/java");
        if (Files.notExists(sourceRoot)) {
            return List.of();
        }

        List<PropertyReference> references = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted(Comparator.naturalOrder())
                    .forEach(path -> analyzeSourceFile(repositoryRoot, path, references));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to scan property references under " + sourceRoot, exception);
        }
        return List.copyOf(references);
    }

    private void analyzeSourceFile(Path repositoryRoot, Path sourceFile, List<PropertyReference> references) {
        try {
            var parseResult = javaParser.parse(sourceFile);
            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                return;
            }

            CompilationUnit compilationUnit = parseResult.getResult().orElseThrow();
            String packageName = compilationUnit.getPackageDeclaration()
                    .map(declaration -> declaration.getNameAsString())
                    .orElse("");

            for (TypeDeclaration<?> typeDeclaration : compilationUnit.findAll(TypeDeclaration.class)) {
                String className = packageName.isBlank()
                        ? typeDeclaration.getNameAsString()
                        : packageName + "." + typeDeclaration.getNameAsString();

                for (AnnotationExpr annotation : typeDeclaration.findAll(AnnotationExpr.class)) {
                    collectAnnotationReference(repositoryRoot, sourceFile, className, annotation).ifPresent(references::addAll);
                }

                for (MethodCallExpr methodCallExpr : typeDeclaration.findAll(MethodCallExpr.class)) {
                    collectMethodReference(repositoryRoot, sourceFile, className, methodCallExpr).ifPresent(references::add);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read source file: " + sourceFile, exception);
        }
    }

    private Optional<List<PropertyReference>> collectAnnotationReference(
            Path repositoryRoot,
            Path sourceFile,
            String className,
            AnnotationExpr annotation
    ) {
        String annotationName = simpleName(annotation.getNameAsString());
        if ("Value".equals(annotationName)) {
            String rawValue = annotation.isSingleMemberAnnotationExpr()
                    ? stringValue(annotation.asSingleMemberAnnotationExpr().getMemberValue()).orElse(null)
                    : null;
            if (rawValue == null) {
                return Optional.empty();
            }

            Matcher matcher = VALUE_PATTERN.matcher(rawValue);
            if (!matcher.find()) {
                return Optional.empty();
            }
            return Optional.of(List.of(new PropertyReference(
                    propertyNameNormalizer.normalize(matcher.group(1)),
                    "@Value",
                    normalizePath(repositoryRoot, sourceFile),
                    className,
                    matcher.group(2),
                    false,
                    null,
                    null
            )));
        }

        if ("Scheduled".equals(annotationName) && annotation.isNormalAnnotationExpr()) {
            return collectScheduledReferences(repositoryRoot, sourceFile, className, annotation);
        }

        if (!"ConditionalOnProperty".equals(annotationName) || !annotation.isNormalAnnotationExpr()) {
            return Optional.empty();
        }

        String prefix = "";
        List<String> names = new ArrayList<>();
        String havingValue = null;
        Boolean matchIfMissing = null;
        for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
            if ("prefix".equals(pair.getNameAsString())) {
                prefix = stringValue(pair.getValue()).orElse("");
            } else if ("name".equals(pair.getNameAsString()) || "value".equals(pair.getNameAsString())) {
                extractStringValues(pair.getValue()).forEach(names::add);
            } else if ("havingValue".equals(pair.getNameAsString())) {
                havingValue = stringValue(pair.getValue()).orElse(null);
            } else if ("matchIfMissing".equals(pair.getNameAsString()) && pair.getValue().isBooleanLiteralExpr()) {
                matchIfMissing = pair.getValue().asBooleanLiteralExpr().getValue();
            }
        }

        if (names.isEmpty()) {
            return Optional.empty();
        }

        List<PropertyReference> references = new ArrayList<>();
        for (String name : names) {
            String propertyName = prefix.isBlank() ? name : prefix + "." + name;
            references.add(new PropertyReference(
                    propertyNameNormalizer.normalize(propertyName),
                    "@ConditionalOnProperty",
                    normalizePath(repositoryRoot, sourceFile),
                    className,
                    null,
                    false,
                    havingValue,
                    matchIfMissing
            ));
        }
        return Optional.of(List.copyOf(references));
    }

    private Optional<PropertyReference> collectMethodReference(
            Path repositoryRoot,
            Path sourceFile,
            String className,
            MethodCallExpr methodCallExpr
    ) {
        String methodName = methodCallExpr.getNameAsString();
        if (!methodName.equals("getProperty")
                && !methodName.equals("getRequiredProperty")
                && !methodName.equals("containsProperty")) {
            return Optional.empty();
        }
        if (methodCallExpr.getArguments().isEmpty()) {
            return Optional.empty();
        }

        Optional<String> propertyName = stringValue(methodCallExpr.getArgument(0));
        if (propertyName.isEmpty()) {
            return Optional.empty();
        }

        String defaultValue = null;
        if ("getProperty".equals(methodName) && methodCallExpr.getArguments().size() > 1) {
            defaultValue = stringValue(methodCallExpr.getArgument(1)).orElse(null);
        }

        return Optional.of(new PropertyReference(
                propertyNameNormalizer.normalize(propertyName.get()),
                "Environment#" + methodName,
                normalizePath(repositoryRoot, sourceFile),
                className,
                defaultValue,
                methodName.equals("getRequiredProperty"),
                null,
                null
        ));
    }

    private Optional<List<PropertyReference>> collectScheduledReferences(
            Path repositoryRoot,
            Path sourceFile,
            String className,
            AnnotationExpr annotation
    ) {
        List<PropertyReference> references = new ArrayList<>();
        for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
            String pairName = pair.getNameAsString();
            if (!pairName.equals("fixedDelayString") && !pairName.equals("fixedRateString") && !pairName.equals("cron")) {
                continue;
            }
            Optional<String> rawValue = stringValue(pair.getValue());
            if (rawValue.isEmpty()) {
                continue;
            }
            Matcher matcher = VALUE_PATTERN.matcher(rawValue.get());
            if (!matcher.find()) {
                continue;
            }
            references.add(new PropertyReference(
                    propertyNameNormalizer.normalize(matcher.group(1)),
                    "@Scheduled",
                    normalizePath(repositoryRoot, sourceFile),
                    className,
                    matcher.group(2),
                    false,
                    pairName,
                    null
            ));
        }
        return references.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(references));
    }

    private List<String> extractStringValues(Expression expression) {
        if (expression.isStringLiteralExpr()) {
            return List.of(expression.asStringLiteralExpr().asString());
        }
        if (expression.isArrayInitializerExpr()) {
            return expression.asArrayInitializerExpr().getValues().stream()
                    .map(this::stringValue)
                    .flatMap(Optional::stream)
                    .toList();
        }
        return List.of();
    }

    private Optional<String> stringValue(Expression expression) {
        if (expression.isStringLiteralExpr()) {
            return Optional.of(expression.asStringLiteralExpr().asString());
        }
        return Optional.empty();
    }

    private String simpleName(String name) {
        int separatorIndex = name.lastIndexOf('.');
        return separatorIndex < 0 ? name : name.substring(separatorIndex + 1);
    }

    private String normalizePath(Path repositoryRoot, Path sourceFile) {
        return repositoryRoot.relativize(sourceFile).toString().replace('\\', '/');
    }
}
