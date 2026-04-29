package com.robbanhoglund.springbootanalyzer.analyzer.model;

import java.util.List;

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
        List<RelatedFindingSignal> relatedSignals
) {

    public Finding {
        highlightRanges = highlightRanges == null ? List.of() : List.copyOf(highlightRanges);
        occurrences = occurrences == null ? List.of() : List.copyOf(occurrences);
        relatedSignals = relatedSignals == null ? List.of() : List.copyOf(relatedSignals);
    }

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
                null,
                null,
                List.of(),
                List.of(),
                List.of()
        );
    }

    public Finding withSourceDetails(
            SourceLocation primaryLocation,
            List<HighlightRange> highlightRanges,
            List<FindingOccurrence> occurrences
    ) {
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
                relatedSignals
        );
    }

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
                relatedSignals
        );
    }
}
