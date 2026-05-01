package com.robbanhoglund.springbootanalyzer.analyzer.model.http;

import java.util.List;

public record HttpSurfaceSummary(
        int inboundEndpointCount,
        int outboundEndpointCount,
        int configuredUrlCount,
        int actuatorExposureCount,
        List<String> basePaths,
        List<String> externalHosts
) {
}
