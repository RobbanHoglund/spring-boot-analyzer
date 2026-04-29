package com.robbanhoglund.springbootanalyzer.analyzer.model.configuration;

import java.util.List;

public record ConfigurationPropertiesClass(
        String prefix,
        String className,
        String sourceFile,
        String description,
        List<CustomPropertyDefinition> properties
) {
}
