package com.robbanhoglund.springbootanalyzer.analyzer.model.gradle;

public record GradleSettingsPluginModel(
        String pluginId, String version, String sourceFile, Integer line) {}
