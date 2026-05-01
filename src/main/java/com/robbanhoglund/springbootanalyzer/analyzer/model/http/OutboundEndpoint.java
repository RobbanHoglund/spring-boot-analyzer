package com.robbanhoglund.springbootanalyzer.analyzer.model.http;

public record OutboundEndpoint(
        String method,
        String urlOrTemplate,
        String host,
        String baseUrl,
        String fullUrlPreview,
        String clientType,
        String sourceFile,
        Integer line,
        String className,
        String methodName,
        boolean fromConfiguration,
        String configurationPropertyName
) {
}
