package com.robbanhoglund.springbootanalyzer.analyzer.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class FindingRulesContractTest {

    @Test
    void catalogContains201UniqueStableRuleIds() {
        List<FindingRule> rules = catalogRules();

        assertThat(rules).hasSize(201);
        assertThat(rules).extracting(FindingRule::ruleId).doesNotHaveDuplicates();
        assertThat(rules)
                .extracting(FindingRule::ruleId)
                .contains("CONFIG_UNKNOWN_PROPERTY", "CONFIG_CODE_REFERENCE_MISSING");
    }

    @Test
    void ruleBasedFactoryUsesCatalogDefaultSeverityForEveryRule() {
        assertThat(catalogRules())
                .allSatisfy(
                        rule ->
                                assertThat(
                                                FindingFactory.builder(rule, FindingConfidence.HIGH)
                                                        .build()
                                                        .severity())
                                        .as(rule.ruleId())
                                        .isEqualTo(rule.defaultSeverity()));
    }

    private static List<FindingRule> catalogRules() {
        return Arrays.stream(FindingRules.class.getDeclaredFields())
                .filter(field -> Modifier.isPublic(field.getModifiers()))
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .filter(field -> FindingRule.class.equals(field.getType()))
                .map(FindingRulesContractTest::readRule)
                .toList();
    }

    private static FindingRule readRule(Field field) {
        try {
            return (FindingRule) field.get(null);
        } catch (IllegalAccessException exception) {
            throw new AssertionError(exception);
        }
    }
}
