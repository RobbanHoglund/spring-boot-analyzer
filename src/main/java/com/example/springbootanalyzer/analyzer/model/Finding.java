package com.example.springbootanalyzer.analyzer.model;

public record Finding(
        FindingSeverity severity,
        String message,
        String location,
        String ruleId,
        String title,
        FindingCategory category,
        FindingRuntimeDetection runtimeDetection,
        FindingConfidence confidence,
        String whyBadPractice,
        String possibleImpact,
        String recommendation,
        String evidence,
        String limitations,
        String sourceFile,
        Integer line,
        String target
) {

    public Finding(FindingSeverity severity, String message, String location) {
        this(
                severity,
                message,
                location,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                location,
                null,
                null
        );
    }
}
