package com.robbanhoglund.springbootanalyzer.analyzer.model.scheduling;

public record AsyncMethodEndpoint(
        String className, String methodName, String sourceFile, Integer line) {}
