package com.robbanhoglund.springbootanalyzer.api.dto;

import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildTool;
import com.robbanhoglund.springbootanalyzer.analyzer.model.DetectedClass;
import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.ConfigurationAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleModelAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.http.HttpSurfaceAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.messaging.MessagingAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.runtime.RuntimeStackAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.scheduling.SchedulingAnalysis;
import java.util.List;

public record AnalyzeRepositoryResponse(
        String repositoryUrl,
        String branch,
        String workspaceId,
        String analysisId,
        String commitSha,
        BuildTool buildTool,
        String javaVersionHint,
        boolean springBootDetected,
        List<String> mainApplicationClasses,
        List<DetectedClass> detectedComponents,
        List<String> dependencies,
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

    public AnalyzeRepositoryResponse(
            String repositoryUrl,
            String branch,
            String workspaceId,
            String analysisId,
            String commitSha,
            BuildTool buildTool,
            String javaVersionHint,
            boolean springBootDetected,
            List<String> mainApplicationClasses,
            List<DetectedClass> detectedComponents,
            List<String> dependencies,
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
                buildTool,
                javaVersionHint,
                springBootDetected,
                mainApplicationClasses,
                detectedComponents,
                dependencies,
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
