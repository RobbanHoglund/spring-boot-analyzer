package com.robbanhoglund.springbootanalyzer.analyzer.model;

import java.util.List;

public record BuildInfo(
        BuildTool buildTool,
        boolean springBootDetected,
        String javaVersionHint,
        List<String> dependencies,
        String springBootVersion,
        String springBootVersionSource,
        String springBootVersionConfidence) {}
