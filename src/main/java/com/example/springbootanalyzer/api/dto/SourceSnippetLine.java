package com.example.springbootanalyzer.api.dto;

public record SourceSnippetLine(
        int lineNumber,
        String text,
        boolean highlight
) {
}
