package com.example.springbootanalyzer.analyzer.model;

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
                    target
            );
        }
    }
}
