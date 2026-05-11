package com.robbanhoglund.springbootanalyzer.suppression;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A single entry in the {@code .analyzer-suppress.yml} suppression file. {@code ruleId} is the
 * stable identifier of the rule to suppress (e.g. {@code SPRING_FIELD_INJECTION}). {@code reason}
 * is an optional human-readable explanation that is preserved for auditability.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SuppressionEntry(String ruleId, String reason) {}
