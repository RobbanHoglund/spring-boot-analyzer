package com.robbanhoglund.springbootanalyzer.analyzer.model.gradle;

import java.util.List;

public record GradleConfigurationModel(
        String projectPath,
        String name,
        boolean resolvable,
        boolean consumable,
        int dependencyCount,
        int declaredDependencyCount,
        int allDependencyCount,
        List<String> extendsFrom) {}
