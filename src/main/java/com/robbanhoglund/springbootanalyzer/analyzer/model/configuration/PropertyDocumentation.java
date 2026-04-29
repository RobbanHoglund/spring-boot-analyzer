package com.robbanhoglund.springbootanalyzer.analyzer.model.configuration;

import java.util.List;

public record PropertyDocumentation(
        boolean known,
        String type,
        String description,
        String defaultValue,
        String sourceType,
        boolean deprecated,
        String deprecationReason,
        List<PropertyValueHint> hints
) {
    public static PropertyDocumentation unknown() {
        return new PropertyDocumentation(false, null, null, null, null, false, null, List.of());
    }
}
