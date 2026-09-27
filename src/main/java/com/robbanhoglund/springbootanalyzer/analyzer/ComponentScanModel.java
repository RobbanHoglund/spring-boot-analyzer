package com.robbanhoglund.springbootanalyzer.analyzer;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.robbanhoglund.springbootanalyzer.analyzer.source.JavaSources;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The packages Spring's component scanning covers and the classes registered explicitly. Scanning
 * starts at each {@code @SpringBootApplication} package (or its {@code scanBasePackages}), and
 * every registered configuration class can widen it with {@code @ComponentScan} or add classes
 * with {@code @Import}. Auto-configurations listed in {@code AutoConfiguration.imports} or
 * {@code spring.factories} are registered no matter where they live.
 */
final class ComponentScanModel {

    private static final String AUTO_CONFIGURATION_IMPORTS =
            "src/main/resources/META-INF/spring/"
                    + "org.springframework.boot.autoconfigure.AutoConfiguration.imports";
    private static final String SPRING_FACTORIES = "src/main/resources/META-INF/spring.factories";
    private static final String ENABLE_AUTO_CONFIGURATION_KEY =
            "org.springframework.boot.autoconfigure.EnableAutoConfiguration";

    private final Set<String> scanRoots;
    private final Set<String> registeredClasses;

    private ComponentScanModel(Set<String> scanRoots, Set<String> registeredClasses) {
        this.scanRoots = scanRoots;
        this.registeredClasses = registeredClasses;
    }

    /** Whether component scanning covers {@code packageName}. */
    boolean isScanned(String packageName) {
        return scanRoots.stream()
                .anyMatch(root -> packageName.equals(root) || packageName.startsWith(root + "."));
    }

    /** Whether the class is registered explicitly (auto-configuration entry or {@code @Import}). */
    boolean isRegistered(String fullyQualifiedClassName) {
        return registeredClasses.contains(fullyQualifiedClassName);
    }

    Set<String> scanRoots() {
        return scanRoots;
    }

    static ComponentScanModel of(JavaSources sources, Collection<String> mainApplicationClasses) {
        Map<String, ClassInfo> classes = new LinkedHashMap<>();
        Map<String, String> simpleNameToFqn = new LinkedHashMap<>();
        for (JavaSources.JavaFile file : sources.files()) {
            CompilationUnit cu = file.compilationUnit();
            if (cu == null) {
                continue;
            }
            String packageName =
                    cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            for (ClassOrInterfaceDeclaration declaration :
                    cu.getTypes().stream()
                            .filter(type -> type instanceof ClassOrInterfaceDeclaration)
                            .map(type -> (ClassOrInterfaceDeclaration) type)
                            .toList()) {
                String fqn =
                        packageName.isEmpty()
                                ? declaration.getNameAsString()
                                : packageName + "." + declaration.getNameAsString();
                classes.put(fqn, new ClassInfo(fqn, packageName, declaration, cu));
                simpleNameToFqn.putIfAbsent(declaration.getNameAsString(), fqn);
            }
        }

        Set<String> roots = new LinkedHashSet<>();
        for (String mainClass : mainApplicationClasses) {
            int dot = mainClass.lastIndexOf('.');
            if (dot > 0) {
                roots.add(mainClass.substring(0, dot));
            }
        }
        Set<String> registered =
                new LinkedHashSet<>(autoConfigurationEntries(sources.repositoryRoot()));

        // Widen the model until no registered configuration adds another package or class.
        boolean changed = true;
        for (int round = 0; changed && round < 10; round++) {
            changed = false;
            for (ClassInfo info : classes.values()) {
                boolean active =
                        info.hasAnnotation("SpringBootApplication")
                                || registered.contains(info.fqn())
                                || isUnder(roots, info.packageName());
                if (!active) {
                    continue;
                }
                AnnotationExpr application = info.annotation("SpringBootApplication");
                if (application != null) {
                    changed |= roots.add(info.packageName());
                    changed |=
                            roots.addAll(
                                    packages(
                                            application,
                                            "scanBasePackages",
                                            info,
                                            simpleNameToFqn));
                }
                AnnotationExpr componentScan = info.annotation("ComponentScan");
                if (componentScan != null) {
                    List<String> scanned =
                            packages(componentScan, "basePackages", info, simpleNameToFqn);
                    changed |=
                            scanned.isEmpty()
                                    ? roots.add(info.packageName())
                                    : roots.addAll(scanned);
                }
                AnnotationExpr imports = info.annotation("Import");
                if (imports != null) {
                    for (Expression value : memberValues(imports, "value")) {
                        if (value instanceof ClassExpr classExpr) {
                            String imported =
                                    resolve(classExpr.getType().asString(), info, simpleNameToFqn);
                            if (imported != null) {
                                changed |= registered.add(imported);
                            }
                        }
                    }
                }
            }
        }
        return new ComponentScanModel(Set.copyOf(roots), Set.copyOf(registered));
    }

    private static boolean isUnder(Set<String> roots, String packageName) {
        return roots.stream()
                .anyMatch(root -> packageName.equals(root) || packageName.startsWith(root + "."));
    }

