package com.robbanhoglund.springbootanalyzer.analyzer.model;

/**
 * Catalogue of all static-analysis rule definitions used by this tool.
 *
 * <p>Each constant is a {@link FindingRule} that bundles a stable rule ID, a human-readable
 * title, a default severity, the finding category, and a hint about whether the issue would
 * normally surface during ordinary runtime operation. Analyzers create {@link Finding} instances
 * by passing one of these constants to {@link FindingFactory#builder(FindingRule, FindingConfidence)}.
 *
 * <p>Rule IDs are stable identifiers; do not rename them without a migration step because
 * clients may suppress specific rules by ID.
 */
public final class FindingRules {

    /** A sensitive property (password, secret, token, …) is set to a plain-text literal value
     *  rather than referencing an environment variable or secret-management placeholder. */
    public static final FindingRule SPRING_SECRET_LITERAL =
            rule(
                    "SPRING_SECRET_LITERAL",
                    "Sensitive property uses a literal value",
                    FindingSeverity.WARNING,
                    FindingCategory.SECURITY,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A sensitive property uses a {@code ${…:defaultValue}} placeholder where the default
     *  value itself looks like a real secret (non-blank, non-CHANGEME, …). */
    public static final FindingRule SPRING_SECRET_WEAK_PLACEHOLDER_DEFAULT =
            rule(
                    "SPRING_SECRET_WEAK_PLACEHOLDER_DEFAULT",
                    "Secret placeholder has a weak default value",
                    FindingSeverity.WARNING,
                    FindingCategory.SECURITY,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** The same sensitive property key appears in two or more profile-specific configuration
     *  files, making consistent secret rotation harder and increasing the risk of stale values. */
    public static final FindingRule SPRING_SECRET_MULTI_PROFILE =
            rule(
                    "SPRING_SECRET_MULTI_PROFILE",
                    "Sensitive property is configured in multiple profiles",
                    FindingSeverity.INFO,
                    FindingCategory.PROFILE_DRIFT,
                    FindingRuntimeDetection.ACTIVE_PROFILE_RUNTIME_MAY_DETECT);

    /** A configuration property in a production-oriented profile has a value that carries
     *  known operational risk (e.g. {@code debug=true}, {@code server.error.include-stacktrace=always},
     *  {@code springdoc.swagger-ui.enabled=true}). */
    public static final FindingRule SPRING_RISKY_PROD_CONFIG =
            rule(
                    "SPRING_RISKY_PROD_CONFIG",
                    "Risky production configuration detected",
                    FindingSeverity.WARNING,
                    FindingCategory.CONFIGURATION,
                    FindingRuntimeDetection.ACTIVE_PROFILE_RUNTIME_MAY_DETECT);

    /** A structurally significant property (URL, endpoint, provider, security setting) has
     *  different values across two or more Spring profiles, which can cause surprising behaviour
     *  when a non-default profile is activated. */
    public static final FindingRule SPRING_PROFILE_DRIFT =
            rule(
                    "SPRING_PROFILE_DRIFT",
                    "Configuration differs across profiles",
                    FindingSeverity.INFO,
                    FindingCategory.PROFILE_DRIFT,
                    FindingRuntimeDetection.ACTIVE_PROFILE_RUNTIME_MAY_DETECT);

    /** A {@code @PostConstruct}, {@code ApplicationRunner}, or {@code CommandLineRunner} method
     *  performs outbound I/O, state mutation, or other side effects that run unconditionally
     *  on every startup, including test contexts. */
    public static final FindingRule SPRING_STARTUP_SIDE_EFFECT =
            rule(
                    "SPRING_STARTUP_SIDE_EFFECT",
                    "Startup hook performs side effects",
                    FindingSeverity.WARNING,
                    FindingCategory.STARTUP,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A {@code @Scheduled} method contains write operations or outbound calls that execute
     *  repeatedly and unconditionally. Combined with a missing transaction boundary this can
     *  cause data inconsistency or unintended external effects. */
    public static final FindingRule SPRING_SCHEDULED_SIDE_EFFECT =
            rule(
                    "SPRING_SCHEDULED_SIDE_EFFECT",
                    "Scheduled job performs side effects",
                    FindingSeverity.WARNING,
                    FindingCategory.SCHEDULING,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A {@code fixedRate} or {@code fixedDelay} scheduler is configured with an interval
     *  shorter than one minute, which may overwhelm downstream systems or the thread pool. */
    public static final FindingRule SPRING_SCHEDULED_SHORT_INTERVAL =
            rule(
                    "SPRING_SCHEDULED_SHORT_INTERVAL",
                    "Scheduled job runs on a short interval",
                    FindingSeverity.INFO,
                    FindingCategory.SCHEDULING,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A {@code @Scheduled(cron = …)} expression omits an explicit {@code zone} attribute,
     *  so the trigger fires according to the JVM default time zone, which may differ between
     *  environments and after DST transitions. */
    public static final FindingRule SPRING_SCHEDULED_CRON_NO_ZONE =
            rule(
                    "SPRING_SCHEDULED_CRON_NO_ZONE",
                    "Scheduled cron has no explicit time zone",
                    FindingSeverity.INFO,
                    FindingCategory.SCHEDULING,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A {@code RestTemplate}, {@code WebClient}, or {@code RestClient} bean has no visible
     *  read/connect timeout configured. Without a timeout, a slow or unresponsive upstream
     *  can hold threads indefinitely and cause thread-pool exhaustion. */
    public static final FindingRule SPRING_HTTP_CLIENT_NO_TIMEOUT =
            rule(
                    "SPRING_HTTP_CLIENT_NO_TIMEOUT",
                    "HTTP client has no visible timeout configuration",
                    FindingSeverity.WARNING,
                    FindingCategory.HTTP,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A hard-coded or configured service URL uses the {@code http://} scheme, exposing traffic
     *  to interception. Should use {@code https://} except for localhost/internal loopback. */
    public static final FindingRule SPRING_HTTP_PLAIN_URL =
            rule(
                    "SPRING_HTTP_PLAIN_URL",
                    "External service URL uses plain HTTP",
                    FindingSeverity.WARNING,
                    FindingCategory.HTTP,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** An HTTP client declaration has no visible retry policy, circuit breaker, or fallback.
     *  A single transient failure propagates directly to the caller as an exception. */
    public static final FindingRule SPRING_HTTP_CLIENT_NO_RESILIENCE =
            rule(
                    "SPRING_HTTP_CLIENT_NO_RESILIENCE",
                    "HTTP client has no visible retry or circuit-breaker handling",
                    FindingSeverity.INFO,
                    FindingCategory.HTTP,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A property that controls a {@code @ConditionalOnProperty} selection has a configured
     *  value that does not match any of the {@code havingValue} strings declared in source,
     *  so the application may silently activate an unexpected or no bean. */
    public static final FindingRule SPRING_CONDITIONAL_VALUE_MISMATCH =
            rule(
                    "SPRING_CONDITIONAL_VALUE_MISMATCH",
                    "Configured provider value does not match any conditional bean",
                    FindingSeverity.WARNING,
                    FindingCategory.CONDITIONAL_BEAN,
                    FindingRuntimeDetection.ACTIVE_PROFILE_RUNTIME_MAY_DETECT);

    /** Two or more {@code @ConditionalOnProperty} beans for the same property key set
     *  {@code matchIfMissing = true}, making the active bean depend on classpath ordering
     *  when the controlling property is absent. */
    public static final FindingRule SPRING_CONDITIONAL_MATCH_IF_MISSING_OVERLAP =
            rule(
                    "SPRING_CONDITIONAL_MATCH_IF_MISSING_OVERLAP",
                    "Multiple conditional beans default to matchIfMissing",
                    FindingSeverity.WARNING,
                    FindingCategory.CONDITIONAL_BEAN,
                    FindingRuntimeDetection.ACTIVE_PROFILE_RUNTIME_MAY_DETECT);

    /** Flyway (migration-based schema management) is active at the same time as a
     *  Hibernate {@code ddl-auto} setting that mutates the schema ({@code update}, {@code create},
     *  {@code create-drop}, {@code drop}), creating two competing schema authorities. */
    public static final FindingRule SPRING_FLYWAY_DDL_AUTO_MIX =
            rule(
                    "SPRING_FLYWAY_DDL_AUTO_MIX",
                    "Flyway is combined with schema-mutating Hibernate DDL",
                    FindingSeverity.WARNING,
                    FindingCategory.PERSISTENCE,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** Flyway is present on the classpath or explicitly enabled in configuration, but no
     *  {@code V__*.sql} migration files were found under {@code src/main/resources/db/migration}. */
    public static final FindingRule SPRING_FLYWAY_MISSING_MIGRATIONS =
            rule(
                    "SPRING_FLYWAY_MISSING_MIGRATIONS",
                    "Flyway is enabled but migration files were not found",
                    FindingSeverity.WARNING,
                    FindingCategory.PERSISTENCE,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A {@code @Transactional} method is called via {@code this.method()} inside the same
     *  class. Spring's proxy-based AOP intercepts the outer call but not internal
     *  {@code this}-invocations, so the transaction annotation has no effect. */
    public static final FindingRule SPRING_TRANSACTION_SELF_INVOCATION =
            rule(
                    "SPRING_TRANSACTION_SELF_INVOCATION",
                    "@Transactional method is called through self-invocation",
                    FindingSeverity.INFO,
                    FindingCategory.TRANSACTION,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A {@code @Transactional} annotation appears on a {@code private} method.
     *  Spring's proxy-based AOP cannot intercept non-overridable methods, so the
     *  transaction will not be started or committed by the proxy. */
    public static final FindingRule SPRING_TRANSACTION_PRIVATE_METHOD =
            rule(
                    "SPRING_TRANSACTION_PRIVATE_METHOD",
                    "@Transactional method may not be proxied",
                    FindingSeverity.INFO,
                    FindingCategory.TRANSACTION,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A service method performs write-like operations ({@code save}, {@code delete},
     *  {@code update}, …) but has no {@code @Transactional} annotation and does not
     *  appear to delegate to a transactional method. */
    public static final FindingRule SPRING_TRANSACTION_MISSING_BOUNDARY =
            rule(
                    "SPRING_TRANSACTION_MISSING_BOUNDARY",
                    "Write-heavy service method has no visible transaction boundary",
                    FindingSeverity.INFO,
                    FindingCategory.TRANSACTION,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A method calls two or more different external services or performs mixed I/O without
     *  an explicit consistency or compensation boundary, making partial-failure scenarios
     *  harder to reason about. */
    public static final FindingRule SPRING_SIDE_EFFECT_ORCHESTRATION_NO_BOUNDARY =
            rule(
                    "SPRING_SIDE_EFFECT_ORCHESTRATION_NO_BOUNDARY",
                    "Potential side-effect orchestration without explicit consistency boundary",
                    FindingSeverity.INFO,
                    FindingCategory.MAINTAINABILITY,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A {@code catch} block is completely empty — no logging, no re-throw, no handling —
     *  silently discarding the exception and any context it carries. */
    public static final FindingRule JAVA_EMPTY_CATCH_BLOCK =
            rule(
                    "JAVA_EMPTY_CATCH_BLOCK",
                    "Empty catch block",
                    FindingSeverity.WARNING,
                    FindingCategory.EXCEPTION_HANDLING,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** An exception is caught and replaced by a fallback return value or default assignment
     *  without being logged or rethrown. The original failure is invisible to operators. */
    public static final FindingRule SPRING_SWALLOWED_EXCEPTION_FALLBACK =
            rule(
                    "SPRING_SWALLOWED_EXCEPTION_FALLBACK",
                    "Exception is swallowed and replaced with fallback",
                    FindingSeverity.INFO,
                    FindingCategory.EXCEPTION_HANDLING,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** The same try-parse-then-fallback pattern appears in three or more places in the
     *  same file, suggesting the logic should be extracted into a shared utility. */
    public static final FindingRule SPRING_REPEATED_FALLBACK_PARSING_PATTERN =
            rule(
                    "SPRING_REPEATED_FALLBACK_PARSING_PATTERN",
                    "Repeated fallback parsing pattern",
                    FindingSeverity.INFO,
                    FindingCategory.MAINTAINABILITY,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** {@code InterruptedException} is caught and swallowed without restoring the thread's
     *  interrupted status. This breaks cooperative thread cancellation and can prevent the
     *  JVM from shutting down cleanly. */
    public static final FindingRule SPRING_INTERRUPTED_EXCEPTION_SWALLOWED =
            rule(
                    "SPRING_INTERRUPTED_EXCEPTION_SWALLOWED",
                    "InterruptedException is swallowed",
                    FindingSeverity.WARNING,
                    FindingCategory.EXCEPTION_HANDLING,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A {@code catch} clause catches {@code Error} or {@code Throwable}, masking JVM-level
     *  fatal errors ({@code OutOfMemoryError}, {@code StackOverflowError}, …) that should
     *  normally terminate the process. */
    public static final FindingRule SPRING_BROAD_FATAL_ERROR_CATCH =
            rule(
                    "SPRING_BROAD_FATAL_ERROR_CATCH",
                    "Broad fatal error catch",
                    FindingSeverity.WARNING,
                    FindingCategory.EXCEPTION_HANDLING,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A Spring boundary method (controller, listener, scheduled job) catches
     *  {@code Exception} or broader without re-throwing or converting to a
     *  structured error response. Framework error handling is bypassed. */
    public static final FindingRule SPRING_BROAD_EXCEPTION_SPRING_BOUNDARY =
            rule(
                    "SPRING_BROAD_EXCEPTION_SPRING_BOUNDARY",
                    "Broad exception catch in Spring boundary",
                    FindingSeverity.INFO,
                    FindingCategory.EXCEPTION_HANDLING,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** {@code Throwable.printStackTrace()} is called instead of routing the exception
     *  through the application's logging framework, so stack traces bypass log level
     *  controls, structured formats, and aggregation pipelines. */
    public static final FindingRule SPRING_PRINT_STACK_TRACE =
            rule(
                    "SPRING_PRINT_STACK_TRACE",
                    "printStackTrace used instead of application logging",
                    FindingSeverity.INFO,
                    FindingCategory.EXCEPTION_HANDLING,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A controller method returns {@code exception.getMessage()} directly in the HTTP
     *  response body, leaking internal implementation details (class names, stack frames,
     *  dependency versions) to external callers. */
    public static final FindingRule SPRING_RAW_EXCEPTION_MESSAGE_HTTP =
            rule(
                    "SPRING_RAW_EXCEPTION_MESSAGE_HTTP",
                    "Raw exception message exposed through HTTP response",
                    FindingSeverity.WARNING,
                    FindingCategory.SECURITY,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A {@code @ControllerAdvice} or {@code @ExceptionHandler} method catches
     *  {@code Exception} or broader and returns a generic response, suppressing
     *  more specific handlers that might be registered lower in the chain. */
    public static final FindingRule SPRING_BROAD_EXCEPTION_HANDLER =
            rule(
                    "SPRING_BROAD_EXCEPTION_HANDLER",
                    "Broad Spring exception handler",
                    FindingSeverity.INFO,
                    FindingCategory.EXCEPTION_HANDLING,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A controller method accepts a {@code @RequestBody} parameter without
     *  {@code @Valid}, so Bean Validation constraints on the request object are
     *  silently ignored and invalid input reaches the service layer. */
    public static final FindingRule SPRING_REQUEST_BODY_NO_VALID =
            rule(
                    "SPRING_REQUEST_BODY_NO_VALID",
                    "@RequestBody is missing @Valid",
                    FindingSeverity.INFO,
                    FindingCategory.VALIDATION,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A controller method accepts a {@code @ModelAttribute} parameter without
     *  {@code @Valid}. Same consequence as {@link #SPRING_REQUEST_BODY_NO_VALID}. */
    public static final FindingRule SPRING_MODEL_ATTRIBUTE_NO_VALID =
            rule(
                    "SPRING_MODEL_ATTRIBUTE_NO_VALID",
                    "@ModelAttribute is missing @Valid",
                    FindingSeverity.INFO,
                    FindingCategory.VALIDATION,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** {@code @Async} is placed on a {@code private} method. Spring's proxy-based AOP
     *  cannot intercept non-overridable methods, so the method runs synchronously
     *  on the calling thread despite the annotation. */
    public static final FindingRule SPRING_ASYNC_PROXY_BYPASS =
            rule(
                    "SPRING_ASYNC_PROXY_BYPASS",
                    "@Async on private method will not be intercepted by proxy",
                    FindingSeverity.WARNING,
                    FindingCategory.MAINTAINABILITY,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** An {@code @Async void} method has no try/catch and no
     *  {@code AsyncUncaughtExceptionHandler} configured. Exceptions thrown asynchronously
     *  are silently discarded by the default executor. */
    public static final FindingRule SPRING_ASYNC_VOID_SWALLOWED_EXCEPTION =
            rule(
                    "SPRING_ASYNC_VOID_SWALLOWED_EXCEPTION",
                    "@Async void method has no exception handling",
                    FindingSeverity.INFO,
                    FindingCategory.EXCEPTION_HANDLING,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A {@code @KafkaListener}, {@code @RabbitListener}, {@code @JmsListener}, or
     *  {@code @SqsListener} method has no visible try/catch or error-channel configuration.
     *  A poison message or processing failure may cause the consumer to stop silently. */
    public static final FindingRule SPRING_MESSAGING_LISTENER_NO_ERROR_HANDLER =
            rule(
                    "SPRING_MESSAGING_LISTENER_NO_ERROR_HANDLER",
                    "Messaging listener has no visible exception handling",
                    FindingSeverity.INFO,
                    FindingCategory.EXCEPTION_HANDLING,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** The project declares a web starter ({@code spring-boot-starter-web} or
     *  {@code spring-boot-starter-webflux}) but has no Spring Security dependency.
     *  All endpoints are publicly accessible by default. */
    public static final FindingRule SPRING_SECURITY_STARTER_MISSING =
            rule(
                    "SPRING_SECURITY_STARTER_MISSING",
                    "Web application has no Spring Security dependency",
                    FindingSeverity.INFO,
                    FindingCategory.SECURITY,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A {@code @ManyToOne} or {@code @OneToOne} relationship uses the JPA default fetch
     *  type ({@code EAGER}), which issues an extra SQL join on every parent load regardless
     *  of whether the association is needed. */
    public static final FindingRule SPRING_JPA_MANYTOONE_EAGER_DEFAULT =
            rule(
                    "SPRING_JPA_MANYTOONE_EAGER_DEFAULT",
                    "@ManyToOne or @OneToOne uses eager loading by default",
                    FindingSeverity.INFO,
                    FindingCategory.PERSISTENCE,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A {@code @OneToMany} or {@code @ManyToMany} relationship has no {@code mappedBy}
     *  attribute, making it the owning side and silently causing a join-table to be created
     *  or duplicate foreign-key columns to appear in the schema. */
    public static final FindingRule SPRING_JPA_ONETOMANY_MISSING_MAPPED_BY =
            rule(
                    "SPRING_JPA_ONETOMANY_MISSING_MAPPED_BY",
                    "@OneToMany or @ManyToMany has no mappedBy attribute",
                    FindingSeverity.WARNING,
                    FindingCategory.PERSISTENCE,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A {@code @Bean} method is declared in a class annotated with {@code @Component} or
     *  {@code @Service} instead of {@code @Configuration}. Spring processes these in "lite"
     *  mode, meaning inter-bean method calls are not proxied and singletons are not enforced. */
    public static final FindingRule SPRING_BEAN_ON_NON_CONFIGURATION =
            rule(
                    "SPRING_BEAN_ON_NON_CONFIGURATION",
                    "@Bean method in class without @Configuration uses lite mode",
                    FindingSeverity.WARNING,
                    FindingCategory.MAINTAINABILITY,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A Spring Data {@code @Modifying} query method has no enclosing {@code @Transactional}
     *  annotation and is not called from a transactional service method. Without a transaction,
     *  the modification may fail or be silently rolled back by some JPA providers. */
    public static final FindingRule SPRING_MODIFYING_NO_TRANSACTION =
            rule(
                    "SPRING_MODIFYING_NO_TRANSACTION",
                    "@Modifying query has no transaction boundary",
                    FindingSeverity.ERROR,
                    FindingCategory.PERSISTENCE,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** {@code spring.jpa.hibernate.ddl-auto} is set to {@code create} or {@code create-drop}
     *  in a production-oriented profile ({@code prod}, {@code production}, {@code staging}).
     *  This drops and recreates all tables on every startup, destroying all existing data. */
    public static final FindingRule SPRING_DDL_AUTO_DESTRUCTIVE_PROD =
            rule(
                    "SPRING_DDL_AUTO_DESTRUCTIVE_PROD",
                    "Destructive Hibernate DDL-auto setting in production-oriented profile",
                    FindingSeverity.ERROR,
                    FindingCategory.PERSISTENCE,
                    FindingRuntimeDetection.ACTIVE_PROFILE_RUNTIME_MAY_DETECT);

    /** {@code spring.jpa.show-sql=true} is set in a production-oriented profile.
     *  SQL logging bypasses the application logging framework, writes directly to stdout,
     *  and cannot be suppressed by log-level configuration. */
    public static final FindingRule SPRING_JPA_SHOW_SQL_PROD =
            rule(
                    "SPRING_JPA_SHOW_SQL_PROD",
                    "spring.jpa.show-sql=true in production-oriented profile",
                    FindingSeverity.WARNING,
                    FindingCategory.CONFIGURATION,
                    FindingRuntimeDetection.ACTIVE_PROFILE_RUNTIME_MAY_DETECT);

    /** {@code spring.jpa.open-in-view} is not explicitly set to {@code false}, so it defaults
     *  to {@code true}. This keeps the Hibernate session open through the entire HTTP request
     *  lifecycle, enabling lazy loading during serialization and masking N+1 query problems. */
    public static final FindingRule SPRING_JPA_OPEN_IN_VIEW =
            rule(
                    "SPRING_JPA_OPEN_IN_VIEW",
                    "Open-session-in-view is not explicitly disabled",
                    FindingSeverity.WARNING,
                    FindingCategory.PERSISTENCE,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** {@code @Transactional} and {@code @Scheduled} are placed on the same method.
     *  Scheduled methods run in a dedicated thread pool that does not participate in
     *  caller-supplied transaction contexts, so the transaction semantics are often
     *  unintentional or misunderstood. */
    public static final FindingRule SPRING_TRANSACTIONAL_ON_SCHEDULED =
            rule(
                    "SPRING_TRANSACTIONAL_ON_SCHEDULED",
                    "@Transactional and @Scheduled on the same method",
                    FindingSeverity.WARNING,
                    FindingCategory.TRANSACTION,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** CSRF protection is explicitly disabled in a Spring Security configuration.
     *  Disabling CSRF is only safe for stateless APIs that use token-based authentication
     *  and never rely on browser session cookies. */
    public static final FindingRule SPRING_CSRF_DISABLED =
            rule(
                    "SPRING_CSRF_DISABLED",
                    "CSRF protection is disabled",
                    FindingSeverity.WARNING,
                    FindingCategory.SECURITY,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A CORS configuration allows all origins ({@code allowedOrigins("*")} or
     *  {@code allowedOriginPatterns("*")}), potentially enabling cross-origin requests
     *  from untrusted domains including credential-carrying requests. */
    public static final FindingRule SPRING_CORS_ALLOW_ALL =
            rule(
                    "SPRING_CORS_ALLOW_ALL",
                    "CORS configuration allows all origins",
                    FindingSeverity.WARNING,
                    FindingCategory.SECURITY,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A sensitive value (password, token, API key, …) is passed as a URL query parameter
     *  or path variable, where it may be logged by proxies, CDNs, or the application itself
     *  as part of the request URL. */
    public static final FindingRule SPRING_REQUEST_PARAM_SENSITIVE_NAME =
            rule(
                    "SPRING_REQUEST_PARAM_SENSITIVE_NAME",
                    "Sensitive value passed as URL parameter or path variable",
                    FindingSeverity.WARNING,
                    FindingCategory.SECURITY,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A {@code @Value("${property}")} annotation has no default ({@code :}) and the
     *  application will throw {@code IllegalArgumentException} on startup if the property
     *  is absent from the environment. */
    public static final FindingRule SPRING_VALUE_NO_DEFAULT =
            rule(
                    "SPRING_VALUE_NO_DEFAULT",
                    "@Value property reference has no default value",
                    FindingSeverity.WARNING,
                    FindingCategory.CONFIGURATION,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** {@code @Autowired} is placed directly on a field instead of using constructor
     *  injection. Field injection bypasses the constructor, making the class harder to
     *  test, obscures dependencies, and prevents fields from being declared {@code final}. */
    public static final FindingRule SPRING_FIELD_INJECTION =
            rule(
                    "SPRING_FIELD_INJECTION",
                    "@Autowired field injection used instead of constructor injection",
                    FindingSeverity.INFO,
                    FindingCategory.MAINTAINABILITY,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A {@code @RequestMapping} annotation on a handler method omits the {@code method}
     *  attribute, so the endpoint accepts all HTTP verbs (GET, POST, PUT, DELETE, …).
     *  This widens the attack surface and contradicts REST conventions. */
    public static final FindingRule SPRING_REQUEST_MAPPING_NO_METHOD =
            rule(
                    "SPRING_REQUEST_MAPPING_NO_METHOD",
                    "@RequestMapping method has no HTTP method constraint",
                    FindingSeverity.INFO,
                    FindingCategory.API_SURFACE,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A {@code @ConfigurationProperties} class is missing {@code @Validated}.
     *  Without it, Bean Validation annotations ({@code @NotNull}, {@code @Min}, …) on
     *  the properties fields are silently ignored at startup. */
    public static final FindingRule SPRING_CONFIGURATION_PROPERTIES_NOT_VALIDATED =
            rule(
                    "SPRING_CONFIGURATION_PROPERTIES_NOT_VALIDATED",
                    "@ConfigurationProperties class has no @Validated annotation",
                    FindingSeverity.INFO,
                    FindingCategory.VALIDATION,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** {@code management.endpoints.web.exposure.include} exposes one or more high-risk
     *  actuator endpoints ({@code env}, {@code heapdump}, {@code shutdown}, …) that can
     *  leak credentials, dump the heap, or terminate the process if reached unauthenticated. */
    public static final FindingRule SPRING_ACTUATOR_ENDPOINT_EXPOSED_PROD =
            rule(
                    "SPRING_ACTUATOR_ENDPOINT_EXPOSED_PROD",
                    "Sensitive actuator endpoints are exposed",
                    FindingSeverity.WARNING,
                    FindingCategory.ACTUATOR,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A native SQL query string is assembled using Java string concatenation rather than
     *  JDBC/JPA named parameters, creating a potential SQL injection vulnerability. */
    public static final FindingRule SPRING_SQL_INJECTION_QUERY_CONCATENATION =
            rule(
                    "SPRING_SQL_INJECTION_QUERY_CONCATENATION",
                    "Native SQL query built with string concatenation",
                    FindingSeverity.WARNING,
                    FindingCategory.SECURITY,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A sensitive value (password, token, credential, …) is passed to a logging call,
     *  which may write it to log files or aggregation systems that are not access-controlled
     *  to the same degree as the secret store. */
    public static final FindingRule SPRING_LOGGING_PII_EXPOSURE =
            rule(
                    "SPRING_LOGGING_PII_EXPOSURE",
                    "Sensitive value may be written to logs",
                    FindingSeverity.WARNING,
                    FindingCategory.SECURITY,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A service method accesses a lazy-loaded JPA association outside of a transaction
     *  boundary. When open-in-view is disabled, this will throw
     *  {@code LazyInitializationException} at runtime. */
    public static final FindingRule SPRING_JPA_LAZY_LOADING_OUTSIDE_TRANSACTION =
            rule(
                    "SPRING_JPA_LAZY_LOADING_OUTSIDE_TRANSACTION",
                    "Service method may trigger lazy loading outside a transaction",
                    FindingSeverity.WARNING,
                    FindingCategory.PERSISTENCE,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** {@code spring.datasource.hikari.maximum-pool-size} is set to {@code 1} or less.
     *  A pool this small serialises all database access through a single connection,
     *  causing severe throughput degradation under any concurrent load. */
    public static final FindingRule SPRING_CONNECTION_POOL_MISCONFIGURED =
            rule(
                    "SPRING_CONNECTION_POOL_MISCONFIGURED",
                    "HikariCP connection pool configuration looks misconfigured",
                    FindingSeverity.WARNING,
                    FindingCategory.CONFIGURATION,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** {@code @Async} is used but no custom {@code Executor} or {@code TaskExecutor} bean
     *  is configured. Spring falls back to {@code SimpleAsyncTaskExecutor}, which creates
     *  a new thread for every invocation and provides no back-pressure or queue bounds. */
    public static final FindingRule SPRING_ASYNC_EXECUTOR_NOT_CONFIGURED =
            rule(
                    "SPRING_ASYNC_EXECUTOR_NOT_CONFIGURED",
                    "@Async used without a custom executor configured",
                    FindingSeverity.WARNING,
                    FindingCategory.SCHEDULING,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** Multiple {@code @Scheduled} methods are present but no dedicated
     *  {@code TaskScheduler} bean is registered. By default, Spring uses a single-threaded
     *  scheduler, so a slow job can delay all other scheduled tasks. */
    public static final FindingRule SPRING_SCHEDULED_EXECUTOR_SERVICE_NOT_CONFIGURED =
            rule(
                    "SPRING_SCHEDULED_EXECUTOR_SERVICE_NOT_CONFIGURED",
                    "Multiple @Scheduled methods without a dedicated TaskScheduler",
                    FindingSeverity.WARNING,
                    FindingCategory.SCHEDULING,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A {@code @FeignClient} has no {@code fallback} or {@code fallbackFactory} attribute
     *  and no circuit-breaker configuration. Any transient upstream failure propagates
     *  directly to the caller, and the default read timeout is infinite. */
    public static final FindingRule SPRING_FEIGN_NO_FALLBACK_OR_TIMEOUT =
            rule(
                    "SPRING_FEIGN_NO_FALLBACK_OR_TIMEOUT",
                    "@FeignClient has no fallback or timeout configuration",
                    FindingSeverity.WARNING,
                    FindingCategory.HTTP,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A {@code RestTemplate} {@code @Bean} method does not call {@code setErrorHandler},
     *  leaving the default behaviour of throwing {@code HttpClientErrorException} or
     *  {@code HttpServerErrorException} on non-2xx responses. Error details from downstream
     *  services are lost or inconsistently handled at different call sites. */
    public static final FindingRule SPRING_RESTTEMPLATE_NO_HTTP_STATUS_HANDLER =
            rule(
                    "SPRING_RESTTEMPLATE_NO_HTTP_STATUS_HANDLER",
                    "RestTemplate used without HTTP status error handling",
                    FindingSeverity.WARNING,
                    FindingCategory.HTTP,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A {@code @Transactional} annotation explicitly sets
     *  {@code isolation = Isolation.READ_UNCOMMITTED}. This allows reading uncommitted
     *  (dirty) data from concurrent transactions and is almost never the right choice
     *  outside of very specific analytical workloads. */
    public static final FindingRule SPRING_TRANSACTION_ISOLATION_READ_UNCOMMITTED =
            rule(
                    "SPRING_TRANSACTION_ISOLATION_READ_UNCOMMITTED",
                    "@Transactional uses READ_UNCOMMITTED isolation",
                    FindingSeverity.WARNING,
                    FindingCategory.TRANSACTION,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** {@code @Timed} is used on a Spring Boot 3.x project. {@code @Observed} (Micrometer
     *  Observation API) supersedes {@code @Timed} on Spring Boot 3+ because it produces
     *  both a timer metric and a distributed-trace span with a single annotation. */
    public static final FindingRule SPRING_TIMED_PREFER_OBSERVED =
            rule(
                    "SPRING_TIMED_PREFER_OBSERVED",
                    "@Timed used on Spring Boot 3+ — prefer @Observed",
                    FindingSeverity.INFO,
                    FindingCategory.OBSERVABILITY,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A {@code @Scheduled} method has neither {@code @Timed} nor {@code @Observed}.
     *  Scheduled jobs run in the background and are invisible to distributed tracing unless
     *  explicitly instrumented. */
    public static final FindingRule SPRING_SCHEDULED_NO_OBSERVABILITY =
            rule(
                    "SPRING_SCHEDULED_NO_OBSERVABILITY",
                    "@Scheduled method has no observability annotation",
                    FindingSeverity.INFO,
                    FindingCategory.OBSERVABILITY,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A messaging listener method ({@code @KafkaListener}, {@code @RabbitListener},
     *  {@code @JmsListener}, {@code @SqsListener}) has neither {@code @Timed} nor
     *  {@code @Observed}. Without instrumentation, consumer lag and processing failures
     *  are invisible to observability tooling. */
    public static final FindingRule SPRING_LISTENER_NO_OBSERVABILITY =
            rule(
                    "SPRING_LISTENER_NO_OBSERVABILITY",
                    "Messaging listener has no observability annotation",
                    FindingSeverity.INFO,
                    FindingCategory.OBSERVABILITY,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    // ── Testing ───────────────────────────────────────────────────────────────

    /** {@code @SpringBootTest} loads the entire application context, but the test class only
     *  injects a controller (suggesting {@code @WebMvcTest}) or a repository (suggesting
     *  {@code @DataJpaTest}). Using a slice annotation is significantly faster and more focused. */
    public static final FindingRule SPRING_TEST_SPRINGBOOTTEST_OVERUSED =
            rule(
                    "SPRING_TEST_SPRINGBOOTTEST_OVERUSED",
                    "@SpringBootTest used where a slice test would suffice",
                    FindingSeverity.WARNING,
                    FindingCategory.TESTING,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** An integration test class injects a {@code Repository} but has no class-level
     *  {@code @Transactional}. Without it, each test method that writes to the database
     *  leaves rows behind, causing order-dependent failures. */
    public static final FindingRule SPRING_TEST_NO_TRANSACTIONAL_ROLLBACK =
            rule(
                    "SPRING_TEST_NO_TRANSACTIONAL_ROLLBACK",
                    "Integration test uses a repository without @Transactional rollback",
                    FindingSeverity.WARNING,
                    FindingCategory.TESTING,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A test class declares more than five {@code @MockBean} fields. Each {@code @MockBean}
     *  replaces a real bean in the application context and forces a context reload, slowing
     *  the suite. It also signals that the class under test has too many dependencies. */
    public static final FindingRule SPRING_TEST_MOCKBEAN_OVERUSE =
            rule(
                    "SPRING_TEST_MOCKBEAN_OVERUSE",
                    "Excessive @MockBean usage forces context reloads",
                    FindingSeverity.INFO,
                    FindingCategory.TESTING,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A Spring-managed test class calls {@code .now()} (e.g. {@code LocalDateTime.now()})
     *  without injecting a fixed {@code Clock}. Time-sensitive assertions are non-deterministic
     *  and can fail around midnight, month boundaries, or DST transitions. */
    public static final FindingRule SPRING_TEST_FIXED_CLOCK_MISSING =
            rule(
                    "SPRING_TEST_FIXED_CLOCK_MISSING",
                    "Test uses wall-clock time without a fixed Clock",
                    FindingSeverity.INFO,
                    FindingCategory.TESTING,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** {@code @SpringBootTest} defaults to {@code webEnvironment = MOCK}, which initialises a
     *  full mock servlet environment. If the test class does not inject {@code MockMvc},
     *  {@code WebTestClient}, or {@code TestRestTemplate}, that overhead is wasted. */
    public static final FindingRule SPRING_TEST_SPRINGBOOTTEST_WEBENV_NONE_MISSING =
            rule(
                    "SPRING_TEST_SPRINGBOOTTEST_WEBENV_NONE_MISSING",
                    "@SpringBootTest starts mock web layer that the test does not use",
                    FindingSeverity.INFO,
                    FindingCategory.TESTING,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    // ── Caching ───────────────────────────────────────────────────────────────

    /** {@code @Cacheable} is placed on a {@code void} method. Spring will attempt to cache a
     *  {@code null} return value; depending on the {@code CacheManager} this either throws an
     *  exception at runtime or silently stores {@code null} and returns it on subsequent calls,
     *  breaking the method's contract entirely. */
    public static final FindingRule SPRING_CACHEABLE_VOID_RETURN =
            rule(
                    "SPRING_CACHEABLE_VOID_RETURN",
                    "@Cacheable on a void method has no effect",
                    FindingSeverity.ERROR,
                    FindingCategory.CACHING,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** {@code @Cacheable} or {@code @CachePut} returns a mutable collection type
     *  ({@code List}, {@code Map}, {@code Set}, etc.). Callers that mutate the returned
     *  collection are directly mutating the cached instance, causing subsequent cache hits to
     *  return corrupt data. */
    public static final FindingRule SPRING_CACHEABLE_MUTABLE_RETURN_TYPE =
            rule(
                    "SPRING_CACHEABLE_MUTABLE_RETURN_TYPE",
                    "@Cacheable returns a mutable collection — callers can corrupt the cache",
                    FindingSeverity.WARNING,
                    FindingCategory.CACHING,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A cache annotation ({@code @Cacheable}, {@code @CacheEvict}, or {@code @CachePut}) is
     *  placed on a private method. Spring's proxy-based AOP cannot intercept private methods,
     *  so the annotation is silently ignored at runtime. */
    public static final FindingRule SPRING_CACHE_ON_PRIVATE_METHOD =
            rule(
                    "SPRING_CACHE_ON_PRIVATE_METHOD",
                    "Cache annotation on private method will be silently ignored",
                    FindingSeverity.WARNING,
                    FindingCategory.CACHING,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** A {@code @Cacheable}, {@code @CacheEvict}, or {@code @CachePut} method is called
     *  directly from within the same class (self-invocation). Because Spring applies caching
     *  through a proxy wrapper around the bean, internal calls bypass the proxy and the
     *  annotation has no effect. */
    public static final FindingRule SPRING_CACHE_SELF_INVOCATION =
            rule(
                    "SPRING_CACHE_SELF_INVOCATION",
                    "Cache annotation bypassed by self-invocation",
                    FindingSeverity.WARNING,
                    FindingCategory.CACHING,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** {@code @CacheEvict} is placed on a method that has no parameters and does not set
     *  {@code allEntries = true}. Without parameters, Spring uses {@code SimpleKey.EMPTY} as
     *  the eviction key, which only removes the specific entry stored with an empty-key
     *  {@code @Cacheable} call. If the intent is to clear all entries in the cache, add
     *  {@code allEntries = true}. */
    public static final FindingRule SPRING_CACHE_EVICT_WITHOUT_ALL_ENTRIES =
            rule(
                    "SPRING_CACHE_EVICT_WITHOUT_ALL_ENTRIES",
                    "@CacheEvict on no-arg method without allEntries=true may not clear the cache",
                    FindingSeverity.INFO,
                    FindingCategory.CACHING,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    // ── Observability gaps ────────────────────────────────────────────────────

    /** An {@code @Async} method has no {@code @Observed} or {@code @Timed} annotation.
     *  Background work dispatched via {@code @Async} is common in service layers and represents
     *  a distinct unit of work that should appear in distributed traces and metrics. */
    public static final FindingRule SPRING_ASYNC_NO_OBSERVABILITY =
            rule(
                    "SPRING_ASYNC_NO_OBSERVABILITY",
                    "@Async method has no observability annotation",
                    FindingSeverity.INFO,
                    FindingCategory.OBSERVABILITY,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** An {@code @EventListener} or {@code @TransactionalEventListener} method has no
     *  {@code @Observed} or {@code @Timed} annotation. Application events that trigger
     *  significant work (sending emails, updating projections, triggering workflows) are
     *  invisible to distributed traces and dashboards without instrumentation. */
    public static final FindingRule SPRING_EVENT_LISTENER_NO_OBSERVABILITY =
            rule(
                    "SPRING_EVENT_LISTENER_NO_OBSERVABILITY",
                    "@EventListener method has no observability annotation",
                    FindingSeverity.INFO,
                    FindingCategory.OBSERVABILITY,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** An {@code @ExceptionHandler} method in a {@code @ControllerAdvice} class contains no
     *  reference to {@code MeterRegistry}, {@code Counter}, or {@code Timer}. Error rates are
     *  a core observability signal (the RED method: Rate, Errors, Duration). Without metrics
     *  in exception handlers, error spikes are invisible until users complain. */
    public static final FindingRule SPRING_EXCEPTION_HANDLER_NO_METRICS =
            rule(
                    "SPRING_EXCEPTION_HANDLER_NO_METRICS",
                    "@ExceptionHandler does not record error metrics",
                    FindingSeverity.INFO,
                    FindingCategory.OBSERVABILITY,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** {@code @Observed} is placed on a private method. Spring's proxy-based AOP cannot
     *  intercept private methods, so the annotation is silently ignored and no observation
     *  span is created at runtime. */
    public static final FindingRule SPRING_OBSERVED_ON_PRIVATE_METHOD =
            rule(
                    "SPRING_OBSERVED_ON_PRIVATE_METHOD",
                    "@Observed on private method will be silently ignored",
                    FindingSeverity.WARNING,
                    FindingCategory.OBSERVABILITY,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** {@code WebClient} is constructed directly via {@code WebClient.create(...)} or
     *  {@code WebClient.builder().build()} instead of being built from the auto-configured
     *  {@code WebClient.Builder} bean. The auto-configured builder has Micrometer tracing,
     *  HTTP client metrics, and observation propagation pre-wired. Manual construction
     *  bypasses all of that. */
    public static final FindingRule SPRING_WEBCLIENT_MANUALLY_CONSTRUCTED =
            rule(
                    "SPRING_WEBCLIENT_MANUALLY_CONSTRUCTED",
                    "WebClient constructed manually — bypasses auto-configured observability",
                    FindingSeverity.INFO,
                    FindingCategory.OBSERVABILITY,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** {@code spring.autoconfigure.exclude} removes a Spring Security auto-configuration class,
     *  bypassing the default security filter chain for that profile. */
    public static final FindingRule SPRING_SECURITY_AUTOCONFIGURE_EXCLUDED =
            rule(
                    "SPRING_SECURITY_AUTOCONFIGURE_EXCLUDED",
                    "Spring Security auto-configuration excluded in a profile",
                    FindingSeverity.WARNING,
                    FindingCategory.PROFILE_DRIFT,
                    FindingRuntimeDetection.ACTIVE_PROFILE_RUNTIME_MAY_DETECT);

    /** The default profile configures a non-embedded datasource URL, but no test profile
     *  overrides {@code spring.datasource.url}, so integration tests may hit the real database. */
    public static final FindingRule SPRING_DATASOURCE_NO_TEST_OVERRIDE =
            rule(
                    "SPRING_DATASOURCE_NO_TEST_OVERRIDE",
                    "Default datasource has no test-profile override",
                    FindingSeverity.WARNING,
                    FindingCategory.PROFILE_DRIFT,
                    FindingRuntimeDetection.ACTIVE_PROFILE_RUNTIME_MAY_DETECT);

    /** An embedded H2 datasource URL appears in a profile that does not look like a test,
     *  local, or development profile, suggesting an in-memory database could be used in staging
     *  or production. */
    public static final FindingRule SPRING_H2_IN_NON_TEST_PROFILE =
            rule(
                    "SPRING_H2_IN_NON_TEST_PROFILE",
                    "Embedded H2 database configured outside a test or local profile",
                    FindingSeverity.WARNING,
                    FindingCategory.PROFILE_DRIFT,
                    FindingRuntimeDetection.ACTIVE_PROFILE_RUNTIME_MAY_DETECT);

    /** {@code spring.flyway.enabled=false} in a test profile means schema migrations are never
     *  executed during CI, so migration errors are discovered only in production. */
    public static final FindingRule SPRING_FLYWAY_DISABLED_IN_TEST =
            rule(
                    "SPRING_FLYWAY_DISABLED_IN_TEST",
                    "Flyway migrations disabled in test profile",
                    FindingSeverity.INFO,
                    FindingCategory.PROFILE_DRIFT,
                    FindingRuntimeDetection.ACTIVE_PROFILE_RUNTIME_MAY_DETECT);

    /** A scheduling-related property (e.g. {@code spring.task.scheduling.enabled=false} or
     *  {@code spring.quartz.auto-startup=false}) is set to a disabled value in a test profile,
     *  meaning scheduled task logic is never exercised during CI runs. */
    public static final FindingRule SPRING_SCHEDULING_DISABLED_IN_TEST =
            rule(
                    "SPRING_SCHEDULING_DISABLED_IN_TEST",
                    "Scheduling disabled in test profile — scheduled tasks not tested",
                    FindingSeverity.INFO,
                    FindingCategory.PROFILE_DRIFT,
                    FindingRuntimeDetection.ACTIVE_PROFILE_RUNTIME_MAY_DETECT);

    /** An explicit dependency overrides the Spring Boot BOM and pins {@code org.hibernate:hibernate-core}
     *  to a version below 6.x while the resolved Spring Boot version is 3.x, which ships Hibernate 6.
     *  The mismatch causes runtime failures at startup. Only detectable with Gradle model data. */
    public static final FindingRule SPRING_HIBERNATE_VERSION_MISMATCH =
            rule(
                    "SPRING_HIBERNATE_VERSION_MISMATCH",
                    "Hibernate version is incompatible with the resolved Spring Boot version",
                    FindingSeverity.ERROR,
                    FindingCategory.DEPENDENCY,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** The detected Spring Boot version is 3.x but the Java version (from Gradle toolchain or
     *  build-file hint) is below 17. Spring Boot 3 requires Java 17 as a minimum; the
     *  application will fail to start on Java 11 or earlier. */
    public static final FindingRule SPRING_BOOT3_REQUIRES_JAVA17 =
            rule(
                    "SPRING_BOOT3_REQUIRES_JAVA17",
                    "Spring Boot 3.x requires Java 17 or later",
                    FindingSeverity.ERROR,
                    FindingCategory.DEPENDENCY,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    /** {@code spring.threads.virtual.enabled=true} is configured but the detected Java version
     *  is below 21. Virtual threads (Project Loom) require Java 21 or later; on older JVMs
     *  Spring Boot may fail to start or silently fall back to platform threads. */
    public static final FindingRule SPRING_VIRTUAL_THREADS_JAVA_TOO_OLD =
            rule(
                    "SPRING_VIRTUAL_THREADS_JAVA_TOO_OLD",
                    "Virtual threads enabled but Java version is too old",
                    FindingSeverity.WARNING,
                    FindingCategory.CONFIGURATION,
                    FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

    private FindingRules() {}

    private static FindingRule rule(
            String ruleId,
            String title,
            FindingSeverity severity,
            FindingCategory category,
            FindingRuntimeDetection runtimeDetection) {
        return new FindingRule(ruleId, title, severity, category, runtimeDetection);
    }
}
