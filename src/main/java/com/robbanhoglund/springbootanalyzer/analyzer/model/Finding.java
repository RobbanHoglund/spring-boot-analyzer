package com.robbanhoglund.springbootanalyzer.analyzer.model;

import java.util.List;

/**
 * An immutable, richly structured finding produced by one of the static analyzers.
 *
 * <p>Every finding carries a two-layer identity: a short human-readable {@code message} and,
 * for rule-based findings, a stable {@code ruleId} that consumers can use to suppress or
 * filter specific categories. The educational narrative ({@code whyBadPractice},
 * {@code possibleImpact}, {@code recommendation}, {@code evidence}, {@code limitations})
 * is intentionally verbose so that the UI can surface actionable guidance without the
 * user having to look anything up.
 *
 * <p>Source position is represented at two granularities:
 * <ul>
 *   <li>{@code sourceFile} + {@code line} — a flat file/line pair, always populated for
 *       source-level findings and convenient for simple display.</li>
 *   <li>{@code primaryLocation} — a rich {@link SourceLocation} that additionally carries
 *       column bounds, a symbol name, language hint, and an optional GitHub permalink.
 *       It is set by {@link FindingFactory.Builder} when a source file and line are
 *       provided, and may be null for configuration-only findings.</li>
 * </ul>
 *
 * <p>Multi-site findings (e.g. a secret duplicated across profiles) use {@code occurrences}
 * to record each individual site, while {@code relatedSignals} holds findings that were
 * demoted by {@code FindingNormalizer} because a more specific rule already covers the
 * same location.
 *
 * <p>List fields are always non-null and immutable; the compact constructor enforces this.
 */
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
        String target,
        SourceLocation primaryLocation,
        List<HighlightRange> highlightRanges,
        List<FindingOccurrence> occurrences,
        List<RelatedFindingSignal> relatedSignals) {

    public Finding {
        highlightRanges = highlightRanges == null ? List.of() : List.copyOf(highlightRanges);
        occurrences = occurrences == null ? List.of() : List.copyOf(occurrences);
        relatedSignals = relatedSignals == null ? List.of() : List.copyOf(relatedSignals);
    }

    /**
     * Convenience constructor for simple structural findings that do not carry a rule ID
     * or educational narrative. Used primarily for project-structure warnings emitted
     * directly by the analyzer orchestrator (missing main class, component-scan issues).
     *
     * @param severity the severity level
     * @param message  a short human-readable description
     * @param location the file path or logical location the finding refers to;
     *                 also used as {@code sourceFile} so that simple display still works
     */
    public Finding(FindingSeverity severity, String message, String location) {
        this(
                severity, message, location, null, null, null, null, null, null, null, null, null,
                null, location, null, null, null, List.of(), List.of(), List.of());
    }

    /**
     * Returns a copy of this finding enriched with source-location details.
     * Used by post-processing steps that resolve GitHub permalinks after the initial
     * analysis, without re-running the analyzer.
     *
     * @param primaryLocation the resolved primary source location
     * @param highlightRanges highlight ranges within the primary file
     * @param occurrences     individual occurrence sites for multi-location findings
     * @return a new finding instance with the provided source details
     */
    public Finding withSourceDetails(
            SourceLocation primaryLocation,
            List<HighlightRange> highlightRanges,
            List<FindingOccurrence> occurrences) {
        return new Finding(
                severity,
                message,
                location,
                ruleId,
                title,
                category,
                runtimeDetection,
                confidence,
                whyBadPractice,
                possibleImpact,
                recommendation,
                evidence,
                limitations,
                sourceFile,
                line,
                target,
                primaryLocation,
                highlightRanges,
                occurrences,
                relatedSignals);
    }

    /**
     * Returns a copy of this finding with the given related signals attached.
     * Called by {@code FindingNormalizer} when it promotes one finding to primary
     * and demotes overlapping findings to subordinate signals.
     *
     * @param relatedSignals the demoted findings to attach as context
     * @return a new finding instance with the related signals set
     */
    public Finding withRelatedSignals(List<RelatedFindingSignal> relatedSignals) {
        return new Finding(
                severity,
                message,
                location,
                ruleId,
                title,
                category,
                runtimeDetection,
                confidence,
                whyBadPractice,
                possibleImpact,
                recommendation,
                evidence,
                limitations,
                sourceFile,
                line,
                target,
                primaryLocation,
                highlightRanges,
                occurrences,
                relatedSignals);
    }
}