    /**
     * Package names from a string attribute ({@code scanBasePackages}, {@code basePackages} or the
     * {@code value} alias) plus the packages of the matching {@code ...Classes} attribute.
     */
    private static List<String> packages(
            AnnotationExpr annotation,
            String packagesAttribute,
            ClassInfo owner,
            Map<String, String> simpleNameToFqn) {
        List<String> packages = new ArrayList<>();
        List<Expression> values = new ArrayList<>(memberValues(annotation, packagesAttribute));
        if (!"scanBasePackages".equals(packagesAttribute)) {
            values.addAll(memberValues(annotation, "value"));
        }
        for (Expression value : values) {
            if (value instanceof StringLiteralExpr literal) {
                for (String part : literal.asString().split("[,;\\s]+")) {
                    if (!part.isBlank()) {
                        packages.add(part.trim());
                    }
                }
            }
        }
        String classesAttribute =
                "scanBasePackages".equals(packagesAttribute)
                        ? "scanBasePackageClasses"
                        : "basePackageClasses";
        for (Expression value : memberValues(annotation, classesAttribute)) {
            if (value instanceof ClassExpr classExpr) {
                String resolved = resolve(classExpr.getType().asString(), owner, simpleNameToFqn);
                if (resolved != null && resolved.contains(".")) {
                    packages.add(resolved.substring(0, resolved.lastIndexOf('.')));
                }
            }
        }
        return packages;
    }

    private static List<Expression> memberValues(AnnotationExpr annotation, String attribute) {
        List<Expression> values = new ArrayList<>();
        if (annotation.isSingleMemberAnnotationExpr()) {
            if ("value".equals(attribute)) {
                flatten(annotation.asSingleMemberAnnotationExpr().getMemberValue(), values);
            }
        } else if (annotation.isNormalAnnotationExpr()) {
            annotation.asNormalAnnotationExpr().getPairs().stream()
                    .filter(pair -> attribute.equals(pair.getNameAsString()))
                    .forEach(pair -> flatten(pair.getValue(), values));
        }
        return values;
    }

    private static void flatten(Expression expression, List<Expression> values) {
        if (expression instanceof ArrayInitializerExpr array) {
            array.getValues().forEach(values::add);
        } else {
            values.add(expression);
        }
    }

    private static String resolve(
            String typeName, ClassInfo owner, Map<String, String> simpleNameToFqn) {
        if (typeName.contains(".")) {
            return typeName;
        }
        for (var importDeclaration : owner.cu().getImports()) {
            String imported = importDeclaration.getNameAsString();
            if (!importDeclaration.isAsterisk() && imported.endsWith("." + typeName)) {
                return imported;
            }
        }
        String samePackage =
                owner.packageName().isEmpty() ? typeName : owner.packageName() + "." + typeName;
        return simpleNameToFqn.getOrDefault(typeName, samePackage);
    }

    /** Simple class names listed in AutoConfiguration.imports or spring.factories. */
    static Set<String> autoConfigurationSimpleNames(JavaSources sources) {
        Set<String> names = new LinkedHashSet<>();
        for (String entry : autoConfigurationEntries(sources.repositoryRoot())) {
            names.add(entry.contains(".") ? entry.substring(entry.lastIndexOf('.') + 1) : entry);
        }
        return names;
    }

    private static Set<String> autoConfigurationEntries(Path repositoryRoot) {
        Set<String> entries = new LinkedHashSet<>();
        Path imports = repositoryRoot.resolve(AUTO_CONFIGURATION_IMPORTS);
        if (Files.isRegularFile(imports)) {
            try {
                for (String line : Files.readAllLines(imports, StandardCharsets.UTF_8)) {
                    String entry = line.strip();
                    if (!entry.isEmpty() && !entry.startsWith("#")) {
                        entries.add(entry);
                    }
                }
            } catch (IOException ignored) {
                // Best effort: an unreadable file registers nothing.
            }
        }
        Path factories = repositoryRoot.resolve(SPRING_FACTORIES);
        if (Files.isRegularFile(factories)) {
            try {
                String content =
                        Files.readString(factories, StandardCharsets.UTF_8)
                                .replace("\\\r\n", "")
                                .replace("\\\n", "");
                for (String line : content.split("\\R")) {
                    String trimmed = line.strip();
                    if (trimmed.startsWith(ENABLE_AUTO_CONFIGURATION_KEY + "=")) {
                        for (String entry :
                                trimmed.substring(ENABLE_AUTO_CONFIGURATION_KEY.length() + 1)
                                        .split(",")) {
                            if (!entry.isBlank()) {
                                entries.add(entry.strip());
                            }
                        }
                    }
                }
            } catch (IOException ignored) {
                // Best effort: an unreadable file registers nothing.
            }
        }
        return entries;
    }

    private record ClassInfo(
            String fqn,
            String packageName,
            ClassOrInterfaceDeclaration declaration,
            CompilationUnit cu) {

        boolean hasAnnotation(String simpleName) {
            return annotation(simpleName) != null;
        }

        AnnotationExpr annotation(String simpleName) {
            for (AnnotationExpr annotation : declaration.getAnnotations()) {
                String name = annotation.getNameAsString();
                String bare = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
                if (bare.equals(simpleName)) {
                    return annotation;
                }
            }
            return null;
        }
    }
}
