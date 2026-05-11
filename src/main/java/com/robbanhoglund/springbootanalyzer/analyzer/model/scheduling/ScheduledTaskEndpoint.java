package com.robbanhoglund.springbootanalyzer.analyzer.model.scheduling;

public record ScheduledTaskEndpoint(
        String className,
        String methodName,
        String sourceFile,
        Integer line,
        String scheduleType,
        String scheduleValue,
        String zone) {}
