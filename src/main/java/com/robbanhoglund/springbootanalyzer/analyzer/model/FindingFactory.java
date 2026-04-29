package com.robbanhoglund.springbootanalyzer.analyzer.model;

import java.util.ArrayList;
import java.util.List;

public final class FindingFactory {

    private FindingFactory() {
    }

    public static Builder builder(
            String ruleId,
            String title,
            FindingSeverity severity,
            FindingCategory category,
            FindingRuntimeDetection runtimeDetection,
            FindingConfidence confidence
    ) {
        return new Builder(ruleId, title, severity, category, runtimeDetection, confidence);
    }

    public static Builder builder(FindingRule rule, FindingConfidence confidence) {
        return new Builder(
                rule.ruleId(),
                rule.title(),
                rule.defaultSeverity(),
                rule.category(),
                rule.runtimeDetection(),
                confidence
        );
    }

    public static final class Builder {
        private final String ruleId;
        private final String title;
        private final FindingSeverity severity;
        private final FindingCategory category;
        private final FindingRuntimeDetection runtimeDetection;
        private final FindingConfidence confidence;
        private String shortMessage;
        private String whyBadPractice;
        private String possibleImpact;
        private String recommendation;
        private String evidence;
        private String limitations;
        private String sourceFile;
        private Integer line;
        private String target;
        private String location;
        private SourceLocation primaryLocation;
        private final List<HighlightRange> highlightRanges = new ArrayList<>();
        private final List<FindingOccurrence> occurrences = new ArrayList<>();

        private Builder(
                String ruleId,
                String title,
                FindingSeverity severity,
                FindingCategory category,
                FindingRuntimeDetection runtimeDetection,
                FindingConfidence confidence
        ) {
            this.ruleId = ruleId;
            this.title = title;
            this.severity = severity;
            this.category = category;
            this.runtimeDetection = runtimeDetection;
            this.confidence = confidence;
        }

        public Builder shortMessage(String shortMessage) {
            this.shortMessage = shortMessage;
            return this;
        }

        public Builder whyBadPractice(String whyBadPractice) {
            this.whyBadPractice = whyBadPractice;
            return this;
        }

        public Builder possibleImpact(String possibleImpact) {
            this.possibleImpact = possibleImpact;
            return this;
        }

        public Builder recommendation(String recommendation) {
            this.recommendation = recommendation;
            return this;
        }

        public Builder evidence(String evidence) {
            this.evidence = evidence;
            return this;
        }

        public Builder limitations(String limitations) {
            this.limitations = limitations;
            return this;
        }

        public Builder source(String sourceFile, Integer line) {
            this.sourceFile = sourceFile;
            this.line = line;
            if (sourceFile != null && line != null && line > 0) {
                this.primaryLocation = new SourceLocation(
                        sourceFile,
                        line,
                        line,
                        null,
                        null,
                        target,
                        SourceLocation.inferLanguage(sourceFile),
                        null
                );
                if (highlightRanges.isEmpty()) {
                    highlightRanges.add(new HighlightRange(line, line, null, null, "issue"));
                }
            }
            return this;
        }

        public Builder sourceLocation(SourceLocation primaryLocation) {
            this.primaryLocation = primaryLocation;
            if (primaryLocation != null) {
                this.sourceFile = primaryLocation.filePath();
                this.line = primaryLocation.startLine();
            }
            return this;
        }

        public Builder highlightRange(HighlightRange range) {
            if (range != null) {
                highlightRanges.add(range);
            }
            return this;
        }

        public Builder occurrences(List<FindingOccurrence> occurrences) {
            this.occurrences.clear();
            if (occurrences != null) {
                this.occurrences.addAll(occurrences);
            }
            return this;
        }

        public Builder addOccurrence(FindingOccurrence occurrence) {
            if (occurrence != null) {
                this.occurrences.add(occurrence);
            }
            return this;
        }

        public Builder target(String target) {
            this.target = target;
            return this;
        }

        public Builder location(String location) {
            this.location = location;
            return this;
        }

        public Finding build() {
            String effectiveLocation = location != null ? location : sourceFile;
            SourceLocation effectivePrimaryLocation = primaryLocation;
            if (effectivePrimaryLocation != null
                    && (effectivePrimaryLocation.symbol() == null || effectivePrimaryLocation.symbol().isBlank())
                    && target != null
                    && !target.isBlank()) {
                effectivePrimaryLocation = new SourceLocation(
                        effectivePrimaryLocation.filePath(),
                        effectivePrimaryLocation.startLine(),
                        effectivePrimaryLocation.endLine(),
                        effectivePrimaryLocation.startColumn(),
                        effectivePrimaryLocation.endColumn(),
                        target,
                        effectivePrimaryLocation.language(),
                        effectivePrimaryLocation.githubUrl()
                );
            }
            return new Finding(
                    severity,
                    shortMessage,
                    effectiveLocation,
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
                    effectivePrimaryLocation,
                    List.copyOf(highlightRanges),
                    List.copyOf(occurrences),
                    List.of()
            );
        }
    }
}
