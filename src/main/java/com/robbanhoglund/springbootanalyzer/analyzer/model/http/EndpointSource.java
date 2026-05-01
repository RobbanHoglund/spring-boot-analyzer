package com.robbanhoglund.springbootanalyzer.analyzer.model.http;

public enum EndpointSource {
    SPRING_MVC_ANNOTATION,
    WEBFLUX_ANNOTATION,
    WEBFLUX_FUNCTIONAL_ROUTE,
    ACTUATOR,
    UNKNOWN
}
