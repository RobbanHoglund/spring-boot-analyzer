package com.robbanhoglund.springbootanalyzer.analyzer.model.gradle;

public record GradleResolutionResult(
        String projectPath,
        String configuration,
        boolean attempted,
        boolean successful,
        boolean fallbackUsed,
        String errorType,
        String errorMessage,
        int resolvedDependencyCount
) {
}
