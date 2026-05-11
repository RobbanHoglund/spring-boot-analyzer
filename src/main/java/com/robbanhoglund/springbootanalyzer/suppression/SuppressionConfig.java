package com.robbanhoglund.springbootanalyzer.suppression;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Root of the {@code .analyzer-suppress.yml} file found in the root of an analyzed repository.
 *
 * <p>Example file:
 *
 * <pre>{@code
 * suppress:
 *   - ruleId: SPRING_FIELD_INJECTION
 *     reason: "Legacy code — tracked for refactor in Q3"
 *   - ruleId: SPRING_JPA_OPEN_IN_VIEW
 *     reason: "Intentional: lazy loading required outside service layer"
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SuppressionConfig(List<SuppressionEntry> suppress) {

    public static SuppressionConfig empty() {
        return new SuppressionConfig(List.of());
    }

    /** Returns an effective list, never {@code null}. */
    public List<SuppressionEntry> suppress() {
        return suppress == null ? List.of() : suppress;
    }
}
