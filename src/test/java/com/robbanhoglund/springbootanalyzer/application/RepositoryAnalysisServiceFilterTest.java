package com.robbanhoglund.springbootanalyzer.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingConfidence;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingFactory;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingRules;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingSeverity;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RepositoryAnalysisServiceFilterTest {

    @Test
    void enabledKnownRuleIsNotDroppedBySeverityFallback() {
        Finding finding =
                FindingFactory.builder(
                                FindingRules.SPRING_REACTIVE_API_IN_SERVLET_APP,
                                FindingConfidence.HIGH)
                        .build();

        assertThat(RepositoryAnalysisService.isNotDisabled(finding, Set.of(), Set.of("WARNING")))
                .isTrue();
    }

    @Test
    void severityFallbackStillAppliesToFindingWithoutRuleId() {
        Finding finding =
                new Finding(
                        FindingSeverity.WARNING,
                        "Structural warning",
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
                        null,
                        null,
                        null,
                        null,
                        null,
                        java.util.List.of(),
                        java.util.List.of(),
                        java.util.List.of());

        assertThat(RepositoryAnalysisService.isNotDisabled(finding, Set.of(), Set.of("WARNING")))
                .isFalse();
    }

    @Test
    void explicitlyDisabledKnownRuleIsDropped() {
        Finding finding =
                FindingFactory.builder(
                                FindingRules.SPRING_REACTIVE_API_IN_SERVLET_APP,
                                FindingConfidence.HIGH)
                        .build();

        assertThat(
                        RepositoryAnalysisService.isNotDisabled(
                                finding,
                                Set.of(FindingRules.SPRING_REACTIVE_API_IN_SERVLET_APP.ruleId()),
                                Set.of()))
                .isFalse();
    }
}
