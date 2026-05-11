package com.robbanhoglund.springbootanalyzer.analyzer.model.gradle;

public record GradleResolvedDependencyModel(
        String projectPath,
        String configuration,
        String group,
        String artifact,
        String version,
        boolean direct,
        String selectedReason) {}
