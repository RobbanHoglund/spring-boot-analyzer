package com.robbanhoglund.springbootanalyzer.analyzer;

import static java.util.Map.entry;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.robbanhoglund.springbootanalyzer.analyzer.model.DetectedClass;
import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingSeverity;
import com.robbanhoglund.springbootanalyzer.analyzer.model.SpringComponentType;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Scans the Java source tree ({@code src/main/java}) and produces a list of every class
 * annotated with a recognised Spring stereotype.
 *
 * <p>Parsing is performed with <a href="https://javaparser.org/">JavaParser</a> configured
 * at Java 25 language level. Nested types are handled by walking the AST upward and joining
 * the enclosing type names with dots so that, for example, an inner {@code @Configuration}
 * class resolves to {@code com.example.Outer.InnerConfig} rather than just {@code InnerConfig}.
 *
 * <p>The recognised stereotypes and the {@link SpringComponentType} they map to are defined
 * in the {@code COMPONENT_TYPES} map. {@code MAIN_APPLICATION} ({@code @SpringBootApplication})
 * always wins over any other annotation on the same type declaration; for all other annotations
 * the first recognised one wins.
 *
 * <p>Parse failures and files that contain no recognised Spring types are silently skipped.
 * A class declared without a package statement produces a {@link Finding} with severity
 * {@code WARNING} rather than a hard error.
 */
@Component
public class JavaSourceAnalyzer {

    private static final Logger LOGGER = LoggerFactory.getLogger(JavaSourceAnalyzer.class);

    private static final Map<String, SpringComponentType> COMPONENT_TYPES =
            Map.ofEntries(
                    entry("SpringBootApplication", SpringComponentType.MAIN_APPLICATION),
                    entry("RestController", SpringComponentType.REST_CONTROLLER),
                    entry("Controller", SpringComponentType.CONTROLLER),
                    entry("ControllerAdvice", SpringComponentType.CONTROLLER_ADVICE),
                    entry("RestControllerAdvice", SpringComponentType.CONTROLLER_ADVICE),
                    entry("Service", SpringComponentType.SERVICE),
                    entry("Repository", SpringComponentType.REPOSITORY),
                    entry("Component", SpringComponentType.COMPONENT),
                    entry("Configuration", SpringComponentType.CONFIGURATION),
                    entry("Entity", SpringComponentType.ENTITY),
                    entry("ConfigurationProperties", SpringComponentType.CONFIGURATION_PROPERTIES));

    private final JavaParser javaParser;

    public JavaSourceAnalyzer() {
        this.javaParser =
                new JavaParser(
                        new ParserConfiguration()
                                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25)
                                .setCharacterEncoding(StandardCharsets.UTF_8));
    }

    /**
     * Walks every {@code .java} file under {@code <repositoryRoot>/src/main/java}, parses each
     * one with JavaParser, and returns a {@link SourceAnalysis} containing:
     * <ul>
     *   <li>all {@link DetectedClass} instances for types carrying a recognised Spring stereotype</li>
     *   <li>any structural {@link Finding}s (e.g. classes in the default package)</li>
     * </ul>
     *
     * <p>Files that fail to parse or that contain no recognised Spring types are silently
     * excluded from the result. If {@code src/main/java} does not exist under the repository
     * root an empty {@code SourceAnalysis} is returned immediately.
     *
     * @param repositoryRoot root directory of the project being analysed
     * @return the combined source analysis result; never null. If the source tree cannot be fully
     *     walked due to an I/O error, the partial results gathered so far are returned.
     */
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
                    .forEach(
                            path ->
                                    analyzeSourceFile(
                                            repositoryRoot, path, detectedClasses, findings));
        } catch (IOException exception) {
            LOGGER.warn(
                    "Failed to fully scan Java sources under {}; returning partial results",
                    sourceRoot,
                    exception);
        }

        return new SourceAnalysis(List.copyOf(detectedClasses), List.copyOf(findings));
    }

    private void analyzeSourceFile(
            Path repositoryRoot,
            Path sourceFile,
            List<DetectedClass> detectedClasses,
            List<Finding> findings) {
        ParseResult<CompilationUnit> parseResult;
        try {
            parseResult = javaParser.parse(sourceFile);
        } catch (IOException exception) {
            return;
        }

        if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
            return;
        }

        CompilationUnit compilationUnit = parseResult.getResult().orElseThrow();
        String packageName =
                compilationUnit
                        .getPackageDeclaration()
                        .map(declaration -> declaration.getNameAsString())
                        .orElse("");

        if (packageName.isBlank()) {
            findings.add(
                    new Finding(
                            FindingSeverity.WARNING,
                            "Class is declared in the default package. Spring Boot recommends"
                                    + " avoiding the default package.",
                            normalizePath(repositoryRoot, sourceFile)));
        }

        for (TypeDeclaration<?> typeDeclaration : compilationUnit.findAll(TypeDeclaration.class)) {
            createDetectedClass(repositoryRoot, sourceFile, packageName, typeDeclaration)
                    .ifPresent(detectedClasses::add);
        }
    }

    private Optional<DetectedClass> createDetectedClass(
            Path repositoryRoot,
            Path sourceFile,
            String packageName,
            TypeDeclaration<?> typeDeclaration) {
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

        return Optional.of(
                new DetectedClass(
                        qualifiedClassName,
                        typeDeclaration.getNameAsString(),
                        packageName,
                        normalizePath(repositoryRoot, sourceFile),
                        componentType,
                        List.copyOf(annotationNames)));
    }

    private SpringComponentType chooseComponentType(
            SpringComponentType currentType, String annotationName) {
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

    /**
     * The result of scanning the Java source tree.
     *
     * @param detectedClasses every class annotated with a recognised Spring stereotype,
     *                        in the order they were discovered (sorted by file path)
     * @param findings        structural findings such as default-package warnings; never null
     */
    public record SourceAnalysis(List<DetectedClass> detectedClasses, List<Finding> findings) {}
}
