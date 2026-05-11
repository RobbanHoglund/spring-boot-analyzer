package com.robbanhoglund.springbootanalyzer.analyzer.model.runtime;

import java.util.List;

public record VirtualThreadAnalysis(
        boolean enabledByProperty,
        boolean javaVersionCompatible,
        boolean explicitVirtualThreadApiUsage,
        boolean scheduledWorkDetected,
        boolean keepAliveConfigured,
        String summary,
        List<String> evidence) {}
