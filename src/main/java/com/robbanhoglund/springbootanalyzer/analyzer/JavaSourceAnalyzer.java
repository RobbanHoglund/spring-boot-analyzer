package com.robbanhoglund.springbootanalyzer.analyzer;

import com.robbanhoglund.springbootanalyzer.analyzer.model.DetectedClass;
import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingSeverity;
import com.robbanhoglund.springbootanalyzer.analyzer.model.SpringComponentType;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public class JavaSourceAnalyzer {

    private static final Map<String, SpringComponentType> COMPONENT_TYPES = Map.of(
            "SpringBootApplication", SpringComponentType.MAIN_APPLICATION,
            "RestController", SpringComponentType.REST_CONTROLLER,
            "Controller", SpringComponentType.CONTROLLER,
            "Service", SpringComponentType.SERVICE,
            "Repository", SpringComponentType.REPOSITORY,
            "Component", SpringComponentType.COMPONENT,
            "Configuration", SpringComponentType.CONFIGURATION,
            "Entity", SpringComponentType.ENTITY,
            "ConfigurationProperties", SpringComponentType.CONFIGURATION_PROPERTIES
    );

    private final JavaParser javaParser;

    public JavaSourceAnalyzer() {
        this.javaParser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25)
                .setCharacterEncoding(StandardCharsets.UTF_8));
    }

    public SourceAnalysis analyze(Path repositoryRoot) {
        Path sourceRoot = repositoryRoot.resolve("src/main/java");
        if (Files.notExists(sourceRoot)) {
            return new SourceAnalysis(List.of(), List.of());
        }

        List<DetectedClass> detectedClasses = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();

        try (Stream<Path> files = Files.walk(sourceRoot)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted(Comparator.naturalOrder())
                    .forEach(path -> analyzeSourceFile(repositoryRoot, path, detectedClasses, findings));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to scan Java sources under " + sourceRoot, exception);
        }

        return new SourceAnalysis(List.copyOf(detectedClasses), List.copyOf(findings));
    }

    private void analyzeSourceFile(
            Path repositoryRoot,
            Path sourceFile,
            List<DetectedClass> detectedClasses,
            List<Finding> findings
    ) {
        ParseResult<CompilationUnit> parseResult;
        try {
            parseResult = javaParser.parse(sourceFile);
        } catch (IOException exception) {
            findings.add(new Finding(
                    FindingSeverity.WARNING,
                    "Failed to read Java source file: " + sourceFile.getFileName(),
                    normalizePath(repositoryRoot, sourceFile)
            ));
            return;
        }

        if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
            findings.add(new Finding(
                    FindingSeverity.WARNING,
                    buildParseFailureMessage(sourceFile, parseResult),
                    normalizePath(repositoryRoot, sourceFile)
            ));
            return;
        }

        CompilationUnit compilationUnit = parseResult.getResult().orElseThrow();
        String packageName = compilationUnit.getPackageDeclaration()
                .map(declaration -> declaration.getNameAsString())
                .orElse("");

        if (packageName.isBlank()) {
            findings.add(new Finding(
                    FindingSeverity.WARNING,
                    "Class is declared in the default package. Spring Boot recommends avoiding the default package.",
                    normalizePath(repositoryRoot, sourceFile)
            ));
        }

        for (TypeDeclaration<?> typeDeclaration : compilationUnit.findAll(TypeDeclaration.class)) {
            createDetectedClass(repositoryRoot, sourceFile, packageName, typeDeclaration).ifPresent(detectedClasses::add);
        }
    }

    private Optional<DetectedClass> createDetectedClass(
            Path repositoryRoot,
            Path sourceFile,
            String packageName,
            TypeDeclaration<?> typeDeclaration
    ) {
        Set<String> annotationNames = new LinkedHashSet<>();
        SpringComponentType componentType = null;

        for (AnnotationExpr annotation : typeDeclaration.getAnnotations()) {
            String annotationName = simpleName(annotation.getNameAsString());
            annotationNames.add(annotationName);
            componentType = chooseComponentType(componentType, annotationName);
        }

        if (componentType == null) {
            return Optional.empty();
        }

        String qualifiedClassName = buildQualifiedClassName(typeDeclaration, packageName);

        return Optional.of(new DetectedClass(
                qualifiedClassName,
                typeDeclaration.getNameAsString(),
                packageName,
                normalizePath(repositoryRoot, sourceFile),
                componentType,
                List.copyOf(annotationNames)
        ));
    }

    private SpringComponentType chooseComponentType(SpringComponentType currentType, String annotationName) {
        SpringComponentType candidate = COMPONENT_TYPES.get(annotationName);
        if (candidate == null) {
            return currentType;
        }
        if (currentType == null || candidate == SpringComponentType.MAIN_APPLICATION) {
            return candidate;
        }
        return currentType;
    }

    private String buildQualifiedClassName(TypeDeclaration<?> typeDeclaration, String packageName) {
        Deque<String> names = new ArrayDeque<>();
        Node current = typeDeclaration;

        while (current instanceof TypeDeclaration<?> currentType) {
            names.addFirst(currentType.getNameAsString());
            current = currentType.getParentNode().orElse(null);
        }

        String typeName = String.join(".", names);
        if (packageName.isBlank()) {
            return typeName;
        }
        return packageName + "." + typeName;
    }

    private String normalizePath(Path repositoryRoot, Path sourceFile) {
        return repositoryRoot.relativize(sourceFile).toString().replace('\\', '/');
    }

    private String simpleName(String name) {
        int separatorIndex = name.lastIndexOf('.');
        if (separatorIndex < 0) {
            return name;
        }
        return name.substring(separatorIndex + 1);
    }

    private String buildParseFailureMessage(Path sourceFile, ParseResult<CompilationUnit> parseResult) {
        String baseMessage = "Failed to parse Java source file: " + sourceFile.getFileName();
        String problemSummary = parseResult.getProblems().stream()
                .map(problem -> problem.getVerboseMessage().replace(System.lineSeparator(), " ").trim())
                .filter(message -> !message.isBlank())
                .findFirst()
                .orElse("");

        if (problemSummary.isBlank()) {
            return baseMessage;
        }

        return baseMessage + ". " + problemSummary;
    }

    public record SourceAnalysis(
            List<DetectedClass> detectedClasses,
            List<Finding> findings
    ) {
    }
}
