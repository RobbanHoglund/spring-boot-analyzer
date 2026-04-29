package com.example.springbootanalyzer.analyzer.configuration;

record ParsedConfigurationProperty(
        String name,
        String value,
        String sourceFile,
        Integer line,
        String profile
) {
}
