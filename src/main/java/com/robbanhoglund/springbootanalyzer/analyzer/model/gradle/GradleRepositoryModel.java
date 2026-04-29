package com.robbanhoglund.springbootanalyzer.analyzer.model.gradle;

public record GradleRepositoryModel(
        String projectPath,
        String name,
        String type,
        String url
) {
}
