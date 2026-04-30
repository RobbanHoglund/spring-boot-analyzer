package com.example.springbootanalyzer.analyzer.model.gradle;

public record ResolvedGradlePlugin(
        String pluginId,
        String version,
        String markerCoordinates,
        String implementationCoordinates,
        String sourceFile,
        Integer line,
        boolean markerDownloaded,
        boolean implementationDownloaded,
        int transitiveArtifactCount
) {
}
