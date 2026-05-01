package com.example.springbootanalyzer.analyzer.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.springbootanalyzer.api.dto.AnalysisMode;
import com.example.springbootanalyzer.analyzer.model.BuildInfo;
import com.example.springbootanalyzer.analyzer.model.BuildTool;
import com.example.springbootanalyzer.analyzer.model.FindingSeverity;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleAnalysisStatus;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleConfigurationModel;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleDependencyModel;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleExecutionFailureType;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleExecutionMode;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleModelAnalysis;
import com.example.springbootanalyzer.analyzer.model.gradle.GradlePluginResolutionFailure;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleResolutionResult;
import com.example.springbootanalyzer.analyzer.gradle.plugin.GradleCorePluginDetector;
import com.example.springbootanalyzer.analyzer.gradle.plugin.GradlePluginDeclarationScanner;
import com.example.springbootanalyzer.analyzer.gradle.plugin.GradlePluginResolutionBridge;
import com.example.springbootanalyzer.analyzer.gradle.plugin.GradleVersionCatalogPluginScanner;
import com.example.springbootanalyzer.config.AnalyzerProperties;
import com.example.springbootanalyzer.git.GitRepositoryReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GradleModelAnalyzerTest {

    @TempDir
    Path tempDir;

    @Test
    void staticOnlyModeNeverInvokesGradle() {
        RecordingToolingApiExecutionService toolingExecutionService = new RecordingToolingApiExecutionService();
        RecordingExecutionService executionService = new RecordingExecutionService();
        GradleModelAnalyzer analyzer = analyzer(toolingExecutionService, executionService);

        var result = analyzer.analyze(
                new GitRepositoryReference("https://github.com/example/demo.git", "main", null, AnalysisMode.STATIC_ONLY),
                tempDir,
                gradleBuildInfo(),
                gradleProperties(true, GradleExecutionMode.TOOLING_API, "9.5.0", false, null, null)
        );

        assertThat(toolingExecutionService.invoked).isFalse();
        assertThat(executionService.invoked).isFalse();
        assertThat(result.gradleModelAnalysis().status()).isEqualTo(GradleAnalysisStatus.NOT_REQUESTED);
        assertThat(result.findings()).extracting(finding -> finding.message())
                .noneMatch(message -> message != null && message.contains("Build-aware analysis disabled"));
    }

    @Test
    void incompatibleDiagnosticGradleReturnsPartialResultWithFinding() {
        RecordingToolingApiExecutionService toolingExecutionService = new RecordingToolingApiExecutionService();
        RecordingExecutionService executionService = new RecordingExecutionService();
        GradleModelAnalyzer analyzer = analyzer(toolingExecutionService, executionService);

        var result = analyzer.analyze(
                new GitRepositoryReference("https://github.com/example/demo.git", "main", null, AnalysisMode.STATIC_PLUS_GRADLE_MODEL),
                tempDir,
                gradleBuildInfo(),
                gradleProperties(true, GradleExecutionMode.TOOLING_API, "8.14.3", false, null, null)
        );

        assertThat(toolingExecutionService.invoked).isFalse();
        assertThat(executionService.invoked).isFalse();
        assertThat(result.gradleModelAnalysis().status()).isEqualTo(GradleAnalysisStatus.PARTIAL);
        assertThat(result.gradleModelAnalysis().failureType()).isEqualTo(GradleExecutionFailureType.INCOMPATIBLE_JAVA_AND_GRADLE.name());
        assertThat(result.findings()).extracting(finding -> finding.message())
                .anyMatch(message -> message.contains("cannot run on Java 25"));
    }

    @Test
    void toolingApiFailureDueToUnsupportedClassFileMajorVersionIsClassifiedAsIncompatible() {
        RecordingToolingApiExecutionService toolingExecutionService = new RecordingToolingApiExecutionService();
        toolingExecutionService.nextResult = new GradleExecutionResult(
                false,
                false,
                -1,
                null,
                null,
                "BUG! exception in phase 'semantic analysis' in source unit '_BuildScript_' Unsupported class file major version 69",
                GradleExecutionMode.TOOLING_API.name(),
                "8.14.3",
                "25",
                GradleExecutionFailureType.INCOMPATIBLE_JAVA_AND_GRADLE,
                "Diagnostic Gradle 8.14.3 cannot run on Java 25.",
                null
        );
        RecordingExecutionService executionService = new RecordingExecutionService();
        GradleModelAnalyzer analyzer = analyzer(toolingExecutionService, executionService);

        var result = analyzer.analyze(
                new GitRepositoryReference("https://github.com/example/demo.git", "main", null, AnalysisMode.STATIC_PLUS_GRADLE_MODEL),
                tempDir,
                gradleBuildInfo(),
                gradleProperties(true, GradleExecutionMode.TOOLING_API, "9.5.0", false, null, null)
        );

        assertThat(result.gradleModelAnalysis().failureType()).isEqualTo(GradleExecutionFailureType.INCOMPATIBLE_JAVA_AND_GRADLE.name());
    }

    @Test
    void systemFallbackDisabledDoesNotAttemptExternalGradle() {
        RecordingToolingApiExecutionService toolingExecutionService = new RecordingToolingApiExecutionService();
        toolingExecutionService.nextResult = new GradleExecutionResult(
                false,
                false,
                -1,
                null,
                null,
                "Tooling API failed",
                GradleExecutionMode.TOOLING_API.name(),
                "9.5.0",
                "25",
                GradleExecutionFailureType.TOOLING_API_TRANSPORT_FAILED,
                "Tooling API transport failed.",
                null
        );
        RecordingExecutionService executionService = new RecordingExecutionService();
        GradleModelAnalyzer analyzer = analyzer(toolingExecutionService, executionService);

        var result = analyzer.analyze(
                new GitRepositoryReference("https://github.com/example/demo.git", "main", null, AnalysisMode.STATIC_PLUS_GRADLE_MODEL),
                tempDir,
                gradleBuildInfo(),
                gradleProperties(true, GradleExecutionMode.TOOLING_API, "9.5.0", false, null, null)
        );

        assertThat(executionService.invoked).isFalse();
        assertThat(result.findings()).extracting(finding -> finding.message())
                .anyMatch(message -> message.contains("External Gradle fallback was skipped"));
    }

    @Test
    void settingsPluginResolutionFailureDoesNotTriggerExternalGradleFallback() {
        RecordingToolingApiExecutionService toolingExecutionService = new RecordingToolingApiExecutionService();
        toolingExecutionService.nextResult = new GradleExecutionResult(
                false,
                false,
                -1,
                null,
                null,
                """
                FAILURE: Build failed with an exception.

                * Where:
                Settings file 'C:\\temp\\repository\\settings.gradle' line: 9

                * What went wrong:
                Plugin [id: 'org.gradle.toolchains.foojay-resolver-convention', version: '1.0.0'] was not found in any of the following sources:

                - Gradle Core Plugins
                - Included Builds
                - Plugin Repositories (could not resolve plugin artifact 'org.gradle.toolchains.foojay-resolver-convention:org.gradle.toolchains.foojay-resolver-convention.gradle.plugin:1.0.0')
                  Searched in the following repositories:
                    Gradle Central Plugin Repository
                """,
                GradleExecutionMode.TOOLING_API.name(),
                "9.5.0",
                "25",
                GradleExecutionFailureType.SETTINGS_PLUGIN_RESOLUTION_FAILED,
                "Settings plugin could not be resolved: org.gradle.toolchains.foojay-resolver-convention:1.0.0",
                new GradlePluginResolutionFailure(
                        "org.gradle.toolchains.foojay-resolver-convention",
                        "1.0.0",
                        "org.gradle.toolchains.foojay-resolver-convention:org.gradle.toolchains.foojay-resolver-convention.gradle.plugin:1.0.0",
                        "settings.gradle",
                        9,
                        List.of("Gradle Central Plugin Repository"),
                        "Plugin resolution failed"
                )
        );
        RecordingExecutionService executionService = new RecordingExecutionService();
        GradleModelAnalyzer analyzer = analyzer(toolingExecutionService, executionService);

        var result = analyzer.analyze(
                new GitRepositoryReference("https://github.com/example/demo.git", "main", null, AnalysisMode.STATIC_PLUS_GRADLE_MODEL),
                tempDir,
                gradleBuildInfo(),
                gradleProperties(true, GradleExecutionMode.TOOLING_API, "9.5.0", true, null, null)
        );

        assertThat(executionService.invoked).isFalse();
        assertThat(result.gradleModelAnalysis().status()).isEqualTo(GradleAnalysisStatus.PARTIAL);
        assertThat(result.gradleModelAnalysis().failureType()).isEqualTo(GradleExecutionFailureType.SETTINGS_PLUGIN_RESOLUTION_FAILED.name());
        assertThat(result.gradleModelAnalysis().pluginResolutionFailures()).hasSize(1);
        assertThat(result.findings()).extracting(finding -> finding.message())
                .anyMatch(message -> message.contains("settings plugin could not be resolved"));
    }

    @Test
    void systemFallbackEnabledButExecutableMissingReturnsFindingNotException() {
        RecordingToolingApiExecutionService toolingExecutionService = new RecordingToolingApiExecutionService();
        toolingExecutionService.nextResult = new GradleExecutionResult(
                false,
                false,
                null == null ? -1 : 0,
                null,
                null,
                "Tooling API failed",
                GradleExecutionMode.TOOLING_API.name(),
                "9.5.0",
                "25",
                GradleExecutionFailureType.TOOLING_API_TRANSPORT_FAILED,
                "Tooling API transport failed.",
                null
        );
        RecordingExecutionService executionService = new RecordingExecutionService();
        executionService.nextResult = new GradleExecutionResult(
                false,
                false,
                -1,
                null,
                null,
                null,
                GradleExecutionMode.SYSTEM_GRADLE.name(),
                null,
                "25",
                GradleExecutionFailureType.EXECUTABLE_NOT_FOUND,
                "External Gradle fallback was skipped because no Gradle executable was configured or found on PATH.",
                null
        );
        GradleModelAnalyzer analyzer = analyzer(toolingExecutionService, executionService);

        var result = analyzer.analyze(
                new GitRepositoryReference("https://github.com/example/demo.git", "main", null, AnalysisMode.STATIC_PLUS_GRADLE_MODEL),
                tempDir,
                gradleBuildInfo(),
                gradleProperties(true, GradleExecutionMode.TOOLING_API, "9.5.0", true, null, null)
        );

        assertThat(executionService.invoked).isTrue();
        assertThat(result.gradleModelAnalysis().status()).isEqualTo(GradleAnalysisStatus.PARTIAL);
        assertThat(result.findings()).extracting(finding -> finding.message())
                .anyMatch(message -> message.contains("no Gradle executable was configured or found on PATH"));
    }

    @Test
    void wrapperModeWithWrapper8143AndJava25IsSkippedWithClearFinding() throws Exception {
        Files.createDirectories(tempDir.resolve("gradle/wrapper"));
        Files.writeString(tempDir.resolve("gradlew.bat"), "@echo off");
        Files.writeString(
                tempDir.resolve("gradle/wrapper/gradle-wrapper.properties"),
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14.3-bin.zip"
        );

        RecordingToolingApiExecutionService toolingExecutionService = new RecordingToolingApiExecutionService();
        RecordingExecutionService executionService = new RecordingExecutionService();
        GradleModelAnalyzer analyzer = analyzer(toolingExecutionService, executionService);

        var result = analyzer.analyze(
                new GitRepositoryReference("https://github.com/example/demo.git", "main", null, AnalysisMode.STATIC_PLUS_GRADLE_MODEL),
                tempDir,
                gradleBuildInfo(),
                gradleProperties(true, GradleExecutionMode.WRAPPER, "9.5.0", false, null, null)
        );

        assertThat(toolingExecutionService.invoked).isFalse();
        assertThat(result.findings()).extracting(finding -> finding.message())
                .anyMatch(message -> message.contains("diagnostic Gradle 8.14.3 cannot run on Java 25")
                        || message.contains("cannot run on Java 25"));
    }

    @Test
    void toolingApiGradleVersionComesFromConfiguredDiagnosticGradleVersion() {
        RecordingToolingApiExecutionService toolingExecutionService = new RecordingToolingApiExecutionService();
        RecordingExecutionService executionService = new RecordingExecutionService();
        GradleModelAnalyzer analyzer = analyzer(toolingExecutionService, executionService);

        analyzer.analyze(
                new GitRepositoryReference("https://github.com/example/demo.git", "main", null, AnalysisMode.STATIC_PLUS_GRADLE_MODEL),
                tempDir,
                gradleBuildInfo(),
                gradleProperties(true, GradleExecutionMode.TOOLING_API, "9.5.0", false, null, null)
        );

        assertThat(toolingExecutionService.lastGradleVersion).isEqualTo("9.5.0");
    }

    @Test
    void successfulGradleRunWithoutResolvedGraphReturnsPartialAndClearsNoneFailureType() {
        RecordingToolingApiExecutionService toolingExecutionService = new RecordingToolingApiExecutionService();
        toolingExecutionService.nextResult = new GradleExecutionResult(
                true,
                false,
                0,
                tempDir.resolve("report.json"),
                null,
                "",
                GradleExecutionMode.TOOLING_API.name(),
                "9.5.0",
                "25",
                GradleExecutionFailureType.NONE,
                null,
                null
        );
        RecordingExecutionService executionService = new RecordingExecutionService();
        GradleModelAnalysis parsed = new GradleModelAnalysis(
                GradleAnalysisStatus.SUCCESS,
                "9.5.0",
                "25",
                GradleExecutionMode.TOOLING_API.name(),
                tempDir.resolve("report.json").toString(),
                null,
                null,
                false,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                com.example.springbootanalyzer.analyzer.model.gradle.GradlePluginResolutionBridgeResult.empty(),
                false,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(new GradleConfigurationModel(":", "runtimeClasspath", true, false, 1, 1, 1, List.of("implementation"))),
                List.of(new GradleDependencyModel(":", "implementation", "org.springframework.boot:spring-boot-starter-web", "org.springframework.boot", "spring-boot-starter-web", "3.5.13")),
                List.of(),
                List.of(new GradleResolutionResult(":", "runtimeClasspath", true, false, true, "EMPTY_LENIENT_RESOLUTION_RESULT", "Dependency graph returned no module components.", 0)),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        GradleModelAnalyzer analyzer = analyzer(toolingExecutionService, executionService, parsed);

        var result = analyzer.analyze(
                new GitRepositoryReference("https://github.com/example/demo.git", "main", null, AnalysisMode.STATIC_PLUS_GRADLE_MODEL),
                tempDir,
                gradleBuildInfo(),
                gradleProperties(true, GradleExecutionMode.TOOLING_API, "9.5.0", false, null, null)
        );

        assertThat(result.gradleModelAnalysis().status()).isEqualTo(GradleAnalysisStatus.PARTIAL);
        assertThat(result.gradleModelAnalysis().failureType()).isEqualTo(GradleExecutionFailureType.DEPENDENCY_RESOLUTION_FAILED.name());
        assertThat(result.gradleModelAnalysis().errorMessage()).contains("Dependency graph resolution failed");
        assertThat(result.findings()).extracting(finding -> finding.message())
                .anyMatch(message -> message.contains("no dependency-bearing configuration resolved a dependency graph")
                        || message.contains("Dependency resolution failed for runtimeClasspath"));
    }

    private GradleModelAnalyzer analyzer(
            RecordingToolingApiExecutionService toolingExecutionService,
            RecordingExecutionService executionService
    ) {
        return analyzer(
                toolingExecutionService,
                executionService,
                GradleModelAnalysis.empty(
                        GradleAnalysisStatus.SUCCESS,
                        GradleExecutionMode.TOOLING_API.name(),
                        "9.5.0",
                        "25",
                        null,
                        null,
                        List.of()
                )
        );
    }

    private GradleModelAnalyzer analyzer(
            RecordingToolingApiExecutionService toolingExecutionService,
            RecordingExecutionService executionService,
            GradleModelAnalysis parsedModel
    ) {
        GradleFailureClassifier failureClassifier = new GradleFailureClassifier(new GradlePluginResolutionFailureParser());
        return new GradleModelAnalyzer(
                new GradleSafetyPolicy(new GradleJavaCompatibilityService()),
                new GradleJavaCompatibilityService(),
                toolingExecutionService,
                executionService,
                new StubParser(parsedModel),
                new GradleSettingsPluginScanner(),
                new GradlePluginDeclarationScanner(new GradleVersionCatalogPluginScanner()),
                new GradlePluginResolutionBridge(
                        new GradleCorePluginDetector(),
                        (url, timeout, networkSettings) -> new GradlePluginResolutionBridge.ArtifactResponse(404, new byte[0])
                )
        );
    }

    private BuildInfo gradleBuildInfo() {
        return new BuildInfo(
                BuildTool.GRADLE,
                true,
                "25",
                List.of("org.springframework.boot:spring-boot-starter-web"),
                "3.5.13",
                "Gradle plugins",
                "HIGH"
        );
    }

    private AnalyzerProperties gradleProperties(
            boolean enabled,
            GradleExecutionMode executionMode,
            String diagnosticGradleVersion,
            boolean allowSystemFallback,
            Path executable,
            Path javaHome
    ) {
        return new AnalyzerProperties(
                tempDir,
                true,
                false,
                new AnalyzerProperties.ScheduledWorkspaceCleanupProperties(true, Duration.ofDays(7), 4),
                new AnalyzerProperties.GradleProperties(
                        enabled,
                        Duration.ofSeconds(5),
                        executionMode,
                        diagnosticGradleVersion,
                        tempDir.resolve("gradle-cache"),
                        List.of(),
                        null,
                        null,
                        true,
                        List.of("https://plugins.gradle.org/m2/"),
                        true,
                        false,
                        true,
                        false,
                        true,
                        false,
                        false,
                        new AnalyzerProperties.SettingsPluginWorkaroundProperties(false, false, List.of(), 1),
                        new AnalyzerProperties.PluginResolutionBridgeProperties(
                                false,
                                false,
                                false,
                                "Spring Boot Analyzer plugin cache",
                                List.of("https://plugins.gradle.org/m2/", "https://repo.maven.apache.org/maven2/"),
                                Duration.ofSeconds(30),
                                50,
                                500,
                                false,
                                2
                        ),
                        allowSystemFallback,
                        executionMode == GradleExecutionMode.WRAPPER,
                        true,
                        executable,
                        javaHome,
                        1024,
                        100
                )
        );
    }

    private static final class RecordingToolingApiExecutionService extends GradleToolingApiExecutionService {
        private boolean invoked;
        private String lastGradleVersion;
        private GradleExecutionResult nextResult = new GradleExecutionResult(
                false,
                false,
                -1,
                null,
                null,
                "Tooling API failed",
                GradleExecutionMode.TOOLING_API.name(),
                "9.5.0",
                "25",
                GradleExecutionFailureType.BUILD_LOGIC_FAILED,
                "Gradle diagnostic task failed during build configuration.",
                null
        );

        private RecordingToolingApiExecutionService() {
            super(
                    new GradleJavaCompatibilityService(),
                    new GradleFailureClassifier(new GradlePluginResolutionFailureParser())
            );
        }

        @Override
        public GradleExecutionResult execute(
                Path repositoryRoot,
                GradleExecutionMode executionMode,
                Path wrapperScript,
                String gradleVersion,
                int javaFeatureVersion,
                AnalyzerProperties.GradleProperties properties
        ) {
            return execute(repositoryRoot, executionMode, wrapperScript, gradleVersion, javaFeatureVersion, properties, null);
        }

        @Override
        public GradleExecutionResult execute(
                Path repositoryRoot,
                GradleExecutionMode executionMode,
                Path wrapperScript,
                String gradleVersion,
                int javaFeatureVersion,
                AnalyzerProperties.GradleProperties properties,
                Path localPluginRepository
        ) {
            invoked = true;
            lastGradleVersion = gradleVersion;
            return nextResult;
        }
    }

    private static final class RecordingExecutionService extends GradleExecutionService {
        private boolean invoked;
        private GradleExecutionResult nextResult = new GradleExecutionResult(
                true,
                false,
                0,
                null,
                null,
                "",
                GradleExecutionMode.SYSTEM_GRADLE.name(),
                null,
                "25",
                GradleExecutionFailureType.NONE,
                null,
                null
        );

        private RecordingExecutionService() {
            super(
                    new GradleCommandBuilder(),
                    new GradleExecutableLocator(),
                    new GradleJavaCompatibilityService(),
                    new GradleFailureClassifier(new GradlePluginResolutionFailureParser())
            );
        }

        @Override
        public GradleExecutionResult execute(
                Path repositoryRoot,
                GradleExecutionMode executionMode,
                Path wrapperScript,
                String gradleVersion,
                int javaFeatureVersion,
                AnalyzerProperties.GradleProperties properties
        ) {
            return execute(repositoryRoot, executionMode, wrapperScript, gradleVersion, javaFeatureVersion, properties, null);
        }

        @Override
        public GradleExecutionResult execute(
                Path repositoryRoot,
                GradleExecutionMode executionMode,
                Path wrapperScript,
                String gradleVersion,
                int javaFeatureVersion,
                AnalyzerProperties.GradleProperties properties,
                Path localPluginRepository
        ) {
            invoked = true;
            return nextResult;
        }
    }

    private static final class StubParser extends GradleModelReportParser {
        private final GradleModelAnalysis modelAnalysis;

        private StubParser(GradleModelAnalysis modelAnalysis) {
            this.modelAnalysis = modelAnalysis;
        }

        @Override
        public GradleModelAnalysis parse(Path reportFile, String executionMode) {
            return new GradleModelAnalysis(
                    modelAnalysis.status(),
                    modelAnalysis.gradleVersion(),
                    modelAnalysis.javaVersion(),
                    executionMode,
                    reportFile == null ? null : reportFile.toString(),
                    modelAnalysis.failureType(),
                    modelAnalysis.errorMessage(),
                    modelAnalysis.sanitizedBuildModel(),
                    modelAnalysis.sanitizedBuildReason(),
                    modelAnalysis.appliedWorkarounds(),
                    modelAnalysis.settingsPlugins(),
                    modelAnalysis.pluginResolutionFailures(),
                    modelAnalysis.pluginDeclarations(),
                    modelAnalysis.pluginResolutionBridge(),
                    modelAnalysis.pluginBridgeUsed(),
                    modelAnalysis.pluginBridgeStatus(),
                    modelAnalysis.projects(),
                    modelAnalysis.plugins(),
                    modelAnalysis.repositories(),
                    modelAnalysis.configurations(),
                    modelAnalysis.declaredDependencies(),
                    modelAnalysis.resolvedDependencies(),
                    modelAnalysis.resolutionResults(),
                    modelAnalysis.dependencyConflicts(),
                    modelAnalysis.sourceSets(),
                    modelAnalysis.tasks(),
                    modelAnalysis.javaToolchains(),
                    modelAnalysis.findings()
            );
        }
    }
}
