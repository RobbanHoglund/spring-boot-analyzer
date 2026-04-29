package com.robbanhoglund.springbootanalyzer.analyzer.model.configuration;

import java.util.List;

public record CustomPropertyDefinition(
        String propertyName,
        String javaName,
        String type,
        List<String> validationAnnotations,
        String description
) {
}
