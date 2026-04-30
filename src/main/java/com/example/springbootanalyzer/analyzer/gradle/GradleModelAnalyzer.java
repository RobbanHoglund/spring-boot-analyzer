package com.example.springbootanalyzer.analyzer.gradle;

import com.example.springbootanalyzer.analyzer.model.BuildInfo;
import com.example.springbootanalyzer.analyzer.model.Finding;
import com.example.springbootanalyzer.analyzer.model.FindingSeverity;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleAnalysisStatus;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleDependencyConflict;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleExecutionFailureType;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleExecutionMode;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleModelAnalysis;
import com.example.springbootanalyzer.analyzer.model.gradle.GradlePluginDeclaration;
import com.example.springbootanalyzer.analyzer.model.gradle.GradlePluginDeclarationSource;
import com.example.springbootanalyzer.analyzer.model.gradle.GradlePluginResolutionBridgeResult;
import com.example.springbootanalyzer.analyzer.model.gradle.GradlePluginResolutionFailure;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleResolvedDependencyModel;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleResolutionResult;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleSettingsPluginModel;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleSettingsPluginWorkaround;
import com.example.springbootanalyzer.analyzer.gradle.plugin.GradlePluginDeclarationScanner;
import com.example.springbootanalyzer.analyzer.gradle.plugin.GradlePluginResolutionBridge;
import com.example.springbootanalyzer.config.AnalyzerProperties;
import com.example.springbootanalyzer.git.GitRepositoryReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GradleModelAnalyzer {

    private static final Logger LOGGER = LoggerFactory.getLogger(GradleModelAnalyzer.class);

    private final GradleSafetyPolicy gradleSafetyPolicy;
    private final GradleJavaCompatibilityService gradleJavaCompatibilityService;
    private final GradleToolingApiExecutionService gradleToolingApiExecutionService;
    private final GradleExecutionService gradleExecutionService;
    private final GradleModelReportParser gradleModelReportParser;
    private final GradleSettingsPluginScanner gradleSettingsPluginScanner;
    private final GradlePluginDeclarationScanner gradlePluginDeclarationScanner;
    private final GradlePluginResolutionBridge gradlePluginResolutionBridge;

    public GradleModelAnalyzer(
            GradleSafetyPolicy gradleSafetyPolicy,
            GradleJavaCompatibilityService gradleJavaCompatibilityService,
            GradleToolingApiExecutionService gradleToolingApiExecutionService,
            GradleExecutionService gradleExecutionService,
            GradleModelReportParser gradleModelReportParser,
            GradleSettingsPluginScanner gradleSettingsPluginScanner,
            GradlePluginDeclarationScanner gradlePluginDeclarationScanner,
            GradlePluginResolutionBridge gradlePluginResolutionBridge
    ) {
        this.gradleSafetyPolicy = gradleSafetyPolicy;
        this.gradleJavaCompatibilityService = gradleJavaCompatibilityService;
        this.gradleToolingApiExecutionService = gradleToolingApiExecutionService;
        this.gradleExecutionService = gradleExecutionService;
        this.gradleModelReportParser = gradleModelReportParser;
        this.gradleSettingsPluginScanner = gradleSettingsPluginScanner;
        this.gradlePluginDeclarationScanner = gradlePluginDeclarationScanner;
        this.gradlePluginResolutionBridge = gradlePluginResolutionBridge;
    }

    public Result analyze(
            GitRepositoryReference repositoryReference,
            Path repositoryRoot,
            BuildInfo buildInfo,
            AnalyzerProperties analyzerProperties
    ) {
        GradleSafetyPolicy.Decision decision =
                gradleSafetyPolicy.decide(repositoryReference, repositoryRoot, buildInfo, analyzerProperties);
        List<Finding> findings = new ArrayList<>();
        List<GradleSettingsPluginModel> settingsPlugins = gradleSettingsPluginScanner.scan(repositoryRoot);
        List<GradlePluginDeclaration> pluginDeclarations = gradlePluginDeclarationScanner.scan(repositoryRoot);
        GradlePluginResolutionBridgeResult pluginBridgeResult = GradlePluginResolutionBridgeResult.empty();
        List<GradleSettingsPluginWorkaround> appliedWorkarounds = new ArrayList<>();
        String sanitizedBuildReason = null;
        boolean sanitizedBuildModel = false;

        LOGGER.info(
                "Gradle model analysis decision: repositoryUrl={}, analysisMode={}, buildTool={}, execute={}, executionMode={}, reason={}",
                repositoryReference.repositoryUrl(),
                repositoryReference.analysisMode(),
                buildInfo.buildTool(),
                decision.execute(),
                decision.executionMode(),
                decision.reason()
        );

        if (!decision.execute()) {
            return handleSkippedDecision(decision, buildInfo, settingsPlugins, pluginDeclarations, pluginBridgeResult, findings);
        }

        if (decision.gradleVersion() != null && shouldSkipForCompatibility(decision)) {
            String message = incompatibilityMessage(decision.gradleVersion(), decision.javaFeatureVersion());
            LOGGER.warn(
                    "Gradle model analysis skipped: diagnostic Gradle {} is not compatible with Java {}. Use Gradle 9.1.0+.",
                    decision.gradleVersion(),
                    decision.javaFeatureVersion()
            );
            findings.add(new Finding(FindingSeverity.WARNING, message, null));
            addFallbackUnavailableFinding(findings, decision);
            return new Result(
                    emptyAnalysis(
                            GradleAnalysisStatus.PARTIAL,
                            decision.executionMode().name(),
                            decision.gradleVersion(),
                            String.valueOf(decision.javaFeatureVersion()),
                            GradleExecutionFailureType.INCOMPATIBLE_JAVA_AND_GRADLE.name(),
                            message,
                            false,
                            null,
                            List.of(),
                            settingsPlugins,
                            List.of(),
                            pluginDeclarations,
                            pluginBridgeResult,
                            findings
                    ),
                    findings
            );
        }

        Path localPluginRepository = null;
        if (shouldPrefetchBeforeGradle(analyzerProperties.gradle())) {
            pluginBridgeResult = gradlePluginResolutionBridge.prefetch(repositoryRoot, pluginDeclarations, analyzerProperties.gradle());
            findings.addAll(pluginBridgeResult.findings());
            localPluginRepository = pluginBridgeResult.localMavenRepository() == null
                    ? null
                    : Path.of(pluginBridgeResult.localMavenRepository());
        }

        GradleExecutionResult executionResult = executePrimary(decision, repositoryRoot, analyzerProperties, localPluginRepository);
        int pluginBridgeRetries = 0;
        while (shouldRetryWithPluginBridge(executionResult, analyzerProperties.gradle(), pluginBridgeRetries, pluginBridgeResult)) {
            GradlePluginDeclaration missingPlugin = declarationForFailure(executionResult.pluginResolutionFailure());
            if (missingPlugin == null) {
                break;
            }
            findings.add(new Finding(
                    FindingSeverity.WARNING,
                    "Gradle plugin resolution failed for %s:%s. The analyzer will try resolving declared plugins into a local analyzer plugin cache and retry."
                            .formatted(missingPlugin.pluginId(), missingPlugin.version()),
                    sourceLocation(missingPlugin.sourceFile(), missingPlugin.line())
            ));
            GradlePluginResolutionBridgeResult retriedBridgeResult = gradlePluginResolutionBridge.prefetch(
                    repositoryRoot,
                    List.of(missingPlugin),
                    analyzerProperties.gradle()
            );
            pluginBridgeResult = mergeBridgeResults(pluginBridgeResult, retriedBridgeResult);
            findings.addAll(retriedBridgeResult.findings());
            localPluginRepository = pluginBridgeResult.localMavenRepository() == null
                    ? localPluginRepository
                    : Path.of(pluginBridgeResult.localMavenRepository());
            pluginBridgeRetries++;
            executionResult = executePrimary(decision, repositoryRoot, analyzerProperties, localPluginRepository);
        }
        if (shouldAttemptFallback(decision, executionResult)) {
            LOGGER.warn(
                    "Tooling API Gradle execution did not produce a usable report; falling back to external Gradle process: executionMode={}, failureType={}, exitCode={}",
                    executionResult.executionMode(),
                    executionResult.failureType(),
                    executionResult.exitCode()
            );
            executionResult = gradleExecutionService.execute(
                    repositoryRoot,
                    GradleExecutionMode.SYSTEM_GRADLE,
                    null,
                    null,
                    decision.javaFeatureVersion(),
                    analyzerProperties.gradle(),
                    localPluginRepository
            );
        } else if (shouldExplainMissingFallback(decision, executionResult)) {
            addFallbackUnavailableFinding(findings, decision);
        }

        LOGGER.info(
                "Gradle execution finished: successful={}, timedOut={}, exitCode={}, reportFile={}",
                executionResult.successful(),
                executionResult.timedOut(),
                executionResult.exitCode(),
                executionResult.reportFile()
        );

        if (executionResult.timedOut()) {
            findings.add(new Finding(FindingSeverity.WARNING, "Gradle analysis timed out; partial static analysis is shown.", null));
            return new Result(
                    emptyAnalysis(
                            GradleAnalysisStatus.TIMED_OUT,
                            executionResult.executionMode(),
                            executionResult.gradleVersion(),
                            executionResult.javaVersion(),
                            executionResult.failureType().name(),
                            executionResult.errorMessage(),
                            sanitizedBuildModel,
                            sanitizedBuildReason,
                            appliedWorkarounds,
                            settingsPlugins,
                            resolutionFailures(executionResult),
                            pluginDeclarations,
                            pluginBridgeResult,
                            findings
                    ),
                    findings
            );
        }

        GradleModelAnalysis parsed = gradleModelReportParser.parse(executionResult.reportFile(), executionResult.executionMode());
        LOGGER.info(
                "Parsed Gradle model report: status={}, projects={}, resolvedDependencies={}, conflicts={}",
                parsed.status(),
                parsed.projects().size(),
                parsed.resolvedDependencies().size(),
                parsed.dependencyConflicts().size()
        );
        findings.addAll(parsed.findings());

        if (!executionResult.successful()) {
            logKnownFailure(executionResult);
            addFailureFinding(findings, executionResult);
            maybeLogRetainedWorkspace(analyzerProperties, repositoryRoot, executionResult);
            return new Result(
                    withStatus(
                            parsed,
                            executionResult,
                            GradleAnalysisStatus.PARTIAL,
                            sanitizedBuildModel,
                            sanitizedBuildReason,
                            appliedWorkarounds,
                            settingsPlugins,
                            resolutionFailures(executionResult),
                            pluginDeclarations,
                            pluginBridgeResult,
                            findings
                    ),
                    findings
            );
        }

        addModelFindings(parsed, buildInfo, findings);
        return new Result(
                withStatus(
                        parsed,
                        executionResult,
                        sanitizedBuildModel ? GradleAnalysisStatus.SUCCESS_WITH_WORKAROUND : GradleAnalysisStatus.SUCCESS,
                        sanitizedBuildModel,
                        sanitizedBuildReason,
                        appliedWorkarounds,
                        settingsPlugins,
                        resolutionFailures(executionResult),
                        pluginDeclarations,
                        pluginBridgeResult,
                        findings
                ),
                findings
        );
    }

    private Result handleSkippedDecision(
            GradleSafetyPolicy.Decision decision,
            BuildInfo buildInfo,
            List<GradleSettingsPluginModel> settingsPlugins,
            List<GradlePluginDeclaration> pluginDeclarations,
            GradlePluginResolutionBridgeResult pluginBridgeResult,
            List<Finding> findings
    ) {
        if ("STATIC_ONLY".equals(decision.reason()) && buildInfo.buildTool().name().equals("GRADLE")) {
            LOGGER.info("Skipping Gradle model analysis because analysisMode=STATIC_ONLY");
            findings.add(new Finding(
                    FindingSeverity.INFO,
                    "Build-aware analysis disabled; dependency versions are inferred statically.",
                    null
            ));
            return new Result(emptyAnalysis(
                    GradleAnalysisStatus.NOT_REQUESTED,
                    decision.executionMode().name(),
                    decision.gradleVersion(),
                    String.valueOf(decision.javaFeatureVersion()),
                    null,
                    null,
                    false,
                    null,
                    List.of(),
                    settingsPlugins,
                    List.of(),
                    pluginDeclarations,
                    pluginBridgeResult,
                    findings
            ), findings);
        }
        if ("DISABLED".equals(decision.reason())) {
            LOGGER.info("Gradle model analysis requested but analyzer.gradle.enabled=false");
            findings.add(new Finding(
                    FindingSeverity.INFO,
                    "Gradle model analysis was requested, but analyzer.gradle.enabled=false.",
                    null
            ));
            return new Result(emptyAnalysis(
                    GradleAnalysisStatus.DISABLED,
                    decision.executionMode().name(),
                    decision.gradleVersion(),
                    String.valueOf(decision.javaFeatureVersion()),
                    GradleExecutionFailureType.NONE.name(),
                    "Gradle model analysis was requested, but analyzer.gradle.enabled=false.",
                    false,
                    null,
                    List.of(),
                    settingsPlugins,
                    List.of(),
                    pluginDeclarations,
                    pluginBridgeResult,
                    findings
            ), findings);
        }
        if ("WRAPPER_NOT_FOUND".equals(decision.reason())) {
            findings.add(new Finding(
                    FindingSeverity.WARNING,
                    "Gradle wrapper execution was requested, but no wrapper script was found.",
                    null
            ));
            return new Result(emptyAnalysis(
                    GradleAnalysisStatus.FAILED,
                    decision.executionMode().name(),
                    decision.gradleVersion(),
                    String.valueOf(decision.javaFeatureVersion()),
                    GradleExecutionFailureType.EXECUTABLE_NOT_FOUND.name(),
                    "Gradle wrapper execution was requested, but no wrapper script was found.",
                    false,
                    null,
                    List.of(),
                    settingsPlugins,
                    List.of(),
                    pluginDeclarations,
                    pluginBridgeResult,
                    findings
            ), findings);
        }
        return new Result(emptyAnalysis(
                GradleAnalysisStatus.SKIPPED,
                decision.executionMode().name(),
                decision.gradleVersion(),
                String.valueOf(decision.javaFeatureVersion()),
                null,
                null,
                false,
                null,
                List.of(),
                settingsPlugins,
                List.of(),
                pluginDeclarations,
                pluginBridgeResult,
                findings
        ), findings);
    }

    private GradleExecutionResult executePrimary(
            GradleSafetyPolicy.Decision decision,
            Path repositoryRoot,
            AnalyzerProperties analyzerProperties,
            Path localPluginRepository
    ) {
        if (decision.executionMode() == GradleExecutionMode.SYSTEM_GRADLE) {
            return gradleExecutionService.execute(
                    repositoryRoot,
                    GradleExecutionMode.SYSTEM_GRADLE,
                    null,
                    decision.gradleVersion(),
                    decision.javaFeatureVersion(),
                    analyzerProperties.gradle(),
                    localPluginRepository
            );
        }
        return gradleToolingApiExecutionService.execute(
                repositoryRoot,
                decision.executionMode(),
                decision.wrapperScript(),
                decision.gradleVersion(),
                decision.javaFeatureVersion(),
                analyzerProperties.gradle(),
                localPluginRepository
        );
    }

    private boolean shouldSkipForCompatibility(GradleSafetyPolicy.Decision decision) {
        return !gradleJavaCompatibilityService
                .evaluateCompatibility(decision.javaFeatureVersion(), decision.gradleVersion())
                .compatible();
    }

    private boolean shouldAttemptFallback(GradleSafetyPolicy.Decision decision, GradleExecutionResult executionResult) {
        return decision.allowSystemGradleFallback()
                && decision.executionMode() != GradleExecutionMode.SYSTEM_GRADLE
                && !executionResult.timedOut()
                && !executionResult.successful()
                && (executionResult.reportFile() == null || Files.notExists(executionResult.reportFile()))
                && (executionResult.failureType() == GradleExecutionFailureType.TOOLING_API_TRANSPORT_FAILED
                || executionResult.failureType() == GradleExecutionFailureType.REPORT_NOT_CREATED
                || executionResult.failureType() == GradleExecutionFailureType.EXECUTABLE_NOT_FOUND);
    }

    private boolean shouldExplainMissingFallback(GradleSafetyPolicy.Decision decision, GradleExecutionResult executionResult) {
        return !executionResult.successful()
                && !executionResult.timedOut()
                && decision.executionMode() != GradleExecutionMode.SYSTEM_GRADLE
                && !decision.allowSystemGradleFallback()
                && (executionResult.failureType() == GradleExecutionFailureType.TOOLING_API_TRANSPORT_FAILED
                || executionResult.failureType() == GradleExecutionFailureType.REPORT_NOT_CREATED);
    }

    private void addFallbackUnavailableFinding(List<Finding> findings, GradleSafetyPolicy.Decision decision) {
        if (decision.allowSystemGradleFallback()) {
            return;
        }
        findings.add(new Finding(
                FindingSeverity.INFO,
                "External Gradle fallback was skipped because no Gradle executable was configured or found on PATH.",
                null
        ));
    }

    private String incompatibilityMessage(String gradleVersion, int javaFeatureVersion) {
        return "Gradle model analysis was skipped because diagnostic Gradle %s cannot run on Java %d. Use Gradle 9.1.0 or newer, or configure analyzer.gradle.java-home."
                .formatted(gradleVersion, javaFeatureVersion);
    }

    private String failureMessage(GradleExecutionResult executionResult) {
        if (executionResult.errorMessage() != null && !executionResult.errorMessage().isBlank()) {
            return executionResult.errorMessage();
        }
        return "Gradle analysis failed; partial static analysis is shown.";
    }

    private GradleAnalysisStatus effectiveStatus(
            GradleModelAnalysis parsed,
            GradleExecutionResult executionResult,
            GradleAnalysisStatus fallbackStatus
    ) {
        if (fallbackStatus != GradleAnalysisStatus.SUCCESS && fallbackStatus != GradleAnalysisStatus.SUCCESS_WITH_WORKAROUND) {
            return fallbackStatus;
        }
        if (hasResolutionProblems(parsed)) {
            return GradleAnalysisStatus.PARTIAL;
        }
        return fallbackStatus;
    }

    private boolean hasResolutionProblems(GradleModelAnalysis parsed) {
        List<GradleResolutionResult> dependencyBearingResults = dependencyBearingResolutionResults(parsed);
        boolean hasFailedResolution = dependencyBearingResults.stream()
                .anyMatch(result -> result.attempted() && !result.successful());
        if (hasFailedResolution) {
            return true;
        }
        boolean attemptedResolutions = dependencyBearingResults.stream()
                .anyMatch(GradleResolutionResult::attempted);
        boolean hasResolvedModules = dependencyBearingResults.stream()
                .anyMatch(result -> result.successful() && result.resolvedDependencyCount() > 0);
        return attemptedResolutions
                && !hasResolvedModules
                && !parsed.declaredDependencies().isEmpty();
    }

    private String normalizedFailureType(GradleModelAnalysis parsed, GradleExecutionResult executionResult) {
        if (executionResult.failureType() == null || executionResult.failureType() == GradleExecutionFailureType.NONE) {
            if (hasResolutionProblems(parsed)) {
                return GradleExecutionFailureType.DEPENDENCY_RESOLUTION_FAILED.name();
            }
            return parsed.failureType();
        }
        return executionResult.failureType().name();
    }

    private String normalizedErrorMessage(GradleModelAnalysis parsed, GradleExecutionResult executionResult) {
        if (executionResult.errorMessage() == null || executionResult.errorMessage().isBlank()) {
            if (hasResolutionProblems(parsed)) {
                List<GradleResolutionResult> dependencyBearingResults = dependencyBearingResolutionResults(parsed);
                long failedCount = dependencyBearingResults.stream()
                        .filter(result -> result.attempted() && !result.successful())
                        .count();
                long successfulCount = dependencyBearingResults.stream()
                        .filter(result -> result.attempted() && result.successful() && result.resolvedDependencyCount() > 0)
                        .count();
                return "Dependency graph resolution failed for dependency-bearing Gradle configurations (%d resolved, %d failed)."
                        .formatted(successfulCount, failedCount);
            }
            return parsed.errorMessage();
        }
        return executionResult.errorMessage();
    }

    private GradleModelAnalysis withStatus(
            GradleModelAnalysis parsed,
            GradleExecutionResult executionResult,
            GradleAnalysisStatus status,
            boolean sanitizedBuildModel,
            String sanitizedBuildReason,
            List<GradleSettingsPluginWorkaround> appliedWorkarounds,
            List<GradleSettingsPluginModel> settingsPlugins,
            List<GradlePluginResolutionFailure> pluginResolutionFailures,
            List<GradlePluginDeclaration> pluginDeclarations,
            GradlePluginResolutionBridgeResult pluginBridgeResult,
            List<Finding> findings
    ) {
        return new GradleModelAnalysis(
                effectiveStatus(parsed, executionResult, status),
                parsed.gradleVersion() != null ? parsed.gradleVersion() : executionResult.gradleVersion(),
                executionResult.javaVersion(),
                parsed.executionMode(),
                parsed.reportFile(),
                normalizedFailureType(parsed, executionResult),
                normalizedErrorMessage(parsed, executionResult),
                sanitizedBuildModel,
                sanitizedBuildReason,
                List.copyOf(appliedWorkarounds),
                List.copyOf(settingsPlugins),
                List.copyOf(pluginResolutionFailures),
                List.copyOf(pluginDeclarations),
                pluginBridgeResult,
                pluginBridgeResult != null && !pluginBridgeResult.resolvedPlugins().isEmpty(),
                bridgeStatus(pluginBridgeResult),
                parsed.projects(),
                parsed.plugins(),
                parsed.repositories(),
                parsed.configurations(),
                parsed.declaredDependencies(),
                parsed.resolvedDependencies(),
                parsed.resolutionResults(),
                parsed.dependencyConflicts(),
                parsed.sourceSets(),
                parsed.tasks(),
                parsed.javaToolchains(),
                List.copyOf(findings)
        );
    }

    private GradleModelAnalysis emptyAnalysis(
            GradleAnalysisStatus status,
            String executionMode,
            String gradleVersion,
            String javaVersion,
            String failureType,
            String errorMessage,
            boolean sanitizedBuildModel,
            String sanitizedBuildReason,
            List<GradleSettingsPluginWorkaround> appliedWorkarounds,
            List<GradleSettingsPluginModel> settingsPlugins,
            List<GradlePluginResolutionFailure> pluginResolutionFailures,
            List<GradlePluginDeclaration> pluginDeclarations,
            GradlePluginResolutionBridgeResult pluginBridgeResult,
            List<Finding> findings
    ) {
        return new GradleModelAnalysis(
                status,
                gradleVersion,
                javaVersion,
                executionMode,
                null,
                failureType,
                errorMessage,
                sanitizedBuildModel,
                sanitizedBuildReason,
                List.copyOf(appliedWorkarounds),
                List.copyOf(settingsPlugins),
                List.copyOf(pluginResolutionFailures),
                List.copyOf(pluginDeclarations),
                pluginBridgeResult,
                pluginBridgeResult != null && !pluginBridgeResult.resolvedPlugins().isEmpty(),
                bridgeStatus(pluginBridgeResult),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.copyOf(findings)
        );
    }

    private List<GradlePluginResolutionFailure> resolutionFailures(GradleExecutionResult executionResult) {
        return executionResult.pluginResolutionFailure() == null
                ? List.of()
                : List.of(executionResult.pluginResolutionFailure());
    }

    private boolean shouldRetryWithPluginBridge(
            GradleExecutionResult executionResult,
            AnalyzerProperties.GradleProperties properties,
            int retries,
            GradlePluginResolutionBridgeResult pluginBridgeResult
    ) {
        if (properties == null
                || properties.pluginResolutionBridge() == null
                || !properties.pluginResolutionBridge().enabled()
                || !properties.pluginResolutionBridge().retryOnPluginResolutionFailure()) {
            return false;
        }
        if (retries >= properties.pluginResolutionBridge().maxRetries()) {
            return false;
        }
        if ((executionResult.failureType() != GradleExecutionFailureType.SETTINGS_PLUGIN_RESOLUTION_FAILED
                && executionResult.failureType() != GradleExecutionFailureType.PLUGIN_RESOLUTION_FAILED)
                || executionResult.pluginResolutionFailure() == null) {
            return false;
        }
        String pluginKey = executionResult.pluginResolutionFailure().pluginId() + ":" + executionResult.pluginResolutionFailure().version();
        return pluginBridgeResult.resolvedPlugins().stream()
                .noneMatch(plugin -> pluginKey.equals(plugin.pluginId() + ":" + plugin.version()));
    }

    private void logKnownFailure(GradleExecutionResult executionResult) {
        if ((executionResult.failureType() == GradleExecutionFailureType.SETTINGS_PLUGIN_RESOLUTION_FAILED
                || executionResult.failureType() == GradleExecutionFailureType.PLUGIN_RESOLUTION_FAILED)
                && executionResult.pluginResolutionFailure() != null) {
            GradlePluginResolutionFailure failure = executionResult.pluginResolutionFailure();
            LOGGER.warn(
                    "Gradle model analysis failed during plugin resolution: pluginId={}, version={}, source={}:{}, searchedRepositories={}",
                    failure.pluginId(),
                    failure.version(),
                    failure.settingsFile(),
                    failure.line(),
                    failure.searchedRepositories()
            );
            return;
        }
        if (executionResult.failureType() == GradleExecutionFailureType.INIT_SCRIPT_COMPILATION_FAILED) {
            LOGGER.warn("Gradle model analysis failed because the analyzer generated an invalid init script");
            return;
        }
        LOGGER.warn("Gradle model analysis failed; returning partial result");
    }

    private void addFailureFinding(List<Finding> findings, GradleExecutionResult executionResult) {
        if (executionResult.failureType() == GradleExecutionFailureType.SETTINGS_PLUGIN_RESOLUTION_FAILED
                && executionResult.pluginResolutionFailure() != null) {
            GradlePluginResolutionFailure failure = executionResult.pluginResolutionFailure();
            findings.add(new Finding(
                    FindingSeverity.WARNING,
                    "Gradle model analysis could not run because a settings plugin could not be resolved: %s:%s. The analyzer can bridge declared Gradle plugins through a local analyzer plugin cache, but this plugin still did not resolve."
                            .formatted(failure.pluginId(), failure.version()),
                    "%s:%s".formatted(failure.settingsFile(), failure.line())
            ));
            return;
        }
        if (executionResult.failureType() == GradleExecutionFailureType.PLUGIN_RESOLUTION_FAILED
                && executionResult.pluginResolutionFailure() != null) {
            GradlePluginResolutionFailure failure = executionResult.pluginResolutionFailure();
            findings.add(new Finding(
                    FindingSeverity.WARNING,
                    "Gradle model analysis could not resolve plugin %s:%s even after local plugin cache bridging."
                            .formatted(failure.pluginId(), failure.version()),
                    sourceLocation(failure.settingsFile(), failure.line())
            ));
            return;
        }
        if (executionResult.failureType() == GradleExecutionFailureType.INIT_SCRIPT_COMPILATION_FAILED) {
            findings.add(new Finding(
                    FindingSeverity.ERROR,
                    helperScopeFindingMessage(executionResult.errorMessage()),
                    null
            ));
            return;
        }
        findings.add(new Finding(FindingSeverity.WARNING, failureMessage(executionResult), null));
    }

    private void maybeLogRetainedWorkspace(
            AnalyzerProperties analyzerProperties,
            Path repositoryRoot,
            GradleExecutionResult executionResult
    ) {
        if (!analyzerProperties.workspaceKeepOnGradleFailure()) {
            return;
        }
        LOGGER.info(
                "Workspace retained because Gradle model analysis failed: path={}, initScript={}, reportFile={}",
                repositoryRoot.getParent(),
                executionResult.initScriptFile(),
                executionResult.reportFile()
        );
        if (executionResult.initScriptFile() != null && executionResult.reportFile() != null) {
            LOGGER.info(
                    "Gradle diagnostic reproduction hint: {}",
                    GradleExecutionSupport.externalCommandHint(
                            executionResult.initScriptFile(),
                            executionResult.reportFile(),
                            analyzerProperties.gradle()
                    )
            );
        }
    }

    private void addModelFindings(GradleModelAnalysis parsed, BuildInfo buildInfo, List<Finding> findings) {
        if (!parsed.dependencyConflicts().isEmpty()) {
            for (GradleDependencyConflict conflict : parsed.dependencyConflicts()) {
                findings.add(new Finding(
                        FindingSeverity.WARNING,
                        "Dependency conflict detected for " + join(conflict.group(), conflict.artifact()) + ".",
                        conflict.configuration()
                ));
            }
        }

        if (!parsed.resolutionResults().isEmpty()) {
            List<GradleResolutionResult> dependencyBearingResults = dependencyBearingResolutionResults(parsed);
            long successfulConfigurations = dependencyBearingResults.stream()
                    .filter(result -> result.successful() && result.resolvedDependencyCount() > 0)
                    .count();
            if (!dependencyBearingResults.isEmpty() && successfulConfigurations == 0) {
                findings.add(new Finding(
                        FindingSeverity.WARNING,
                        "Gradle executed successfully, but no dependency-bearing configuration resolved a dependency graph.",
                        "Gradle model"
                ));
            }
            for (GradleResolutionResult result : dependencyBearingResults) {
                if (result.attempted() && !result.successful()) {
                    findings.add(new Finding(
                            FindingSeverity.WARNING,
                            "Dependency resolution failed for %s%s"
                                    .formatted(
                                            result.configuration(),
                                            result.errorMessage() == null || result.errorMessage().isBlank()
                                                    ? "."
                                                    : ": " + result.errorMessage()
                                    ),
                            result.projectPath()
                    ));
                }
            }
        }

        List<String> coordinates = parsed.resolvedDependencies().stream()
                .map(this::coordinate)
                .toList();
        if (containsDependency(coordinates, "org.projectlombok", "lombok")) {
            findings.add(new Finding(FindingSeverity.INFO, "Lombok detected; generated methods may not be visible to the static parser.", null));
        }
        if (containsDependency(coordinates, "org.mapstruct", "mapstruct-processor")) {
            findings.add(new Finding(FindingSeverity.INFO, "MapStruct detected; generated mappers may not be visible to the static parser.", null));
        }
        if (parsed.configurations().stream().anyMatch(configuration -> configuration.name().toLowerCase(Locale.ROOT).contains("annotationprocessor") && configuration.allDependencyCount() > 0)) {
            findings.add(new Finding(FindingSeverity.INFO, "Annotation processors are configured.", null));
        }
        if (containsDependency(coordinates, "org.springframework.boot", "spring-boot-starter-web")
                && containsDependency(coordinates, "org.springframework.boot", "spring-boot-starter-webflux")) {
            findings.add(new Finding(FindingSeverity.WARNING, "Spring Boot starter web and webflux are both resolved.", null));
        }

        String resolvedBootVersion = parsed.resolvedDependencies().stream()
                .filter(dependency -> "org.springframework.boot".equals(dependency.group()) && "spring-boot".equals(dependency.artifact()))
                .map(GradleResolvedDependencyModel::version)
                .filter(version -> version != null && !version.isBlank())
                .findFirst()
                .orElse(null);
        if (resolvedBootVersion != null
                && buildInfo.springBootVersion() != null
                && !resolvedBootVersion.equals(buildInfo.springBootVersion())) {
            findings.add(new Finding(
                    FindingSeverity.WARNING,
                    "Resolved Spring Boot version differs from declared plugin version.",
                    buildInfo.springBootVersion() + " -> " + resolvedBootVersion
            ));
        }
    }

    private boolean containsDependency(List<String> coordinates, String group, String artifact) {
        return coordinates.stream().anyMatch(coordinate -> coordinate.equals(group + ":" + artifact));
    }

    private String coordinate(GradleResolvedDependencyModel dependency) {
        return join(dependency.group(), dependency.artifact());
    }

    private String join(String group, String artifact) {
        return (group == null ? "" : group) + ":" + (artifact == null ? "" : artifact);
    }

    private List<GradleResolutionResult> dependencyBearingResolutionResults(GradleModelAnalysis parsed) {
        return parsed.resolutionResults().stream()
                .filter(result -> configurationHasDependencies(parsed, result.projectPath(), result.configuration()))
                .toList();
    }

    private boolean configurationHasDependencies(GradleModelAnalysis parsed, String projectPath, String configurationName) {
        return parsed.configurations().stream()
                .filter(configuration -> java.util.Objects.equals(configuration.projectPath(), projectPath))
                .filter(configuration -> java.util.Objects.equals(configuration.name(), configurationName))
                .findFirst()
                .map(configuration -> configuration.allDependencyCount() > 0)
                .orElse(false);
    }

    private boolean shouldPrefetchBeforeGradle(AnalyzerProperties.GradleProperties properties) {
        return properties != null
                && properties.pluginResolutionBridge() != null
                && properties.pluginResolutionBridge().enabled()
                && properties.pluginResolutionBridge().prefetchBeforeGradle();
    }

    private GradlePluginDeclaration declarationForFailure(GradlePluginResolutionFailure failure) {
        if (failure == null || failure.pluginId() == null || failure.version() == null) {
            return null;
        }
        return new GradlePluginDeclaration(
                failure.pluginId(),
                failure.version(),
                failure.settingsFile(),
                failure.line(),
                GradlePluginDeclarationSource.UNKNOWN,
                false
        );
    }

    private GradlePluginResolutionBridgeResult mergeBridgeResults(
            GradlePluginResolutionBridgeResult left,
            GradlePluginResolutionBridgeResult right
    ) {
        LinkedHashMap<String, com.example.springbootanalyzer.analyzer.model.gradle.ResolvedGradlePlugin> resolved = new LinkedHashMap<>();
        for (var plugin : left.resolvedPlugins()) {
            resolved.put(plugin.pluginId() + ":" + plugin.version(), plugin);
        }
        for (var plugin : right.resolvedPlugins()) {
            resolved.put(plugin.pluginId() + ":" + plugin.version(), plugin);
        }
        LinkedHashMap<String, com.example.springbootanalyzer.analyzer.model.gradle.GradlePluginBridgeFailure> failures = new LinkedHashMap<>();
        for (var failure : left.failures()) {
            failures.put(failure.pluginId() + ":" + failure.version(), failure);
        }
        for (var failure : right.failures()) {
            failures.put(failure.pluginId() + ":" + failure.version(), failure);
        }
        List<Finding> findings = new ArrayList<>();
        findings.addAll(left.findings());
        findings.addAll(right.findings());
        return new GradlePluginResolutionBridgeResult(
                left.successful() && right.successful(),
                right.localMavenRepository() != null ? right.localMavenRepository() : left.localMavenRepository(),
                List.copyOf(resolved.values()),
                List.copyOf(failures.values()),
                List.copyOf(findings)
        );
    }

    private String bridgeStatus(GradlePluginResolutionBridgeResult pluginBridgeResult) {
        if (pluginBridgeResult == null || (pluginBridgeResult.resolvedPlugins().isEmpty() && pluginBridgeResult.failures().isEmpty())) {
            return null;
        }
        if (!pluginBridgeResult.resolvedPlugins().isEmpty() && pluginBridgeResult.failures().isEmpty()) {
            return "LOCAL_PLUGIN_CACHE_USED";
        }
        if (!pluginBridgeResult.resolvedPlugins().isEmpty()) {
            return "LOCAL_PLUGIN_CACHE_PARTIAL";
        }
        return "LOCAL_PLUGIN_CACHE_FAILED";
    }

    private String sourceLocation(String sourceFile, Integer line) {
        if (sourceFile == null) {
            return null;
        }
        return line == null ? sourceFile : sourceFile + ":" + line;
    }

    private String helperScopeFindingMessage(String errorMessage) {
        String normalized = errorMessage == null ? "" : errorMessage.toLowerCase(Locale.ROOT);
        if (normalized.contains("could not find method sanitizevalue()")
                || normalized.contains("could not find method sbasanitizevalue()")) {
            return "Gradle model analysis failed because the generated init script called helper method sanitizeValue from a Gradle task closure. This is an analyzer init-script scoping bug.";
        }
        return "Gradle model analysis failed because the analyzer generated an invalid Gradle init script. This is likely a path escaping or helper scoping issue.";
    }

    public record Result(
            GradleModelAnalysis gradleModelAnalysis,
            List<Finding> findings
    ) {
    }
}
