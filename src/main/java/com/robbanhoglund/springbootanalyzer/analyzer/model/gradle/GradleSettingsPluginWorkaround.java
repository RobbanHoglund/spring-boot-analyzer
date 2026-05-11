package com.robbanhoglund.springbootanalyzer.analyzer.model.gradle;

public record GradleSettingsPluginWorkaround(
        String pluginId,
        String version,
        String sourceFile,
        Integer line,
        String action,
        String reason) {}
