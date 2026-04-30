package com.example.springbootanalyzer.analyzer.model.gradle;

import com.example.springbootanalyzer.analyzer.model.Finding;
import java.util.List;

public record GradleModelAnalysis(
        GradleAnalysisStatus status,
        String gradleVersion,
        String javaVersion,
        String executionMode,
        String reportFile,
        String failureType,
        String errorMessage,
        boolean sanitizedBuildModel,
        String sanitizedBuildReason,
        List<GradleSettingsPluginWorkaround> appliedWorkarounds,
        List<GradleSettingsPluginModel> settingsPlugins,
        List<GradlePluginResolutionFailure> pluginResolutionFailures,
        List<GradlePluginDeclaration> pluginDeclarations,
        GradlePluginResolutionBridgeResult pluginResolutionBridge,
        boolean pluginBridgeUsed,
        String pluginBridgeStatus,
        List<GradleProjectModel> projects,
        List<GradlePluginModel> plugins,
        List<GradleRepositoryModel> repositories,
        List<GradleConfigurationModel> configurations,
        List<GradleDependencyModel> declaredDependencies,
        List<GradleResolvedDependencyModel> resolvedDependencies,
        List<GradleResolutionResult> resolutionResults,
        List<GradleDependencyConflict> dependencyConflicts,
        List<GradleSourceSetModel> sourceSets,
        List<GradleTaskModel> tasks,
        List<GradleJavaToolchainModel> javaToolchains,
        List<Finding> findings
) {
    public static GradleModelAnalysis empty(GradleAnalysisStatus status, String executionMode, List<Finding> findings) {
        return empty(status, executionMode, null, null, null, null, findings);
    }

    public static GradleModelAnalysis empty(
            GradleAnalysisStatus status,
            String executionMode,
            String gradleVersion,
            String javaVersion,
            String errorType,
            String errorMessage,
            List<Finding> findings
    ) {
        return new GradleModelAnalysis(
                status,
                gradleVersion,
                javaVersion,
                executionMode,
                null,
                errorType,
                errorMessage,
                false,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                GradlePluginResolutionBridgeResult.empty(),
                false,
                null,
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
}
