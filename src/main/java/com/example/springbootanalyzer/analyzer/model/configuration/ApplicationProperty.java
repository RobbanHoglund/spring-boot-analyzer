package com.example.springbootanalyzer.analyzer.model.configuration;

import java.util.List;

public record ApplicationProperty(
        String name,
        String value,
        boolean valueRedacted,
        String sourceFile,
        Integer line,
        String profile,
        PropertyKind kind,
        PropertyDocumentation documentation,
        List<PropertyReference> references
) {
}
