package com.example.springbootanalyzer.analyzer.model.gradle;

import com.example.springbootanalyzer.analyzer.model.Finding;
import java.util.List;

public record GradlePluginResolutionBridgeResult(
        boolean successful,
        String localMavenRepository,
        List<ResolvedGradlePlugin> resolvedPlugins,
        List<GradlePluginBridgeFailure> failures,
        List<Finding> findings
) {
    public static GradlePluginResolutionBridgeResult empty() {
        return new GradlePluginResolutionBridgeResult(false, null, List.of(), List.of(), List.of());
    }
}
