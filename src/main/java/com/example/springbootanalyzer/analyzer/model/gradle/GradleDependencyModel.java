package com.example.springbootanalyzer.analyzer.model.gradle;

public record GradleDependencyModel(
        String projectPath,
        String configuration,
        String notation,
        String group,
        String artifact,
        String version
) {
}
