package com.example.springbootanalyzer.analyzer.model.http;

import java.util.List;

public record HttpSurfaceAnalysis(
        HttpSurfaceSummary summary,
        List<InboundEndpoint> inboundEndpoints,
        List<OutboundEndpoint> outboundEndpoints,
        List<ConfiguredUrl> configuredUrls,
        List<ActuatorEndpointExposure> actuatorExposures
) {
    public static HttpSurfaceAnalysis empty() {
        return new HttpSurfaceAnalysis(
                new HttpSurfaceSummary(0, 0, 0, 0, List.of(), List.of()),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }
}
