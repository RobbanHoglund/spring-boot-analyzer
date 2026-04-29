package com.example.springbootanalyzer.api.dto;

import com.example.springbootanalyzer.analyzer.model.BuildTool;
import com.example.springbootanalyzer.analyzer.model.DetectedClass;
import com.example.springbootanalyzer.analyzer.model.Finding;
import com.example.springbootanalyzer.analyzer.model.configuration.ConfigurationAnalysis;
import com.example.springbootanalyzer.analyzer.model.http.HttpSurfaceAnalysis;
import com.example.springbootanalyzer.analyzer.model.runtime.RuntimeStackAnalysis;
import java.util.List;

public record AnalyzeRepositoryResponse(
        String repositoryUrl,
        String branch,
        String workspaceId,
        BuildTool buildTool,
        String javaVersionHint,
        boolean springBootDetected,
        List<String> mainApplicationClasses,
        List<DetectedClass> detectedComponents,
        List<String> dependencies,
        List<Finding> findings,
        ConfigurationAnalysis configurationAnalysis,
        RuntimeStackAnalysis runtimeStackAnalysis,
        HttpSurfaceAnalysis httpSurfaceAnalysis
) {
}
