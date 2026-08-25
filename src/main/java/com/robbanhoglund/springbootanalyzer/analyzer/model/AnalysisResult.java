package com.robbanhoglund.springbootanalyzer.analyzer.model;

import com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.ConfigurationAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleModelAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.http.HttpSurfaceAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.messaging.MessagingAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.runtime.RuntimeStackAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.scheduling.SchedulingAnalysis;
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
        GradleModelAnalysis gradleModelAnalysis,
        SchedulingAnalysis schedulingAnalysis,
        MessagingAnalysis messagingAnalysis,
        List<String> suppressedRuleIds,
        int suppressedFindingCount,
        List<String> unknownSuppressedRuleIds) {

    public AnalysisResult(
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
            GradleModelAnalysis gradleModelAnalysis,
            SchedulingAnalysis schedulingAnalysis,
            MessagingAnalysis messagingAnalysis) {
        this(
                repositoryUrl,
                branch,
                workspaceId,
                analysisId,
                commitSha,
                buildInfo,
                mainApplicationClasses,
                detectedComponents,
                findings,
                configurationAnalysis,
                runtimeStackAnalysis,
                httpSurfaceAnalysis,
                gradleModelAnalysis,
                schedulingAnalysis,
                messagingAnalysis,
                List.of(),
                0,
                List.of());
    }
}
