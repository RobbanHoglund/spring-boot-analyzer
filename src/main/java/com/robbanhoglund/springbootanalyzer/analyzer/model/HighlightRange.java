package com.robbanhoglund.springbootanalyzer.analyzer.model;

public record HighlightRange(
        int startLine,
        int endLine,
        Integer startColumn,
        Integer endColumn,
        String kind
) {
}
