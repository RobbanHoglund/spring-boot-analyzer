package com.robbanhoglund.springbootanalyzer.analyzer.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Factory for constructing {@link Finding} instances via a fluent {@link Builder}.
 *
 * <p>The two static {@code builder()} overloads are the only entry points; there is no
 * public constructor. The rule-based overload ({@code builder(FindingRule, FindingConfidence)})
 * is the preferred path for the vast majority of analyzer code: it pulls {@code ruleId},
 * {@code title}, {@code severity}, {@code category}, and {@code runtimeDetection} directly
 * from the catalogue entry, ensuring those fields are always consistent with the rule definition.
 * The explicit overload is available for the rare case where a finding cannot be represented
 * by a static catalogue entry.
 *
 * <p>Source position is set via {@link Builder#source(String, Integer)} (file path + line) or
 * {@link Builder#sourceLocation(SourceLocation)} (rich location with column bounds and a symbol
 * name). When {@code source()} is called with a non-null, positive line number it also creates a
 * default single-line {@link HighlightRange} tagged {@code "issue"}, so callers do not need to
 * add an explicit highlight range for the common case.
 *
 * <p>The {@link Builder#target(String)} value is lazily promoted to
 * {@link SourceLocation#symbol()} in {@link Builder#build()} if the primary location does not
 * already carry a symbol — this lets callers set {@code target} before or after calling
 * {@code source()}.
 */
public final class FindingFactory {

    private FindingFactory() {}

    /**
     * Creates a builder with all identity fields specified explicitly.
     *
     * <p>Prefer {@link #builder(FindingRule, FindingConfidence)} when the finding corresponds
     * to a catalogue entry in {@link FindingRules}. Use this overload only when a dynamic or
     * synthetic finding is needed that has no stable rule constant.
     *
     * @param ruleId           stable identifier, e.g. {@code "SPRING_SECRET_LITERAL"}
     * @param title            short human-readable name displayed in the UI heading
     * @param severity         how serious the finding is
     * @param category         which area of concern the finding belongs to
     * @param runtimeDetection whether and how this finding can be detected at runtime
     * @param confidence       how certain the analyzer is that this is a real issue
     * @return a new {@link Builder} pre-populated with the identity fields
     */
    public static Builder builder(
            String ruleId,
            String title,
            FindingSeverity severity,
            FindingCategory category,
            FindingRuntimeDetection runtimeDetection,
            FindingConfidence confidence) {
        return new Builder(ruleId, title, severity, category, runtimeDetection, confidence);
    }

    /**
     * Creates a builder from a {@link FindingRule} catalogue entry.
     *
     * <p>This is the preferred factory method for static rule-based findings. It reads
     * {@code ruleId}, {@code title}, {@code defaultSeverity}, {@code category}, and
     * {@code runtimeDetection} from the rule, ensuring consistency with the catalogue.
     *
     * @param rule       the catalogue entry that describes this class of finding
     * @param confidence how certain the analyzer is that this specific occurrence is a real issue
     * @return a new {@link Builder} pre-populated from the rule
     */
    public static Builder builder(FindingRule rule, FindingConfidence confidence) {
        return new Builder(
                rule.ruleId(),
                rule.title(),
                rule.defaultSeverity(),
                rule.category(),
                rule.runtimeDetection(),
                confidence);
    }

    /**
     * Fluent builder for {@link Finding}.
     *
     * <p>The identity fields ({@code ruleId}, {@code title}, {@code severity}, {@code category},
     * {@code runtimeDetection}, {@code confidence}) are set at construction time and are
     * immutable. All other fields default to {@code null} / empty and are populated via the
     * fluent setters before calling {@link #build()}.
     *
     * <p>Typical usage:
     * <pre>{@code
     * Finding f = FindingFactory.builder(FindingRules.SPRING_SECRET_LITERAL, FindingConfidence.HIGH)
     *         .shortMessage("Hardcoded password in application.properties")
     *         .whyBadPractice("Plain-text secrets are visible to anyone who can read the file.")
     *         .recommendation("Use an environment-variable placeholder: ${DB_PASSWORD}")
     *         .evidence("spring.datasource.password=hunter2  (line 12)")
     *         .source("src/main/resources/application.properties", 12)
     *         .target("spring.datasource.password")
     *         .location("application.properties:12")
     *         .build();
     * }</pre>
     */
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
                FindingConfidence confidence) {
            this.ruleId = ruleId;
            this.title = title;
            this.severity = severity;
            this.category = category;
            this.runtimeDetection = runtimeDetection;
            this.confidence = confidence;
        }

        /** Sets the short human-readable message displayed as the finding headline. */
        public Builder shortMessage(String shortMessage) {
            this.shortMessage = shortMessage;
            return this;
        }

        /** Sets the educational narrative explaining why this practice is problematic. */
        public Builder whyBadPractice(String whyBadPractice) {
            this.whyBadPractice = whyBadPractice;
            return this;
        }

        /** Sets the description of the potential consequences if this finding is not addressed. */
        public Builder possibleImpact(String possibleImpact) {
            this.possibleImpact = possibleImpact;
            return this;
        }

        /** Sets the actionable guidance for resolving this finding. */
        public Builder recommendation(String recommendation) {
            this.recommendation = recommendation;
            return this;
        }

        /**
         * Sets a snippet of evidence drawn from the analysed source (e.g., the offending
         * property key/value pair, the method signature, or the relevant configuration line).
         */
        public Builder evidence(String evidence) {
            this.evidence = evidence;
            return this;
        }

        /**
         * Sets a note on the known limitations of this detection — false-positive scenarios,
         * cases where the rule does not fire despite the practice being present, etc.
         */
        public Builder limitations(String limitations) {
            this.limitations = limitations;
            return this;
        }

        /**
         * Sets the primary source location by file path and line number, and creates a default
         * single-line {@link HighlightRange} tagged {@code "issue"} when the highlight list is
         * still empty.
         *
         * <p>The language of the resulting {@link SourceLocation} is inferred from the file
         * extension via {@link SourceLocation#inferLanguage(String)}. If {@code target} was set
         * before this call its value is used as the initial {@link SourceLocation#symbol()}; if
         * {@code target} is set afterward it is promoted to the symbol in {@link #build()}.
         *
         * @param sourceFile relative or absolute path to the source file
         * @param line       1-based line number; no location is created if null or &lt;= 0
         */
        public Builder source(String sourceFile, Integer line) {
            this.sourceFile = sourceFile;
            this.line = line;
            if (sourceFile != null && line != null && line > 0) {
                this.primaryLocation =
                        new SourceLocation(
                                sourceFile,
                                line,
                                line,
                                null,
                                null,
                                target,
                                SourceLocation.inferLanguage(sourceFile),
                                null);
                if (highlightRanges.isEmpty()) {
                    highlightRanges.add(new HighlightRange(line, line, null, null, "issue"));
                }
            }
            return this;
        }

        /**
         * Sets the primary location directly from a fully constructed {@link SourceLocation}.
         *
         * <p>Also updates {@code sourceFile} and {@code line} from the location so that the
         * flat fields on the produced {@link Finding} remain consistent.
         *
         * @param primaryLocation the rich location; may be null to clear a previously set value
         */
        public Builder sourceLocation(SourceLocation primaryLocation) {
            this.primaryLocation = primaryLocation;
            if (primaryLocation != null) {
                this.sourceFile = primaryLocation.filePath();
                this.line = primaryLocation.startLine();
            }
            return this;
        }

        /**
         * Appends a highlight range to the list of ranges that should be visually marked in
         * the UI. Null values are silently ignored.
         */
        public Builder highlightRange(HighlightRange range) {
            if (range != null) {
                highlightRanges.add(range);
            }
            return this;
        }

        /**
         * Replaces the current occurrence list with the provided list.
         *
         * <p>Use this for multi-site findings such as a secret duplicated across several
         * profiles. Null is treated as an empty list; the existing occurrences are cleared
         * before adding the new ones.
         *
         * @param occurrences the complete set of occurrence sites; may be null
         */
        public Builder occurrences(List<FindingOccurrence> occurrences) {
            this.occurrences.clear();
            if (occurrences != null) {
                this.occurrences.addAll(occurrences);
            }
            return this;
        }

        /**
         * Appends a single occurrence site to the occurrence list. Null values are silently
         * ignored.
         */
        public Builder addOccurrence(FindingOccurrence occurrence) {
            if (occurrence != null) {
                this.occurrences.add(occurrence);
            }
            return this;
        }

        /**
         * Sets the target symbol — the property key, method name, class name, or other
         * identifier that is the subject of this finding. If the primary location does not
         * already carry a {@link SourceLocation#symbol()} when {@link #build()} is called,
         * this value is promoted to that field.
         */
        public Builder target(String target) {
            this.target = target;
            return this;
        }

        /**
         * Sets the human-readable location string displayed in the findings table, such as
         * {@code "application.properties:12"} or a logical path like
         * {@code "src/main/resources/application.yml"}.
         *
         * <p>Falls back to {@code sourceFile} when not explicitly set.
         */
        public Builder location(String location) {
            this.location = location;
            return this;
        }

        /**
         * Constructs the immutable {@link Finding}.
         *
         * <p>If the primary location is present but has no {@link SourceLocation#symbol()},
         * and {@code target} is non-blank, a new {@link SourceLocation} is created with the
         * target promoted to the symbol field. The effective {@code location} falls back to
         * {@code sourceFile} when not explicitly set via {@link #location(String)}.
         *
         * @return the fully assembled, immutable {@link Finding}
         */
        public Finding build() {
            String effectiveLocation = location != null ? location : sourceFile;
            SourceLocation effectivePrimaryLocation = primaryLocation;
            if (effectivePrimaryLocation != null
                    && (effectivePrimaryLocation.symbol() == null
                            || effectivePrimaryLocation.symbol().isBlank())
                    && target != null
                    && !target.isBlank()) {
                effectivePrimaryLocation =
                        new SourceLocation(
                                effectivePrimaryLocation.filePath(),
                                effectivePrimaryLocation.startLine(),
                                effectivePrimaryLocation.endLine(),
                                effectivePrimaryLocation.startColumn(),
                                effectivePrimaryLocation.endColumn(),
                                target,
                                effectivePrimaryLocation.language(),
                                effectivePrimaryLocation.githubUrl());
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
                    List.of());
        }
    }
}
