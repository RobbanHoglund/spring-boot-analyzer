package com.robbanhoglund.springbootanalyzer.analyzer.model;

public record FindingRule(
        String ruleId,
        String title,
        FindingSeverity defaultSeverity,
        FindingCategory category,
        FindingRuntimeDetection runtimeDetection) {}
