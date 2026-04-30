package com.example.springbootanalyzer.analyzer.model.gradle;

public record GradleProjectModel(
        String path,
        String name,
        String projectDir
) {
}
