package com.robbanhoglund.springbootanalyzer.api.dto;

public record RuleInfoDto(
        String ruleId,
        String title,
        String severity,
        String category,
        String runtimeDetection,
        boolean enabled) {}
