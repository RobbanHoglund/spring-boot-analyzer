package com.robbanhoglund.springbootanalyzer.analyzer.runtime;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildInfo;
import com.robbanhoglund.springbootanalyzer.analyzer.model.DetectedClass;
import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingCategory;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingConfidence;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingFactory;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingRules;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingRuntimeDetection;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingSeverity;
import com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.ApplicationProperty;
import com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.ConfigurationAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleJavaToolchainModel;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleModelAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleResolvedDependencyModel;
import com.robbanhoglund.springbootanalyzer.analyzer.model.runtime.RuntimeStackAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.runtime.VirtualThreadAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.runtime.WebStack;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public class RuntimeStackAnalyzer {
    private final JavaParser javaParser =
            new JavaParser(
                    new ParserConfiguration()
                            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25)
                            .setCharacterEncoding(StandardCharsets.UTF_8));

    public Result analyze(
            Path repositoryRoot,
            BuildInfo buildInfo,
            GradleModelAnalysis gradleModelAnalysis,
            ConfigurationAnalysis configurationAnalysis,
            List<DetectedClass> detectedComponents,
            List<String> mainApplicationClasses) {
        // Resolve versions first so that analyzeVirtualThreads and finding rules use the
        // most accurate values — Gradle model data takes precedence over static build-file hints.
        String springBootVersion = gradleResolvedSpringBootVersion(gradleModelAnalysis);
        String springBootVersionSource;
        if (springBootVersion != null) {
            springBootVersionSource = "Gradle resolved";
        } else {
            springBootVersion = buildInfo.springBootVersion();
            springBootVersionSource = buildInfo.springBootVersionSource();
        }

        String javaVersion = gradleToolchainJavaVersion(gradleModelAnalysis);
        if (javaVersion == null) {
            javaVersion = buildInfo.javaVersionHint();
        }

        RuntimeEvidence evidence = collectRuntimeEvidence(repositoryRoot, detectedComponents);
        List<String> dependencyCoordinates = runtimeDependencies(buildInfo, gradleModelAnalysis);
        String configuredWebApplicationType =
                configuredPropertyValue(configurationAnalysis, "spring.main.web-application-type");

        WebStack webStack =
                determineWebStack(
                        dependencyCoordinates,
                        buildInfo,
                        configuredWebApplicationType,
                        evidence,
                        detectedComponents);
        String webStackReason =
                determineWebStackReason(
                        dependencyCoordinates,
                        buildInfo,
                        configuredWebApplicationType,
                        evidence,
                        webStack);

        VirtualThreadAnalysis virtualThreads =
                analyzeVirtualThreads(javaVersion, configurationAnalysis, evidence);

        List<Finding> findings = new ArrayList<>();
        addVirtualThreadFindings(virtualThreads, findings);
        addWebStackFindings(dependencyCoordinates, buildInfo, webStack, evidence, findings);
        addJavaVersionFindings(
                springBootVersion, javaVersion, virtualThreads.enabledByProperty(), findings);

        String mainClass = mainApplicationClasses.isEmpty() ? null : mainApplicationClasses.get(0);

        RuntimeStackAnalysis analysis =
                new RuntimeStackAnalysis(
                        springBootVersion,
                        springBootVersionSource,
                        javaVersion,
                        webStack,
                        webStackReason,
                        virtualThreads,
                        mainClass);
        return new Result(analysis, List.copyOf(findings));
    }

    private RuntimeEvidence collectRuntimeEvidence(
            Path repositoryRoot, List<DetectedClass> detectedComponents) {
        Path sourceRoot = repositoryRoot.resolve("src/main/java");
        if (Files.notExists(sourceRoot)) {
            return new RuntimeEvidence(false, false, false, false, false, List.of());
        }

        boolean scheduledDetected = false;
        boolean enableSchedulingDetected = false;
        boolean directVirtualThreadUsage = false;
        boolean reactiveSignalDetected = false;
        boolean routerFunctionDetected = false;
        Set<String> evidence = new LinkedHashSet<>();

        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file :
                    files.filter(Files::isRegularFile)
                            .filter(path -> path.toString().endsWith(".java"))
                            .sorted(Comparator.naturalOrder())
                            .toList()) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                String relativePath = repositoryRoot.relativize(file).toString().replace('\\', '/');

                CompilationUnit compilationUnit = parseCompilationUnit(file);
                if (hasAnnotation(compilationUnit, "Scheduled")) {
                    scheduledDetected = true;
                    evidence.add("@Scheduled in " + relativePath);
                }
                if (hasAnnotation(compilationUnit, "EnableScheduling")) {
                    enableSchedulingDetected = true;
                    evidence.add("@EnableScheduling in " + relativePath);
                }
                if (content.contains("Thread.ofVirtual(")
                        || content.contains("Thread.startVirtualThread(")
                        || content.contains("Executors.newVirtualThreadPerTaskExecutor(")) {
                    directVirtualThreadUsage = true;
                    evidence.add("Virtual thread API usage in " + relativePath);
                }
                if (content.contains("reactor.core.publisher.Mono")
                        || content.contains("reactor.core.publisher.Flux")
                        || content.contains("Mono<")
                        || content.contains("Flux<")) {
                    reactiveSignalDetected = true;
                    evidence.add("Reactive types in " + relativePath);
                }
                if (content.contains("RouterFunction<")
                        || content.contains("RouterFunctions.route(")
                        || content.contains("RequestPredicates.")) {
                    routerFunctionDetected = true;
                    evidence.add("WebFlux routing API in " + relativePath);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to scan source files for runtime stack analysis", exception);
        }

        boolean controllerDetected =
                detectedComponents.stream()
                        .anyMatch(
                                component ->
                                        "REST_CONTROLLER"
                                                        .equalsIgnoreCase(
                                                                component.componentType().name())
                                                || "CONTROLLER"
                                                        .equalsIgnoreCase(
                                                                component.componentType().name()));

        return new RuntimeEvidence(
                scheduledDetected,
                enableSchedulingDetected,
                directVirtualThreadUsage,
                reactiveSignalDetected,
                routerFunctionDetected || controllerDetected,
                List.copyOf(evidence));
    }

    private CompilationUnit parseCompilationUnit(Path file) {
        try {
            return javaParser.parse(file).getResult().orElse(null);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to parse Java source for runtime stack analysis: " + file, exception);
        }
    }

    private boolean hasAnnotation(CompilationUnit compilationUnit, String annotationSimpleName) {
        if (compilationUnit == null) {
            return false;
        }
        return compilationUnit.findAll(AnnotationExpr.class).stream()
                .map(annotation -> annotation.getName().getIdentifier())
                .anyMatch(annotationSimpleName::equals);
    }

    private WebStack determineWebStack(
            List<String> dependencyCoordinates,
            BuildInfo buildInfo,
            String configuredWebApplicationType,
            RuntimeEvidence evidence,
            List<DetectedClass> detectedComponents) {
        if (configuredWebApplicationType != null) {
            return switch (configuredWebApplicationType.toLowerCase(Locale.ROOT)) {
                case "servlet" -> WebStack.SERVLET_MVC;
                case "reactive" -> WebStack.REACTIVE_WEBFLUX;
                case "none" -> WebStack.NON_WEB;
                default -> WebStack.UNKNOWN;
            };
        }

        boolean servletDependency =
                dependencyCoordinates.stream().anyMatch(this::isServletDependency);
        boolean reactiveDependency =
                dependencyCoordinates.stream().anyMatch(this::isReactiveDependency);

        if (servletDependency && reactiveDependency) {
            return WebStack.MIXED_MVC_AND_WEBFLUX;
        }
        if (servletDependency) {
            return WebStack.SERVLET_MVC;
        }
        if (reactiveDependency) {
            return WebStack.REACTIVE_WEBFLUX;
        }
        if (detectedComponents.stream()
                .anyMatch(component -> component.componentType().name().contains("CONTROLLER"))) {
            return WebStack.SERVLET_MVC;
        }
        if (evidence.reactiveSignalDetected()) {
            return WebStack.REACTIVE_WEBFLUX;
        }
        return buildInfo.springBootDetected() ? WebStack.NON_WEB : WebStack.UNKNOWN;
    }

    private String determineWebStackReason(
            List<String> dependencyCoordinates,
            BuildInfo buildInfo,
            String configuredWebApplicationType,
            RuntimeEvidence evidence,
            WebStack webStack) {
        if (configuredWebApplicationType != null) {
            return "Configured via spring.main.web-application-type="
                    + configuredWebApplicationType;
        }

        boolean servletDependency =
                dependencyCoordinates.stream().anyMatch(this::isServletDependency);
        boolean reactiveDependency =
                dependencyCoordinates.stream().anyMatch(this::isReactiveDependency);

        if (servletDependency && reactiveDependency) {
            return "Both Spring MVC/Servlet and WebFlux dependencies were detected. Spring MVC"
                    + " typically wins auto-configuration precedence.";
        }
        if (servletDependency && evidence.webSignalDetected()) {
            return "Spring MVC annotations and servlet web dependency declarations were detected.";
        }
        if (servletDependency) {
            return "Servlet web dependencies were detected in the build.";
        }
        if (reactiveDependency) {
            return "Reactive WebFlux dependencies were detected in the build.";
        }
        if (webStack == WebStack.SERVLET_MVC && evidence.webSignalDetected()) {
            return "Detected from Spring MVC annotations in source files.";
        }
        if (evidence.reactiveSignalDetected()) {
            return "Reactive code patterns were detected in source files.";
        }
        if (webStack == WebStack.NON_WEB) {
            return "No web starter or explicit web application type was detected.";
        }
        return "No strong runtime stack signal was detected.";
    }

    private VirtualThreadAnalysis analyzeVirtualThreads(
            String javaVersion,
            ConfigurationAnalysis configurationAnalysis,
            RuntimeEvidence evidence) {
        boolean enabledByProperty =
                "true"
                        .equalsIgnoreCase(
                                configuredPropertyValue(
                                        configurationAnalysis, "spring.threads.virtual.enabled"));
        boolean keepAliveConfigured =
                "true"
                        .equalsIgnoreCase(
                                configuredPropertyValue(
                                        configurationAnalysis, "spring.main.keep-alive"));
        boolean javaVersionCompatible = parseJavaVersion(javaVersion) >= 21;
        boolean scheduledWorkDetected =
                evidence.scheduledDetected() || evidence.enableSchedulingDetected();

        List<String> evidenceLines = new ArrayList<>(evidence.evidence());
        if (enabledByProperty) {
            evidenceLines.add("spring.threads.virtual.enabled=true");
        }
        if (keepAliveConfigured) {
            evidenceLines.add("spring.main.keep-alive=true");
        }

        String summary;
        if (enabledByProperty && javaVersionCompatible) {
            summary =
                    scheduledWorkDetected && !keepAliveConfigured
                            ? "Enabled, but scheduled work may need spring.main.keep-alive=true."
                            : "Enabled";
        } else if (enabledByProperty) {
            summary = "Configured, but the detected Java version may not support virtual threads.";
        } else if (evidence.directVirtualThreadUsage()) {
            summary = "Direct API usage";
        } else if (!javaVersionCompatible) {
            summary = "Java not compatible";
        } else {
            summary = "Disabled";
        }

        return new VirtualThreadAnalysis(
                enabledByProperty,
                javaVersionCompatible,
                evidence.directVirtualThreadUsage(),
                scheduledWorkDetected,
                keepAliveConfigured,
                summary,
                List.copyOf(evidenceLines));
    }

    private void addVirtualThreadFindings(VirtualThreadAnalysis analysis, List<Finding> findings) {
        // Java version incompatibility is handled by addJavaVersionFindings so it gets a proper
        // rule ID and richer finding body.
        if (analysis.enabledByProperty()
                && analysis.scheduledWorkDetected()
                && !analysis.keepAliveConfigured()) {
            findings.add(
                    new Finding(
                            FindingSeverity.INFO,
                            "Virtual threads are enabled and scheduled work was detected, but"
                                    + " spring.main.keep-alive=true was not found.",
                            null));
        }
    }

    private void addJavaVersionFindings(
            String springBootVersion,
            String javaVersion,
            boolean virtualThreadsEnabled,
            List<Finding> findings) {
        int javaMajor = parseJavaVersion(javaVersion);
        int bootMajor = parseMajorVersion(springBootVersion);

        if (bootMajor == 3 && javaMajor > 0 && javaMajor < 17) {
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_BOOT3_REQUIRES_JAVA17,
                                    FindingConfidence.HIGH)
                            .shortMessage(
                                    "Spring Boot "
                                            + springBootVersion
                                            + " requires Java 17 or later, but Java "
                                            + javaVersion
                                            + " was detected.")
                            .whyBadPractice(
                                    "Spring Boot 3.x requires Java 17 as a baseline. Running on"
                                            + " an older JVM will cause a hard startup failure.")
                            .possibleImpact(
                                    "The application will not start. Spring Boot 3 uses APIs and"
                                        + " bytecode features only available from Java 17 onwards.")
                            .recommendation(
                                    "Upgrade to Java 17 or later. Spring Boot 3.3+ also supports"
                                        + " Java 21 with virtual-thread and record improvements.")
                            .evidence(
                                    "Spring Boot "
                                            + springBootVersion
                                            + "; detected Java "
                                            + javaVersion)
                            .target("java.version")
                            .build());
        }

        if (virtualThreadsEnabled && javaMajor > 0 && javaMajor < 21) {
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_VIRTUAL_THREADS_JAVA_TOO_OLD,
                                    FindingConfidence.HIGH)
                            .shortMessage(
                                    "spring.threads.virtual.enabled=true requires Java 21 or"
                                            + " later, but Java "
                                            + javaVersion
                                            + " was detected.")
                            .whyBadPractice(
                                    "Virtual threads (Project Loom) are a Java 21 feature. Enabling"
                                            + " them on an older JVM causes a startup failure or"
                                            + " silently falls back to platform threads.")
                            .possibleImpact(
                                    "The application may fail to start, or virtual threads may be"
                                        + " silently disabled, negating any throughput benefit.")
                            .recommendation(
                                    "Upgrade to Java 21 or later, or remove"
                                            + " spring.threads.virtual.enabled=true until the JVM"
                                            + " is updated.")
                            .evidence(
                                    "spring.threads.virtual.enabled=true; detected Java "
                                            + javaVersion)
                            .target("spring.threads.virtual.enabled")
                            .build());
        }
    }

    private int parseMajorVersion(String version) {
        if (version == null || version.isBlank()) return -1;
        try {
            return Integer.parseInt(version.replaceAll("[^0-9].*$", ""));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private void addWebStackFindings(
            List<String> dependencyCoordinates,
            BuildInfo buildInfo,
            WebStack webStack,
            RuntimeEvidence evidence,
            List<Finding> findings) {
        if (webStack == WebStack.MIXED_MVC_AND_WEBFLUX) {
            findings.add(
                    new Finding(
                            FindingSeverity.INFO,
                            "Both Spring MVC/Servlet and WebFlux dependencies were detected. Spring"
                                    + " MVC usually takes precedence unless"
                                    + " spring.main.web-application-type overrides it.",
                            null));
        }
        if (webStack == WebStack.SERVLET_MVC && evidence.reactiveSignalDetected()) {
            String reactiveEvidence =
                    evidence.evidence().stream()
                            .filter(
                                    item ->
                                            item.startsWith("Reactive types in ")
                                                    || item.startsWith("WebFlux routing API in "))
                            .limit(4)
                            .reduce((left, right) -> left + ", " + right)
                            .orElse("Reactive types or routing APIs were detected in source code.");
            findings.add(
                    FindingFactory.builder(
                                    "SPRING_REACTIVE_API_IN_SERVLET_APP",
                                    "Reactive API usage in Servlet application",
                                    FindingSeverity.INFO,
                                    FindingCategory.API_SURFACE,
                                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED,
                                    FindingConfidence.MEDIUM)
                            .shortMessage(
                                    "Reactive APIs were detected in code, but the build currently"
                                            + " looks like a Servlet/MVC application.")
                            .whyBadPractice(
                                    "Mixing reactive APIs into a primarily Servlet/MVC application"
                                            + " can make execution and error-handling assumptions"
                                            + " harder to follow during review.")
                            .possibleImpact(
                                    "Developers may assume reactive behavior or shared"
                                            + " infrastructure where the deployed application still"
                                            + " behaves like a traditional Servlet stack.")
                            .recommendation(
                                    "Review whether the reactive usage is intentional, documented,"
                                        + " and compatible with the application's main web stack.")
                            .evidence(reactiveEvidence)
                            .limitations(
                                    "Static analysis can infer API usage from source and"
                                        + " dependencies, but runtime behavior may still differ by"
                                        + " profile, classpath, and configuration.")
                            .target("reactive APIs")
                            .build());
        }
        if (webStack == WebStack.NON_WEB
                && dependencyCoordinates.stream().anyMatch(this::isServletDependency)) {
            findings.add(
                    new Finding(
                            FindingSeverity.INFO,
                            "Web dependencies were detected, but configuration indicates a non-web"
                                    + " application type.",
                            null));
        }
    }

    private String configuredPropertyValue(
            ConfigurationAnalysis configurationAnalysis, String name) {
        if (configurationAnalysis == null || configurationAnalysis.properties() == null) {
            return null;
        }
        return configurationAnalysis.properties().stream()
                .filter(property -> name.equals(property.name()))
                .map(ApplicationProperty::value)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }

    private int parseJavaVersion(String javaVersionHint) {
        if (javaVersionHint == null || javaVersionHint.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(javaVersionHint.replaceAll("[^0-9].*$", ""));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private boolean isServletDependency(String dependency) {
        String normalized = dependency.toLowerCase(Locale.ROOT);
        return normalized.contains("spring-boot-starter-web")
                || normalized.contains("spring-webmvc")
                || normalized.contains("starter-tomcat")
                || normalized.contains("starter-jetty")
                || normalized.contains("starter-undertow");
    }

    private boolean isReactiveDependency(String dependency) {
        String normalized = dependency.toLowerCase(Locale.ROOT);
        return normalized.contains("spring-boot-starter-webflux")
                || normalized.contains("spring-webflux")
                || normalized.contains("reactor-netty");
    }

    /**
     * Extracts the resolved Spring Boot version from the Gradle model when the model ran
     * successfully. All {@code org.springframework.boot} dependencies resolve to the same
     * BOM-managed version, so any one of them gives the authoritative version.
     *
     * <p>Returns {@code null} if the model is absent, was not successful, or has no Spring Boot
     * dependencies with a resolvable version.
     */
    private String gradleResolvedSpringBootVersion(GradleModelAnalysis gradleModelAnalysis) {
        if (gradleModelAnalysis == null || gradleModelAnalysis.resolvedDependencies() == null) {
            return null;
        }
        String statusName =
                gradleModelAnalysis.status() == null ? "" : gradleModelAnalysis.status().name();
        if (!statusName.startsWith("SUCCESS") && !statusName.equals("PARTIAL")) {
            return null;
        }
        return gradleModelAnalysis.resolvedDependencies().stream()
                .filter(dep -> "org.springframework.boot".equals(dep.group()))
                .map(GradleResolvedDependencyModel::version)
                .filter(v -> v != null && !v.isBlank())
                .findFirst()
                .orElse(null);
    }

    /**
     * Extracts the configured Java toolchain language version from the Gradle model.
     * The toolchain version is the most explicit signal for the project's target Java version —
     * more reliable than {@code sourceCompatibility} or build-file regex extraction.
     *
     * <p>Returns {@code null} if no toolchain is configured or the model is absent.
     */
    private String gradleToolchainJavaVersion(GradleModelAnalysis gradleModelAnalysis) {
        if (gradleModelAnalysis == null || gradleModelAnalysis.javaToolchains() == null) {
            return null;
        }
        String statusName =
                gradleModelAnalysis.status() == null ? "" : gradleModelAnalysis.status().name();
        if (!statusName.startsWith("SUCCESS") && !statusName.equals("PARTIAL")) {
            return null;
        }
        return gradleModelAnalysis.javaToolchains().stream()
                .map(GradleJavaToolchainModel::languageVersion)
                .filter(v -> v != null && !v.isBlank())
                .findFirst()
                .orElse(null);
    }

    private List<String> runtimeDependencies(
            BuildInfo buildInfo, GradleModelAnalysis gradleModelAnalysis) {
        if (gradleModelAnalysis != null
                && gradleModelAnalysis.resolvedDependencies() != null
                && !gradleModelAnalysis.resolvedDependencies().isEmpty()) {
            return gradleModelAnalysis.resolvedDependencies().stream()
                    .map(this::coordinate)
                    .distinct()
                    .toList();
        }
        return buildInfo.dependencies();
    }

    private String coordinate(GradleResolvedDependencyModel dependency) {
        return (dependency.group() == null ? "" : dependency.group())
                + ":"
                + (dependency.artifact() == null ? "" : dependency.artifact());
    }

    public record Result(RuntimeStackAnalysis runtimeStackAnalysis, List<Finding> findings) {}

    private record RuntimeEvidence(
            boolean scheduledDetected,
            boolean enableSchedulingDetected,
            boolean directVirtualThreadUsage,
            boolean reactiveSignalDetected,
            boolean webSignalDetected,
            List<String> evidence) {}
}
