package com.example.springbootanalyzer.analyzer.model.gradle;

public record GradlePluginModel(
        String projectPath,
        String pluginId,
        String implementationClass
) {
}
