package com.example.springbootanalyzer.analyzer.model;

public record Finding(
        FindingSeverity severity,
        String message,
        String location
) {
}
