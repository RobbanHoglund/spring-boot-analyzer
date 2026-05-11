package com.robbanhoglund.springbootanalyzer.analyzer.model.configuration;

public record ConfigurationFile(
        String path, String profile, PropertySourceType type, int propertyCount) {}
