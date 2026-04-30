package com.example.springbootanalyzer.analyzer.model.gradle;

public record GradleTaskModel(
        String projectPath,
        String name,
        String group,
        String description
) {
}
