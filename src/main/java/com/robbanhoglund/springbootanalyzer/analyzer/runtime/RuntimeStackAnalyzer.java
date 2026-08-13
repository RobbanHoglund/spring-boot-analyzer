package com.robbanhoglund.springbootanalyzer.analyzer.runtime;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildInfo;
import com.robbanhoglund.springbootanalyzer.analyzer.model.DetectedClass;
import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingConfidence;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingFactory;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingRules;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RuntimeStackAnalyzer {

    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeStackAnalyzer.class);

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
        addWebStackFindings(
                dependencyCoordinates, configuredWebApplicationType, webStack, evidence, findings);
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
            return new RuntimeEvidence(false, false, false, false, false, false, List.of());
        }

        boolean scheduledDetected = false;
        boolean enableSchedulingDetected = false;
        boolean directVirtualThreadUsage = false;
        boolean reactiveSignalDetected = false;
        boolean webFluxRoutingDetected = false;
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
                if (usesWebFluxServerApi(compilationUnit)) {
                    webFluxRoutingDetected = true;
                    evidence.add("WebFlux routing API in " + relativePath);
                }
            }
        } catch (IOException exception) {
            LOGGER.warn(
                    "Failed to fully scan source files for runtime stack analysis;"
                            + " using partial evidence",
                    exception);
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
                webFluxRoutingDetected,
                controllerDetected,
                List.copyOf(evidence));
    }

    private CompilationUnit parseCompilationUnit(Path file) {
        try {
            return javaParser.parse(file).getResult().orElse(null);
        } catch (IOException exception) {
            // Skip an individual unreadable file rather than aborting runtime stack analysis.
            LOGGER.debug(
                    "Failed to parse {} for runtime stack analysis; skipping", file, exception);
            return null;
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

    private boolean usesWebFluxServerApi(CompilationUnit compilationUnit) {
        if (compilationUnit == null) {
            return false;
        }
        boolean webFluxImport =
                compilationUnit.getImports().stream()
                        .map(importDeclaration -> importDeclaration.getNameAsString())
                        .anyMatch(
                                name ->
                                        name.startsWith(
                                                        "org.springframework.web.reactive.function.server")
                                                || name.equals(
                                                        "org.springframework.web.reactive.config.WebFluxConfigurer")
                                                || name.equals(
                                                        "org.springframework.web.reactive.config.EnableWebFlux"));
        return webFluxImport || hasAnnotation(compilationUnit, "EnableWebFlux");
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
            // This is a mixed classpath, not a mixed running server. Spring Boot chooses the
            // Servlet application type when both framework stacks are present unless the user
            // explicitly selected REACTIVE above.
            return WebStack.SERVLET_MVC;
        }
        if (servletDependency) {
            return WebStack.SERVLET_MVC;
        }
        if (reactiveDependency) {
            return WebStack.REACTIVE_WEBFLUX;
        }
        if (evidence.webFluxRoutingDetected()) {
            return WebStack.REACTIVE_WEBFLUX;
        }
        if (detectedComponents.stream()
                .anyMatch(component -> component.componentType().name().contains("CONTROLLER"))) {
            return WebStack.SERVLET_MVC;
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
            return "Both Spring MVC/Servlet and WebFlux dependencies were detected. Spring Boot"
                    + " selects the Servlet/MVC application type by default; the WebFlux"
                    + " dependency may be present only for WebClient.";
        }
        if (servletDependency && evidence.controllerDetected()) {
            return "Spring MVC annotations and servlet web dependency declarations were detected.";
        }
        if (servletDependency) {
            return "Servlet web dependencies were detected in the build.";
        }
        if (reactiveDependency) {
            return "Reactive WebFlux dependencies were detected in the build.";
        }
        if (webStack == WebStack.SERVLET_MVC && evidence.controllerDetected()) {
            return "Detected from Spring MVC annotations in source files.";
        }
        if (evidence.webFluxRoutingDetected()) {
            return "Spring WebFlux server configuration or routing APIs were detected in source"
                    + " files.";
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
                    FindingFactory.builder(
                                    FindingRules.SPRING_VIRTUAL_THREADS_NO_KEEP_ALIVE,
                                    FindingConfidence.MEDIUM)
                            .shortMessage(
                                    "Virtual threads are enabled and scheduled work was detected,"
                                            + " but spring.main.keep-alive=true was not found.")
                            .whyBadPractice(
                                    "Virtual threads are daemon threads. In a non-web application"
                                        + " whose only remaining work runs on them, nothing keeps"
                                        + " the JVM alive once the main thread finishes, so the"
                                        + " process can exit right after startup.")
                            .possibleImpact(
                                    "Scheduled jobs never run because the application terminates"
                                        + " immediately after the context is ready — and it exits"
                                        + " with a success code, so orchestrators may not alert.")
                            .recommendation(
                                    "Set spring.main.keep-alive=true so the application keeps"
                                            + " running for scheduled work, or keep a non-daemon"
                                            + " thread alive explicitly.")
                            .evidence(
                                    "spring.threads.virtual.enabled=true and scheduled work were"
                                            + " detected without spring.main.keep-alive.")
                            .limitations(
                                    "A web application's server thread already keeps the JVM"
                                            + " alive, in which case this finding is informational"
                                            + " only.")
                            .target("spring.main.keep-alive")
                            .location("Runtime configuration")
                            .build());
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
                                    "Upgrade to Java 17 or later. Spring Boot 3.2+ supports Java"
                                            + " 21, which also unlocks virtual threads via"
                                            + " spring.threads.virtual.enabled.")
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
            String configuredWebApplicationType,
            WebStack webStack,
            RuntimeEvidence evidence,
            List<Finding> findings) {
        boolean servletDependency =
                dependencyCoordinates.stream().anyMatch(this::isServletDependency);
        boolean reactiveDependency =
                dependencyCoordinates.stream().anyMatch(this::isReactiveDependency);
        if (servletDependency && reactiveDependency) {
            boolean webFluxServerCodeInactive =
                    webStack == WebStack.SERVLET_MVC && evidence.webFluxRoutingDetected();
            String configuredSuffix =
                    configuredWebApplicationType == null
                            ? " Spring Boot therefore selects Servlet/MVC by default."
                            : " spring.main.web-application-type="
                                    + configuredWebApplicationType
                                    + " selects "
                                    + webStack
                                    + ".";
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_MIXED_MVC_AND_WEBFLUX,
                                    FindingConfidence.HIGH)
                            .severity(
                                    webFluxServerCodeInactive
                                            ? com.robbanhoglund.springbootanalyzer.analyzer.model
                                                    .FindingSeverity.WARNING
                                            : com.robbanhoglund.springbootanalyzer.analyzer.model
                                                    .FindingSeverity.INFO)
                            .shortMessage(
                                    "Both Spring MVC/Servlet and WebFlux dependencies were"
                                            + " detected."
                                            + configuredSuffix)
                            .whyBadPractice(
                                    webFluxServerCodeInactive
                                            ? "The application resolves to Servlet/MVC while"
                                                    + " WebFlux server routing APIs are present."
                                                    + " Those routes are not served by the MVC"
                                                    + " runtime."
                                            : "Having both dependencies is valid when an MVC"
                                                    + " application uses WebClient. The classpath"
                                                    + " alone does not mean both server stacks run"
                                                    + " at the same time.")
                            .possibleImpact(
                                    webFluxServerCodeInactive
                                            ? "Reactive routes can be silently unserved because"
                                                    + " the active server stack is MVC."
                                            : "Usually none when WebFlux is client-only. Unneeded"
                                                    + " framework dependencies still make runtime"
                                                    + " intent harder to review.")
                            .recommendation(
                                    "Keep both only when the secondary dependency is intentional."
                                            + " If WebFlux is used only for WebClient in an MVC"
                                            + " service, no server-stack migration is required."
                                            + " Otherwise remove the unused starter or set"
                                            + " spring.main.web-application-type explicitly.")
                            .evidence(
                                    "Both Servlet/MVC and WebFlux dependencies were resolved for"
                                            + " this project.")
                            .limitations(
                                    "Using WebFlux solely for WebClient is a common and valid"
                                            + " pattern; review before removing dependencies.")
                            .target("web stack")
                            .location("Runtime stack")
                            .build());
        }
        if (webStack == WebStack.SERVLET_MVC
                && evidence.webFluxRoutingDetected()
                && !(servletDependency && reactiveDependency)) {
            String reactiveEvidence =
                    evidence.evidence().stream()
                            .filter(item -> item.startsWith("WebFlux routing API in "))
                            .limit(4)
                            .reduce((left, right) -> left + ", " + right)
                            .orElse("Reactive types or routing APIs were detected in source code.");
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_REACTIVE_API_IN_SERVLET_APP,
                                    FindingConfidence.HIGH)
                            .severity(
                                    com.robbanhoglund.springbootanalyzer.analyzer.model
                                            .FindingSeverity.WARNING)
                            .shortMessage(
                                    "WebFlux server routing APIs were detected, but the active"
                                            + " application type is Servlet/MVC.")
                            .whyBadPractice(
                                    "WebClient and reactive return types are valid in an MVC"
                                            + " application, but WebFlux server routes require a"
                                            + " reactive application type and are not registered by"
                                            + " the MVC runtime.")
                            .possibleImpact(
                                    "Routes written for the WebFlux server API may never become"
                                            + " reachable in the deployed application.")
                            .recommendation(
                                    "If these are server routes, select the reactive application"
                                        + " type and remove MVC, or port the routes to MVC. Keep"
                                        + " client-only WebClient/Mono usage as-is.")
                            .evidence(reactiveEvidence)
                            .limitations(
                                    "Static analysis cannot see profile-specific dependency"
                                            + " substitutions or custom parent application"
                                            + " contexts.")
                            .target("WebFlux server routes")
                            .build());
        }
        if (webStack == WebStack.NON_WEB
                && dependencyCoordinates.stream().anyMatch(this::isServletDependency)) {
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_WEB_DEPENDENCIES_IN_NON_WEB_APP,
                                    FindingConfidence.MEDIUM)
                            .shortMessage(
                                    "Web dependencies were detected, but configuration indicates a"
                                            + " non-web application type.")
                            .whyBadPractice(
                                    "spring.main.web-application-type=none prevents the embedded"
                                        + " server from starting even though the servlet stack is"
                                        + " packaged, so the web dependencies add startup cost and"
                                        + " attack surface without serving anything.")
                            .possibleImpact(
                                    "A larger artifact and classpath than needed; developers may"
                                            + " also expect endpoints to be reachable when they are"
                                            + " not.")
                            .recommendation(
                                    "Remove the web starter if the application is intentionally"
                                        + " non-web, or drop the web-application-type override if"
                                        + " the server should start.")
                            .evidence(
                                    "Servlet/web dependencies were resolved while the application"
                                            + " type resolves to non-web.")
                            .limitations(
                                    "The web dependency may be required transitively for a client"
                                            + " (e.g. RestTemplate) rather than for serving.")
                            .target("web dependencies")
                            .location("Runtime stack")
                            .build());
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
        return (normalized.contains("spring-boot-starter-web")
                        && !normalized.contains("spring-boot-starter-webflux"))
                || normalized.contains("spring-webmvc");
    }

    private boolean isReactiveDependency(String dependency) {
        String normalized = dependency.toLowerCase(Locale.ROOT);
        return normalized.contains("spring-boot-starter-webflux")
                || normalized.contains("spring-webflux");
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
            boolean webFluxRoutingDetected,
            boolean controllerDetected,
            List<String> evidence) {}
}
