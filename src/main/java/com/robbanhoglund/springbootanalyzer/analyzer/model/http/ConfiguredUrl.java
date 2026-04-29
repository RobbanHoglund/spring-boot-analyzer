package com.robbanhoglund.springbootanalyzer.analyzer.model.http;

public record ConfiguredUrl(
        String propertyName,
        String value,
        boolean valueRedacted,
        String host,
        String referencedPropertyName,
        String sourceFile,
        Integer line,
        String profile,
        UrlKind kind
) {
}
