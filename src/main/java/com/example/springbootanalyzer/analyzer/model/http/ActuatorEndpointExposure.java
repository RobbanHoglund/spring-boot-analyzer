package com.example.springbootanalyzer.analyzer.model.http;

import java.util.List;

public record ActuatorEndpointExposure(
        String propertyName,
        String value,
        String sourceFile,
        Integer line,
        String profile,
        List<String> exposedEndpoints
) {
}
