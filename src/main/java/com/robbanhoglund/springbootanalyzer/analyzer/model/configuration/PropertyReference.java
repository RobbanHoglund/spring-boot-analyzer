package com.robbanhoglund.springbootanalyzer.analyzer.model.configuration;

public record PropertyReference(
        String propertyName,
        String referenceType,
        String sourceFile,
        Integer line,
        String className,
        String defaultValue,
        boolean required,
        String expectedValue,
        Boolean matchIfMissing
) {
}
