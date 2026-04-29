package com.example.springbootanalyzer.analyzer.runtime;

import com.example.springbootanalyzer.analyzer.model.BuildInfo;
import com.example.springbootanalyzer.analyzer.model.DetectedClass;
import com.example.springbootanalyzer.analyzer.model.Finding;
import com.example.springbootanalyzer.analyzer.model.FindingSeverity;
import com.example.springbootanalyzer.analyzer.model.configuration.ApplicationProperty;
import com.example.springbootanalyzer.analyzer.model.configuration.ConfigurationAnalysis;
import com.example.springbootanalyzer.analyzer.model.runtime.RuntimeStackAnalysis;
import com.example.springbootanalyzer.analyzer.model.runtime.VirtualThreadAnalysis;
import com.example.springbootanalyzer.analyzer.model.runtime.WebStack;
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

    public Result analyze(
            Path repositoryRoot,
            BuildInfo buildInfo,
            ConfigurationAnalysis configurationAnalysis,
            List<DetectedClass> detectedComponents,
            List<String> mainApplicationClasses
    ) {
        RuntimeEvidence evidence = collectRuntimeEvidence(repositoryRoot, detectedComponents);
        String configuredWebApplicationType = configuredPropertyValue(
                configurationAnalysis,
                "spring.main.web-application-type"
        );

        WebStack webStack = determineWebStack(buildInfo, configuredWebApplicationType, evidence, detectedComponents);
        String webStackReason = determineWebStackReason(buildInfo, configuredWebApplicationType, evidence, webStack);
        VirtualThreadAnalysis virtualThreads = analyzeVirtualThreads(buildInfo, configurationAnalysis, evidence);

        List<Finding> findings = new ArrayList<>();
        addVirtualThreadFindings(buildInfo, virtualThreads, findings);
        addWebStackFindings(buildInfo, webStack, evidence, findings);

        String mainClass = mainApplicationClasses.isEmpty() ? null : mainApplicationClasses.get(0);
        RuntimeStackAnalysis analysis = new RuntimeStackAnalysis(
                buildInfo.springBootVersion(),
                buildInfo.springBootVersionSource(),
                buildInfo.javaVersionHint(),
                webStack,
                webStackReason,
                virtualThreads,
                mainClass
        );
        return new Result(analysis, List.copyOf(findings));
    }

    private RuntimeEvidence collectRuntimeEvidence(Path repositoryRoot, List<DetectedClass> detectedComponents) {
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
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted(Comparator.naturalOrder())
                    .toList()) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                String relativePath = repositoryRoot.relativize(file).toString().replace('\\', '/');

                if (content.contains("@Scheduled")) {
                    scheduledDetected = true;
                    evidence.add("@Scheduled in " + relativePath);
                }
                if (content.contains("@EnableScheduling")) {
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
            throw new IllegalStateException("Failed to scan source files for runtime stack analysis", exception);
        }

        boolean controllerDetected = detectedComponents.stream().anyMatch(component ->
                "REST_CONTROLLER".equalsIgnoreCase(component.componentType().name())
                        || "CONTROLLER".equalsIgnoreCase(component.componentType().name())
        );

        return new RuntimeEvidence(
                scheduledDetected,
                enableSchedulingDetected,
                directVirtualThreadUsage,
                reactiveSignalDetected,
                routerFunctionDetected || controllerDetected,
                List.copyOf(evidence)
        );
    }

    private WebStack determineWebStack(
            BuildInfo buildInfo,
            String configuredWebApplicationType,
            RuntimeEvidence evidence,
            List<DetectedClass> detectedComponents
    ) {
        if (configuredWebApplicationType != null) {
            return switch (configuredWebApplicationType.toLowerCase(Locale.ROOT)) {
                case "servlet" -> WebStack.SERVLET_MVC;
                case "reactive" -> WebStack.REACTIVE_WEBFLUX;
                case "none" -> WebStack.NON_WEB;
                default -> WebStack.UNKNOWN;
            };
        }

        boolean servletDependency = buildInfo.dependencies().stream().anyMatch(this::isServletDependency);
        boolean reactiveDependency = buildInfo.dependencies().stream().anyMatch(this::isReactiveDependency);

        if (servletDependency && reactiveDependency) {
            return WebStack.MIXED_MVC_AND_WEBFLUX;
        }
        if (servletDependency) {
            return WebStack.SERVLET_MVC;
        }
        if (reactiveDependency) {
            return WebStack.REACTIVE_WEBFLUX;
        }
        if (detectedComponents.stream().anyMatch(component ->
                component.componentType().name().contains("CONTROLLER"))) {
            return WebStack.SERVLET_MVC;
        }
        if (evidence.reactiveSignalDetected()) {
            return WebStack.REACTIVE_WEBFLUX;
        }
        return buildInfo.springBootDetected() ? WebStack.NON_WEB : WebStack.UNKNOWN;
    }

    private String determineWebStackReason(
            BuildInfo buildInfo,
            String configuredWebApplicationType,
            RuntimeEvidence evidence,
            WebStack webStack
    ) {
        if (configuredWebApplicationType != null) {
            return "Configured via spring.main.web-application-type=" + configuredWebApplicationType;
        }

        boolean servletDependency = buildInfo.dependencies().stream().anyMatch(this::isServletDependency);
        boolean reactiveDependency = buildInfo.dependencies().stream().anyMatch(this::isReactiveDependency);

        if (servletDependency && reactiveDependency) {
            return "Both Spring MVC/Servlet and WebFlux dependencies were detected. Spring MVC typically wins auto-configuration precedence.";
        }
        if (servletDependency) {
            return "Servlet web dependencies were detected in the build.";
        }
        if (reactiveDependency) {
            return "Reactive WebFlux dependencies were detected in the build.";
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
            BuildInfo buildInfo,
            ConfigurationAnalysis configurationAnalysis,
            RuntimeEvidence evidence
    ) {
        boolean enabledByProperty = "true".equalsIgnoreCase(
                configuredPropertyValue(configurationAnalysis, "spring.threads.virtual.enabled")
        );
        boolean keepAliveConfigured = "true".equalsIgnoreCase(
                configuredPropertyValue(configurationAnalysis, "spring.main.keep-alive")
        );
        boolean javaVersionCompatible = parseJavaVersion(buildInfo.javaVersionHint()) >= 21;
        boolean scheduledWorkDetected = evidence.scheduledDetected() || evidence.enableSchedulingDetected();

        List<String> evidenceLines = new ArrayList<>(evidence.evidence());
        if (enabledByProperty) {
            evidenceLines.add("spring.threads.virtual.enabled=true");
        }
        if (keepAliveConfigured) {
            evidenceLines.add("spring.main.keep-alive=true");
        }

        String summary;
        if (enabledByProperty && javaVersionCompatible) {
            summary = scheduledWorkDetected && !keepAliveConfigured
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
                List.copyOf(evidenceLines)
        );
    }

    private void addVirtualThreadFindings(
            BuildInfo buildInfo,
            VirtualThreadAnalysis analysis,
            List<Finding> findings
    ) {
        if (analysis.enabledByProperty() && !analysis.javaVersionCompatible()) {
            findings.add(new Finding(
                    FindingSeverity.WARNING,
                    "spring.threads.virtual.enabled=true was detected, but Java "
                            + (buildInfo.javaVersionHint() == null ? "unknown" : buildInfo.javaVersionHint())
                            + " may not support virtual threads.",
                    null
            ));
        }
        if (analysis.enabledByProperty()
                && analysis.scheduledWorkDetected()
                && !analysis.keepAliveConfigured()) {
            findings.add(new Finding(
                    FindingSeverity.INFO,
                    "Virtual threads are enabled and scheduled work was detected, but spring.main.keep-alive=true was not found.",
                    null
            ));
        }
    }

    private void addWebStackFindings(
            BuildInfo buildInfo,
            WebStack webStack,
            RuntimeEvidence evidence,
            List<Finding> findings
    ) {
        if (webStack == WebStack.MIXED_MVC_AND_WEBFLUX) {
            findings.add(new Finding(
                    FindingSeverity.INFO,
                    "Both Spring MVC/Servlet and WebFlux dependencies were detected. Spring MVC usually takes precedence unless spring.main.web-application-type overrides it.",
                    null
            ));
        }
        if (webStack == WebStack.SERVLET_MVC && evidence.reactiveSignalDetected()) {
            findings.add(new Finding(
                    FindingSeverity.INFO,
                    "Reactive APIs were detected in code, but the build currently looks like a Servlet/MVC application.",
                    null
            ));
        }
        if (webStack == WebStack.NON_WEB && buildInfo.dependencies().stream().anyMatch(this::isServletDependency)) {
            findings.add(new Finding(
                    FindingSeverity.INFO,
                    "Web dependencies were detected, but configuration indicates a non-web application type.",
                    null
            ));
        }
    }

    private String configuredPropertyValue(ConfigurationAnalysis configurationAnalysis, String name) {
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

    public record Result(
            RuntimeStackAnalysis runtimeStackAnalysis,
            List<Finding> findings
    ) {
    }

    private record RuntimeEvidence(
            boolean scheduledDetected,
            boolean enableSchedulingDetected,
            boolean directVirtualThreadUsage,
            boolean reactiveSignalDetected,
            boolean webSignalDetected,
            List<String> evidence
    ) {
    }
}
