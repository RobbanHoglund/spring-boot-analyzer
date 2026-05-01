package com.robbanhoglund.springbootanalyzer.analyzer.model;

import com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.ConfigurationAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleModelAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.http.HttpSurfaceAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.runtime.RuntimeStackAnalysis;
import java.util.List;

public record AnalysisResult(
        String repositoryUrl,
        String branch,
        String workspaceId,
        String analysisId,
        String commitSha,
        BuildInfo buildInfo,
        List<String> mainApplicationClasses,
        List<DetectedClass> detectedComponents,
        List<Finding> findings,
        ConfigurationAnalysis configurationAnalysis,
        RuntimeStackAnalysis runtimeStackAnalysis,
        HttpSurfaceAnalysis httpSurfaceAnalysis,
        GradleModelAnalysis gradleModelAnalysis
) {
}
