package com.robbanhoglund.springbootanalyzer.analyzer.model.gradle;

public record GradleJavaToolchainModel(
        String projectPath, String languageVersion, String vendor, String implementation) {}
