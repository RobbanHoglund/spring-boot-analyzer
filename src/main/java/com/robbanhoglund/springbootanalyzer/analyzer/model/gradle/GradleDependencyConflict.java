package com.robbanhoglund.springbootanalyzer.analyzer.model.gradle;

public record GradleDependencyConflict(
        String projectPath,
        String configuration,
        String group,
        String artifact,
        String requestedVersions,
        String selectedVersion) {}
