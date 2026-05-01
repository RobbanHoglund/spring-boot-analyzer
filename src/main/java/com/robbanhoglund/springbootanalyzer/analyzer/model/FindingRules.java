package com.robbanhoglund.springbootanalyzer.analyzer.model;

public final class FindingRules {

    public static final FindingRule SPRING_SECRET_LITERAL = rule(
            "SPRING_SECRET_LITERAL",
            "Sensitive property uses a literal value",
            FindingSeverity.WARNING,
            FindingCategory.SECURITY,
            FindingRuntimeDetection.NOT_NORMALLY_DETECTED
    );
    public static final FindingRule SPRING_SECRET_WEAK_PLACEHOLDER_DEFAULT = rule(
            "SPRING_SECRET_WEAK_PLACEHOLDER_DEFAULT",
            "Secret placeholder has a weak default value",
            FindingSeverity.WARNING,
            FindingCategory.SECURITY,
            FindingRuntimeDetection.NOT_NORMALLY_DETECTED
    );
    public static final FindingRule SPRING_SECRET_MULTI_PROFILE = rule(
            "SPRING_SECRET_MULTI_PROFILE",
            "Sensitive property is configured in multiple profiles",
            FindingSeverity.INFO,
            FindingCategory.PROFILE_DRIFT,
            FindingRuntimeDetection.ACTIVE_PROFILE_RUNTIME_MAY_DETECT
    );
    public static final FindingRule SPRING_RISKY_PROD_CONFIG = rule(
            "SPRING_RISKY_PROD_CONFIG",
            "Risky production configuration detected",
            FindingSeverity.WARNING,
            FindingCategory.CONFIGURATION,
            FindingRuntimeDetection.ACTIVE_PROFILE_RUNTIME_MAY_DETECT
    );
    public static final FindingRule SPRING_PROFILE_DRIFT = rule(
            "SPRING_PROFILE_DRIFT",
            "Configuration differs across profiles",
            FindingSeverity.INFO,
            FindingCategory.PROFILE_DRIFT,
            FindingRuntimeDetection.ACTIVE_PROFILE_RUNTIME_MAY_DETECT
    );
    public static final FindingRule SPRING_STARTUP_SIDE_EFFECT = rule(
            "SPRING_STARTUP_SIDE_EFFECT",
            "Startup hook performs side effects",
            FindingSeverity.WARNING,
            FindingCategory.STARTUP,
            FindingRuntimeDetection.NOT_NORMALLY_DETECTED
    );
    public static final FindingRule SPRING_SCHEDULED_SIDE_EFFECT = rule(
            "SPRING_SCHEDULED_SIDE_EFFECT",
            "Scheduled job performs side effects",
            FindingSeverity.WARNING,
            FindingCategory.SCHEDULING,
            FindingRuntimeDetection.NOT_NORMALLY_DETECTED
    );
    public static final FindingRule SPRING_SCHEDULED_SHORT_INTERVAL = rule(
            "SPRING_SCHEDULED_SHORT_INTERVAL",
            "Scheduled job runs on a short interval",
            FindingSeverity.INFO,
            FindingCategory.SCHEDULING,
            FindingRuntimeDetection.NOT_NORMALLY_DETECTED
    );
    public static final FindingRule SPRING_SCHEDULED_CRON_NO_ZONE = rule(
            "SPRING_SCHEDULED_CRON_NO_ZONE",
            "Scheduled cron has no explicit time zone",
            FindingSeverity.INFO,
            FindingCategory.SCHEDULING,
            FindingRuntimeDetection.NOT_NORMALLY_DETECTED
    );
    public static final FindingRule SPRING_HTTP_CLIENT_NO_TIMEOUT = rule(
            "SPRING_HTTP_CLIENT_NO_TIMEOUT",
            "HTTP client has no visible timeout configuration",
            FindingSeverity.WARNING,
            FindingCategory.HTTP,
            FindingRuntimeDetection.NOT_NORMALLY_DETECTED
    );
    public static final FindingRule SPRING_HTTP_PLAIN_URL = rule(
            "SPRING_HTTP_PLAIN_URL",
            "External service URL uses plain HTTP",
            FindingSeverity.WARNING,
            FindingCategory.HTTP,
            FindingRuntimeDetection.NOT_NORMALLY_DETECTED
    );
    public static final FindingRule SPRING_HTTP_CLIENT_NO_RESILIENCE = rule(
            "SPRING_HTTP_CLIENT_NO_RESILIENCE",
            "HTTP client has no visible retry or circuit-breaker handling",
            FindingSeverity.INFO,
            FindingCategory.HTTP,
            FindingRuntimeDetection.NOT_NORMALLY_DETECTED
    );
    public static final FindingRule SPRING_CONDITIONAL_VALUE_MISMATCH = rule(
            "SPRING_CONDITIONAL_VALUE_MISMATCH",
            "Configured provider value does not match any conditional bean",
            FindingSeverity.WARNING,
            FindingCategory.CONDITIONAL_BEAN,
            FindingRuntimeDetection.ACTIVE_PROFILE_RUNTIME_MAY_DETECT
    );
    public static final FindingRule SPRING_CONDITIONAL_MATCH_IF_MISSING_OVERLAP = rule(
            "SPRING_CONDITIONAL_MATCH_IF_MISSING_OVERLAP",
            "Multiple conditional beans default to matchIfMissing",
            FindingSeverity.WARNING,
            FindingCategory.CONDITIONAL_BEAN,
            FindingRuntimeDetection.ACTIVE_PROFILE_RUNTIME_MAY_DETECT
    );
    public static final FindingRule SPRING_FLYWAY_DDL_AUTO_MIX = rule(
            "SPRING_FLYWAY_DDL_AUTO_MIX",
            "Flyway is combined with schema-mutating Hibernate DDL",
            FindingSeverity.WARNING,
            FindingCategory.PERSISTENCE,
            FindingRuntimeDetection.NOT_NORMALLY_DETECTED
    );
    public static final FindingRule SPRING_FLYWAY_MISSING_MIGRATIONS = rule(
            "SPRING_FLYWAY_MISSING_MIGRATIONS",
            "Flyway is enabled but migration files were not found",
            FindingSeverity.WARNING,
            FindingCategory.PERSISTENCE,
            FindingRuntimeDetection.NOT_NORMALLY_DETECTED
    );
    public static final FindingRule SPRING_TRANSACTION_SELF_INVOCATION = rule(
            "SPRING_TRANSACTION_SELF_INVOCATION",
            "@Transactional method is called through self-invocation",
            FindingSeverity.INFO,
            FindingCategory.TRANSACTION,
            FindingRuntimeDetection.NOT_NORMALLY_DETECTED
    );
    public static final FindingRule SPRING_TRANSACTION_PRIVATE_METHOD = rule(
            "SPRING_TRANSACTION_PRIVATE_METHOD",
            "@Transactional method may not be proxied",
            FindingSeverity.INFO,
            FindingCategory.TRANSACTION,
            FindingRuntimeDetection.NOT_NORMALLY_DETECTED
    );
    public static final FindingRule SPRING_TRANSACTION_MISSING_BOUNDARY = rule(
            "SPRING_TRANSACTION_MISSING_BOUNDARY",
            "Write-heavy service method has no visible transaction boundary",
            FindingSeverity.INFO,
            FindingCategory.TRANSACTION,
            FindingRuntimeDetection.NOT_NORMALLY_DETECTED
    );
    public static final FindingRule SPRING_SIDE_EFFECT_ORCHESTRATION_NO_BOUNDARY = rule(
            "SPRING_SIDE_EFFECT_ORCHESTRATION_NO_BOUNDARY",
            "Potential side-effect orchestration without explicit consistency boundary",
            FindingSeverity.INFO,
            FindingCategory.MAINTAINABILITY,
            FindingRuntimeDetection.NOT_NORMALLY_DETECTED
    );
    public static final FindingRule JAVA_EMPTY_CATCH_BLOCK = rule(
            "JAVA_EMPTY_CATCH_BLOCK",
            "Empty catch block",
            FindingSeverity.WARNING,
            FindingCategory.EXCEPTION_HANDLING,
            FindingRuntimeDetection.NOT_NORMALLY_DETECTED
    );
    public static final FindingRule SPRING_SWALLOWED_EXCEPTION_FALLBACK = rule(
            "SPRING_SWALLOWED_EXCEPTION_FALLBACK",
            "Exception is swallowed and replaced with fallback",
            FindingSeverity.INFO,
            FindingCategory.EXCEPTION_HANDLING,
            FindingRuntimeDetection.NOT_NORMALLY_DETECTED
    );
    public static final FindingRule SPRING_REPEATED_FALLBACK_PARSING_PATTERN = rule(
            "SPRING_REPEATED_FALLBACK_PARSING_PATTERN",
            "Repeated fallback parsing pattern",
            FindingSeverity.INFO,
            FindingCategory.MAINTAINABILITY,
            FindingRuntimeDetection.NOT_NORMALLY_DETECTED
    );
    public static final FindingRule SPRING_INTERRUPTED_EXCEPTION_SWALLOWED = rule(
            "SPRING_INTERRUPTED_EXCEPTION_SWALLOWED",
            "InterruptedException is swallowed",
            FindingSeverity.WARNING,
            FindingCategory.EXCEPTION_HANDLING,
            FindingRuntimeDetection.NOT_NORMALLY_DETECTED
    );
    public static final FindingRule SPRING_BROAD_FATAL_ERROR_CATCH = rule(
            "SPRING_BROAD_FATAL_ERROR_CATCH",
            "Broad fatal error catch",
            FindingSeverity.WARNING,
            FindingCategory.EXCEPTION_HANDLING,
            FindingRuntimeDetection.NOT_NORMALLY_DETECTED
    );
    public static final FindingRule SPRING_BROAD_EXCEPTION_SPRING_BOUNDARY = rule(
            "SPRING_BROAD_EXCEPTION_SPRING_BOUNDARY",
            "Broad exception catch in Spring boundary",
            FindingSeverity.INFO,
            FindingCategory.EXCEPTION_HANDLING,
            FindingRuntimeDetection.NOT_NORMALLY_DETECTED
    );
    public static final FindingRule SPRING_PRINT_STACK_TRACE = rule(
            "SPRING_PRINT_STACK_TRACE",
            "printStackTrace used instead of application logging",
            FindingSeverity.INFO,
            FindingCategory.EXCEPTION_HANDLING,
            FindingRuntimeDetection.NOT_NORMALLY_DETECTED
    );
    public static final FindingRule SPRING_RAW_EXCEPTION_MESSAGE_HTTP = rule(
            "SPRING_RAW_EXCEPTION_MESSAGE_HTTP",
            "Raw exception message exposed through HTTP response",
            FindingSeverity.WARNING,
            FindingCategory.SECURITY,
            FindingRuntimeDetection.NOT_NORMALLY_DETECTED
    );
    public static final FindingRule SPRING_BROAD_EXCEPTION_HANDLER = rule(
            "SPRING_BROAD_EXCEPTION_HANDLER",
            "Broad Spring exception handler",
            FindingSeverity.INFO,
            FindingCategory.EXCEPTION_HANDLING,
            FindingRuntimeDetection.NOT_NORMALLY_DETECTED
    );
    public static final FindingRule SPRING_REQUEST_BODY_NO_VALID = rule(
            "SPRING_REQUEST_BODY_NO_VALID",
            "@RequestBody is missing @Valid",
            FindingSeverity.INFO,
            FindingCategory.VALIDATION,
            FindingRuntimeDetection.NOT_NORMALLY_DETECTED
    );

    private FindingRules() {
    }

    private static FindingRule rule(
            String ruleId,
            String title,
            FindingSeverity severity,
            FindingCategory category,
            FindingRuntimeDetection runtimeDetection
    ) {
        return new FindingRule(ruleId, title, severity, category, runtimeDetection);
    }
}
