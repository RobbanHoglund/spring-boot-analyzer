package com.robbanhoglund.springbootanalyzer.analyzer.model.gradle;

public record GradlePluginBridgeFailure(
        String pluginId,
        String version,
        String markerCoordinates,
        String sourceFile,
        Integer line,
        String failureType,
        String message,
        boolean markerPresentLocally,
        boolean implementationPresentLocally,
        String implementationCoordinates
) {
}
