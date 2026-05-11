package com.robbanhoglund.springbootanalyzer.analyzer.model.configuration;

import java.util.List;

public record ConfigurationAnalysis(
        List<ConfigurationFile> files,
        List<ApplicationProperty> properties,
        List<PropertyReference> codeReferences,
        List<ConfigurationPropertiesClass> configurationPropertiesClasses,
        ConfigurationSummary summary) {
    public static ConfigurationAnalysis empty() {
        return new ConfigurationAnalysis(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new ConfigurationSummary(0, 0, 0, 0, 0, 0, List.of()));
    }
}
