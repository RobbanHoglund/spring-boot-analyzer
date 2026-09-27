package com.robbanhoglund.springbootanalyzer.analyzer.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class FindingRulesContractTest {

    @Test
    void catalogContains225UniqueStableRuleIds() {
        List<FindingRule> rules = catalogRules();

        assertThat(rules).hasSize(225);
        assertThat(rules).extracting(FindingRule::ruleId).doesNotHaveDuplicates();
        assertThat(rules)
                .extracting(FindingRule::ruleId)
                .contains("CONFIG_UNKNOWN_PROPERTY", "CONFIG_CODE_REFERENCE_MISSING");
        assertThat(rules)
                .extracting(FindingRule::ruleId)
                .contains(
                        "SPRING_SECURITY_FILTER_CHAIN_NO_AUTHORIZATION",
                        "SPRING_SECURITY_AUTHORIZATION_RULE_INVALID",
                        "SPRING_SECURITY_FILTER_CHAIN_UNREACHABLE",
                        "SPRING_CACHEABLE_WITHOUT_ENABLE_CACHING",
                        "SPRING_CACHE_ANNOTATION_CONFLICTING_ATTRIBUTES",
                        "SPRING_CACHEABLE_CONDITION_USES_RESULT",
                        "SPRING_ENABLE_ANNOTATION_ON_NON_BEAN_CLASS",
                        "SPRING_BEAN_METHOD_INVALID",
                        "SPRING_EVENT_LISTENER_INVALID_SIGNATURE",
                        "SPRING_CONFIGURATION_PROPERTIES_INVALID_PREFIX",
                        "SPRING_CONFIGURATION_PROPERTIES_BEAN_CONSTRUCTOR_BINDING",
                        "SPRING_OPTIONAL_PRIMITIVE_REQUEST_PARAMETER",
                        "SPRING_MULTIPLE_REQUEST_BODY",
                        "SPRING_AMBIGUOUS_HANDLER_MAPPING",
                        "SPRING_TX_EVENT_LISTENER_NO_TRANSACTION",
                        "SPRING_TRANSACTIONAL_SYNCHRONIZED",
                        "SPRING_TASK_EXECUTOR_MAX_POOL_IGNORED",
                        "SPRING_FLYWAY_MIGRATION_NAME_IGNORED",
                        "SPRING_QUERY_DML_WITHOUT_MODIFYING",
                        "SPRING_JPA_ENUM_ORDINAL",
                        "SPRING_SCOPED_BEAN_WITHOUT_PROXY",
                        "SPRING_BFPP_BEAN_METHOD_NOT_STATIC",
                        "SPRING_PARAMETER_NAMES_NOT_RETAINED",
                        "SPRING_JACKSON2_OBJECTMAPPER_IGNORED_FOR_HTTP",
                        "SPRING_BOOT4_TEST_CLIENT_NOT_AUTOCONFIGURED",
                        "SPRING_REMOVED_CONFIGURATION_PROPERTY");
        // Retired because their premise was wrong: @Scheduled invokes the method through the
        // transactional proxy, and RestTemplate's default error handler already throws with the
        // full status, headers and body.
        assertThat(rules)
                .extracting(FindingRule::ruleId)
                .doesNotContain(
                        "SPRING_TRANSACTIONAL_ON_SCHEDULED",
                        "SPRING_RESTTEMPLATE_NO_HTTP_STATUS_HANDLER");
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
