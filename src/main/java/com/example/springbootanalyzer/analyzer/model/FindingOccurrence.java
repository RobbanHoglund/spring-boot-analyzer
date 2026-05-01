package com.example.springbootanalyzer.analyzer.model;

import java.util.List;

public record FindingOccurrence(
        String message,
        SourceLocation location,
        List<HighlightRange> highlightRanges
) {

    public FindingOccurrence {
        highlightRanges = highlightRanges == null ? List.of() : List.copyOf(highlightRanges);
    }

    public FindingOccurrence withGithubUrl(String githubUrl) {
        if (location == null) {
            return this;
        }
        return new FindingOccurrence(message, location.withGithubUrl(githubUrl), highlightRanges);
    }
}
