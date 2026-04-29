package com.robbanhoglund.springbootanalyzer.analyzer.model.http;

import java.util.List;

public record InboundEndpoint(
        String httpMethod,
        String path,
        String controllerClass,
        String handlerMethod,
        String sourceFile,
        Integer line,
        String produces,
        String consumes,
        List<String> parameters,
        EndpointSource source
) {
}
