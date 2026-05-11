package com.robbanhoglund.springbootanalyzer.analyzer.model.configuration;

import java.util.List;

public record ConfigurationSummary(
        int configuredPropertyCount,
        int knownSpringBootPropertyCount,
        int customPropertyCount,
        int unknownPropertyCount,
        int codeReferenceCount,
        int sensitiveValueCount,
        List<String> profiles) {}
