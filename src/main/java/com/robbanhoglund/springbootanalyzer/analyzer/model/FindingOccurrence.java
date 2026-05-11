package com.robbanhoglund.springbootanalyzer.analyzer.model;

import java.util.List;

/**
 * A single occurrence site for a multi-location finding.
 *
 * <p>When the same issue manifests at more than one location — e.g. a secret value duplicated
 * across several Spring profile configuration files — the primary {@link Finding} records each
 * site as a {@code FindingOccurrence} in its {@link Finding#occurrences()} list. Each occurrence
 * carries its own message, source location, and highlight ranges, allowing the UI to navigate
 * directly to each affected line.
 *
 * <p>The {@code highlightRanges} list is always non-null; the compact constructor replaces null
 * with an empty immutable list.
 */
public record FindingOccurrence(
        String message, SourceLocation location, List<HighlightRange> highlightRanges) {

    public FindingOccurrence {
        highlightRanges = highlightRanges == null ? List.of() : List.copyOf(highlightRanges);
    }

    /**
     * Returns a copy of this occurrence with the supplied GitHub permalink set on its
     * {@link SourceLocation}. If this occurrence has no location the instance is returned
     * unchanged.
     *
     * @param githubUrl the resolved GitHub permalink (e.g.
     *                  {@code https://github.com/org/repo/blob/abc123/src/…#L12})
     * @return a new occurrence with the updated location, or {@code this} if location is null
     */
    public FindingOccurrence withGithubUrl(String githubUrl) {
        if (location == null) {
            return this;
        }
        return new FindingOccurrence(message, location.withGithubUrl(githubUrl), highlightRanges);
    }
}
