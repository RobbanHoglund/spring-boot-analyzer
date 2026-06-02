# Spring Boot Analyzer — Rule Catalog

This catalog documents every static-analysis rule built into the Spring Boot Analyzer. Each rule maps to a `FindingRules` constant and is surfaced as a `Finding` in the analyzer output. For tool setup, configuration options, and usage instructions see the [project README](../README.md).

## How to read this catalog

**Severity** follows three levels:

| Severity | Meaning |
|----------|---------|
| ERROR | Near-certain runtime failure or data-loss risk. Fix before shipping to production. |
| WARNING | Strong indicator of a problem; may be intentional in some designs but warrants review. |
| INFO | Low-severity smell or best-practice deviation. Address when practical. |

**Confidence note:** This tool uses text-based static analysis — it does not build or run the project. Some findings may not apply to your specific configuration or may be suppressed by runtime behavior the analyzer cannot observe. See [Known limitations and false positives](#known-limitations-and-false-positives) at the bottom of this file.

---

## Security

| Rule ID | Severity | What it detects | Why it matters | Recommendation |
|---------|----------|-----------------|----------------|----------------|
| `SPRING_SECRET_LITERAL` | WARNING | Sensitive property (password, secret, token) set to a plain-text literal | Secrets committed to version control or config files are a common breach vector | Use environment-variable references (`${MY_SECRET}`) or a secrets manager |
| `SPRING_SECRET_WEAK_PLACEHOLDER_DEFAULT` | WARNING | `${…:defaultValue}` placeholder where the default looks like a real secret | A weak default ships to any environment where the variable is absent | Use a clearly invalid sentinel like `${DB_PASS:CHANGE_ME_OR_SET_ENV}` and fail fast |
| `SPRING_RAW_EXCEPTION_MESSAGE_HTTP` | WARNING | Controller returns `exception.getMessage()` directly in the HTTP response body | Leaks stack paths, class names, and SQL fragments to clients | Map exceptions to a generic error DTO in an `@ExceptionHandler` or `@ControllerAdvice` |
| `SPRING_CSRF_DISABLED` | WARNING | CSRF protection explicitly disabled via `.csrf().disable()` (detected in configuration properties) | Removes a built-in defense for state-changing browser requests | Only disable for stateless APIs that enforce token-based auth on every request |
| `SPRING_CSRF_DISABLED_CODE` | WARNING | CSRF protection explicitly disabled in source code via `.csrf().disable()` or `csrf(AbstractHttpConfigurer::disable)` | Removes a built-in defense for state-changing browser requests | Only disable for stateless APIs that enforce token-based auth on every request |
| `SPRING_PREAUTHORIZE_ON_PRIVATE_METHOD` | WARNING | `@PreAuthorize`, `@PostAuthorize`, `@Secured`, or `@RolesAllowed` on a private method | Spring Security's AOP proxy cannot intercept private methods; authorization check is silently skipped | Make the method at least package-private, or move it to a separate bean |
| `SPRING_CORS_ALLOW_ALL` | WARNING | `allowedOrigins("*")` or `allowedOriginPatterns("*")` in CORS configuration | Permits cross-origin requests from any domain | Restrict to known origins; enumerate them explicitly in each profile |
| `SPRING_REQUEST_PARAM_SENSITIVE_NAME` | WARNING | Password, token, or secret passed as a URL parameter or path variable | URLs are logged by proxies, load balancers, and browser history | Move secrets to request headers or the request body |
| `SPRING_SQL_INJECTION_QUERY_CONCATENATION` | WARNING | Native SQL query built with Java string concatenation | String-built queries are vulnerable to SQL injection | Use named parameters (`:param`) or `JdbcTemplate` with `?` placeholders |
| `SPRING_LOGGING_PII_EXPOSURE` | WARNING | Sensitive value (password, token, credential) passed to a logging call | Log aggregators, log files, and SIEM tools may retain the value | Redact or omit sensitive fields before logging |
| `SPRING_SECURITY_STARTER_MISSING` | INFO | Web project has no `spring-boot-starter-security` dependency | All endpoints are publicly accessible without authentication by default | Add the starter and configure an appropriate security policy |
| `SPRING_FILTER_COMPONENT_REGISTRATION_LEAK` | WARNING | Class implements `Filter` and is annotated `@Component` | Spring Boot auto-registers every `@Component` filter globally into the servlet chain, bypassing any `SecurityFilterChain` URL restrictions; the filter may also execute twice if additionally registered via `addFilterBefore` | Remove `@Component`; register the filter exclusively via `SecurityFilterChain` or declare a `FilterRegistrationBean` with `setEnabled(false)` |
| `SPRING_ASYNC_SECURITY_CONTEXT_LOST` | WARNING | `@Async` methods present alongside Spring Security with no `DelegatingSecurityContextAsyncTaskExecutor` configured | The async thread has an empty `SecurityContext`; `@PreAuthorize` checks and `SecurityContextHolder.getContext()` inside the async method see no authenticated principal | Wrap the task executor in `DelegatingSecurityContextAsyncTaskExecutor`, or set `SecurityContextHolder.setStrategyName(MODE_INHERITABLETHREADLOCAL)` |
| `SPRING_DEVTOOLS_IN_PRODUCTION` | WARNING | `spring-boot-devtools` appears in the runtime dependency list | DevTools activates live-reload servers, remote-restart endpoints, and file-system watchers; these add CPU overhead and expose attack surface in production containers | Declare DevTools as `devOnly` (Gradle) or `<optional>true</optional>` (Maven) so it is excluded from the production bootJar |
| `SPRING_WEAK_PASSWORD_HASH` | WARNING | `MessageDigest.getInstance("MD5")`, `"SHA-1"`, or `"SHA-256"` used in production code | These algorithms are optimised for speed — a modern GPU can compute billions per second, making brute-force attacks trivial against any leaked hash database | Use `BCryptPasswordEncoder`, `Argon2PasswordEncoder`, or `SCryptPasswordEncoder` from Spring Security; never implement password hashing manually |
| `SPRING_H2_CONSOLE_ENABLED_PROD` | **ERROR** | `spring.h2.console.enabled=true` in a production-oriented profile | The H2 web console accepts arbitrary SQL and can invoke Java stored procedures — a direct RCE vector when reachable | Disable in production profiles, or remove the H2 dependency entirely; keep it scoped to local development only |
| `SPRING_H2_CONSOLE_PERMITALL` | **ERROR** | `requestMatchers("/h2-console/**").permitAll()` in a `SecurityFilterChain` | Grants unauthenticated network access to an embedded SQL shell with code-execution surface | Remove the `permitAll()` on `/h2-console`; require authentication, restrict by role, or remove H2 from production builds |
| `SPRING_PERMIT_ALL_ANY_REQUEST` | **ERROR** | `anyRequest().permitAll()` or `requestMatchers("/**").permitAll()` in a security configuration | Removes authentication from every endpoint not matched by a preceding rule — usually a copy/paste accident | Replace with `.anyRequest().authenticated()` (or `.denyAll()`) and grant `permitAll` only to specific whitelisted paths |
| `SPRING_INSECURE_TRUST_MANAGER` | **ERROR** | `X509TrustManager` whose `checkServerTrusted`/`checkClientTrusted` body is empty, or a `HostnameVerifier`/lambda that returns `true` unconditionally | Disables TLS certificate or hostname validation, allowing any MITM proxy to intercept HTTPS traffic | Remove the custom trust manager/verifier and use the JVM default; install internal CAs into the trust store instead of bypassing validation |
| `SPRING_INSECURE_DESERIALIZATION` | **ERROR** | Jackson `enableDefaultTyping()`/`activateDefaultTyping(...)`, raw `new ObjectInputStream(...)`, or SnakeYAML `new Yaml()` no-arg constructor | Polymorphic deserialization of untrusted input is a well-known RCE vector (gadget chains achieve code execution on construction) | Replace Java serialization with JSON; use `@JsonTypeInfo` with an explicit subtype allowlist; use SnakeYAML `SafeConstructor`; install an `ObjectInputFilter` allowlist |
| `SPRING_XXE_VULNERABLE_PARSER` | WARNING | `DocumentBuilderFactory`/`SAXParserFactory`/`XMLInputFactory`/`TransformerFactory` created without any `setFeature`/`setProperty` call disabling external entities (heuristic — same file) | Java's default XML parsers honour DOCTYPE and external entities → XXE: file disclosure, internal-network SSRF, billion-laughs DoS | Call `setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)` and disable DOCTYPE/external entities before using the parser, or use a hardening helper |
| `SPRING_SECURITY_HEADERS_DISABLED` | WARNING | Spring Security HTTP response headers disabled (e.g. `.headers().disable()`, `.frameOptions().disable()`, `.xssProtection().disable()`, `.contentTypeOptions().disable()`) | Browser-enforced defenses against clickjacking, content sniffing, and TLS downgrade are removed | Keep the default headers enabled; configure a specific header (e.g. SAMEORIGIN frame embedding for a known partner) instead of disabling it outright |

---

## Configuration

| Rule ID | Severity | What it detects | Why it matters | Recommendation |
|---------|----------|-----------------|----------------|----------------|
| `SPRING_RISKY_PROD_CONFIG` | WARNING | Property with known operational risk (`debug=true`, `show-sql`, etc.) in a production-oriented profile | Debug settings in production increase attack surface and performance overhead | Move debug properties to development profiles only |
| `SPRING_VALUE_NO_DEFAULT` | WARNING | `@Value("${prop}")` without a `:default` fallback | Application fails to start if the property is absent in any environment | Add a safe default (`@Value("${prop:false}")`) or validate eagerly with `@ConfigurationProperties` |
| `SPRING_JPA_SHOW_SQL_PROD` | WARNING | `spring.jpa.show-sql=true` in a production-oriented profile | SQL is written directly to stdout, bypassing log-level controls and structured logging | Use `logging.level.org.hibernate.SQL=DEBUG` instead, gated to non-production profiles |
| `SPRING_JPA_OPEN_IN_VIEW` | WARNING | `spring.jpa.open-in-view=true` set explicitly in any profile | Keeps the Hibernate session open through the entire HTTP request, enabling lazy loading during serialization and masking N+1 queries | Set `spring.jpa.open-in-view=false` and load all required data explicitly in the service layer |
| `SPRING_JPA_DDL_AUTO_DANGEROUS` | **ERROR** | `spring.jpa.hibernate.ddl-auto` or `spring.datasource.initialization-mode` set to `create` or `create-drop` in a production-oriented profile (`prod`, `production`, `staging`) | Drops and recreates all tables on every startup, destroying all production data | Set to `validate` or `none` in production; manage schema with Flyway or Liquibase |
| `SPRING_CONNECTION_POOL_MISCONFIGURED` | WARNING | `spring.datasource.hikari.maximum-pool-size` set to 1 or less | Serializes all database access; causes severe throughput bottleneck under load | Set pool size to a value appropriate for your workload (commonly 5–20) |
| `SPRING_VIRTUAL_THREADS_JAVA_TOO_OLD` | WARNING | `spring.threads.virtual.enabled=true` configured but detected Java version is below 21 | Virtual threads require Java 21 (Project Loom); on older JVMs Spring Boot may fail to start or silently fall back to platform threads | Upgrade to Java 21+ or remove the virtual-threads property |

---

## Profile Drift

| Rule ID | Severity | What it detects | Why it matters | Recommendation |
|---------|----------|-----------------|----------------|----------------|
| `SPRING_SECRET_MULTI_PROFILE` | INFO | Same sensitive property key appears in multiple profile-specific config files | Increases the risk of one profile having a stale or leaked value | Centralize secrets in a vault or environment variables; reference them uniformly |
| `SPRING_PROFILE_DRIFT` | INFO | Structurally significant property has different values across profiles | Silent misconfiguration when the wrong profile is active | Document intentional differences; use a configuration matrix in CI to validate all profiles |
| `SPRING_SECURITY_AUTOCONFIGURE_EXCLUDED` | WARNING | `spring.autoconfigure.exclude` removes a Spring Security auto-configuration class in a profile | Bypasses the default security filter chain for that profile; easy to forget when promoting from dev to prod | Only exclude security auto-config in profiles where a custom security configuration is explicitly registered |
| `SPRING_DATASOURCE_NO_TEST_OVERRIDE` | WARNING | Default profile configures a non-embedded datasource but no test profile overrides `spring.datasource.url` | Integration tests may connect to the real database and corrupt data | Add an `application-test.properties` with an in-memory or containerized datasource URL |
| `SPRING_H2_IN_NON_TEST_PROFILE` | WARNING | Embedded H2 datasource URL appears outside a test, local, or development profile | In-memory data is lost on restart; wrong database engine can mask schema incompatibilities | Use a real RDBMS in staging and production profiles; restrict H2 to test and local profiles |
| `SPRING_FLYWAY_DISABLED_IN_TEST` | INFO | `spring.flyway.enabled=false` in a test profile | Schema migrations are never applied in CI, so migration errors surface only in production | Run Flyway against a test database (H2 or Testcontainers); disable only if the test explicitly manages the schema |
| `SPRING_SCHEDULING_DISABLED_IN_TEST` | INFO | A scheduling property (`spring.task.scheduling.enabled`, `spring.quartz.auto-startup`, …) is set to disabled in a test profile | Scheduled job logic is never executed during CI | Test schedulers with slice tests (`@SpringBatchTest`, manual trigger) rather than disabling them entirely |
| `SPRING_PROFILE_SPECIFIC_CONFIG` | INFO | Profile-specific configuration files (`application-prod.properties`, etc.) detected | Analysis cannot determine which profiles will be active at runtime; profile-specific values are advisory only | Review each profile file manually; use CI jobs that activate each profile to catch misconfiguration early |

---

## Persistence

| Rule ID | Severity | What it detects | Why it matters | Recommendation |
|---------|----------|-----------------|----------------|----------------|
| `SPRING_MODIFYING_NO_TRANSACTION` | ERROR | Spring Data `@Modifying` query without `@Transactional` | Always throws `TransactionRequiredException` at runtime — the method can never succeed | Add `@Transactional` to the repository method or its service caller |
| `SPRING_DDL_AUTO_DESTRUCTIVE_PROD` | ERROR | `create` or `create-drop` for `spring.jpa.hibernate.ddl-auto` in a production-oriented profile | Drops and recreates all tables on every application startup — catastrophic data loss | Set `ddl-auto=validate` or `none` in production; manage schema with Flyway or Liquibase |
| `SPRING_FLYWAY_DDL_AUTO_MIX` | WARNING | Flyway is active alongside a schema-mutating Hibernate DDL setting | Two competing schema authorities can corrupt the schema on startup | Use Flyway exclusively; set `ddl-auto=validate` or `none` |
| `SPRING_FLYWAY_MISSING_MIGRATIONS` | WARNING | Flyway is enabled but no `V*.sql` files were found under `db/migration` | Flyway will fail to start or will manage an empty migration history | Add migration scripts or disable Flyway if schema management is handled elsewhere |
| `SPRING_JPA_ONETOMANY_MISSING_MAPPED_BY` | WARNING | `@OneToMany` or `@ManyToMany` has no `mappedBy` attribute | Hibernate silently creates an unintended join table | Add `mappedBy` pointing to the owning side of the relationship |
| `SPRING_JPA_LAZY_LOADING_OUTSIDE_TRANSACTION` | WARNING | Service method may trigger lazy loading outside a transaction | Throws `LazyInitializationException` at runtime when open-in-view is disabled | Ensure lazy associations are accessed within a `@Transactional` boundary, or use fetch joins |
| `SPRING_JPA_MANYTOONE_EAGER_DEFAULT` | INFO | `@ManyToOne` or `@OneToOne` relies on the eager-loading default | Issues an extra SQL join on every parent load, even when the association is not needed | Add `fetch = FetchType.LAZY` explicitly and load eagerly only where required |
| `SPRING_LOMBOK_DATA_ON_ENTITY` | WARNING | `@Data` and `@Entity` on the same class | Lombok generates `equals()`, `hashCode()`, and `toString()` over all fields; on bidirectional lazy associations this causes eager proxy initialization and `StackOverflowError` | Replace `@Data` with `@Getter` and `@Setter`; implement `equals()`/`hashCode()` on the primary key only; use `@ToString.Exclude` on association fields |
| `SPRING_FLYWAY_DUPLICATE_VERSION` | WARNING | Two or more Flyway migration scripts share the same version number | Flyway fails on startup because version numbers must be unique across all configured locations | Assign unique, monotonically increasing version numbers to all migration scripts |

### Example bad patterns — Persistence ERRORs

```java
// SPRING_MODIFYING_NO_TRANSACTION
// Missing @Transactional — will always throw TransactionRequiredException
public interface OrderRepository extends JpaRepository<Order, Long> {
    @Modifying
    @Query("UPDATE Order o SET o.status = :status WHERE o.id = :id")
    int updateStatus(@Param("id") Long id, @Param("status") String status);
}
```

```properties
# SPRING_DDL_AUTO_DESTRUCTIVE_PROD
# application-prod.properties — drops and recreates all tables on every start
spring.jpa.hibernate.ddl-auto=create-drop
```

---

## Transaction

| Rule ID | Severity | What it detects | Why it matters | Recommendation |
|---------|----------|-----------------|----------------|----------------|
| `SPRING_TRANSACTIONAL_ON_SCHEDULED` | WARNING | `@Transactional` and `@Scheduled` on the same method | The scheduled thread has no outer transaction context; `@Transactional` may not behave as expected | Delegate to a `@Transactional` service method from the `@Scheduled` method |
| `SPRING_TRANSACTION_ISOLATION_READ_UNCOMMITTED` | WARNING | `@Transactional` specifies `READ_UNCOMMITTED` isolation | Allows reading uncommitted data from concurrent transactions (dirty reads) | Use `READ_COMMITTED` or higher unless you have a specific, documented reason |
| `SPRING_TRANSACTIONAL_ON_PRIVATE_METHOD` | **ERROR** | `@Transactional` on a private method | Spring's proxy cannot intercept private methods; the transaction is silently never started | Make the method at least package-private, or use AspectJ weaving |
| `SPRING_TRANSACTIONAL_ON_CONTROLLER` | WARNING | `@Transactional` on a `@RestController` or `@Controller` class or handler method | Database connection held open for the entire HTTP processing time including Jackson serialisation, which can trigger N+1 lazy-loading queries in the web layer | Move `@Transactional` to the service-layer method; the controller should call the service and serialise only plain DTOs |
| `SPRING_TRANSACTIONAL_SELF_INVOCATION` | WARNING | `@Transactional` method called directly from within the same class | The call bypasses the Spring AOP proxy; transaction semantics declared on the target method are ignored | Inject a self-reference via `@Autowired` or extract to a separate bean |
| `SPRING_ASYNC_TRANSACTIONAL` | **ERROR** | Method annotated with both `@Async` and `@Transactional` | The transaction context is bound to the calling thread via `ThreadLocal` and is not propagated to the async thread; DB operations run outside the transaction | Remove `@Transactional` from the `@Async` method; manage transactions inside the async body explicitly |
| `SPRING_TRANSACTION_MISSING_BOUNDARY` | INFO | Service method with save/delete/update calls has no visible `@Transactional` | Multiple writes may execute in separate transactions, leaving data partially applied on failure | Annotate the service method with `@Transactional` |
| `SPRING_TRANSACTIONAL_EXCEPTION_SWALLOWED` | **ERROR** | `@Transactional` method contains a `catch` block that catches `RuntimeException` or `Exception` without rethrowing | Spring's automatic rollback is only triggered by uncaught runtime exceptions; swallowing the exception leaves the database in a partially written state | Either rethrow the exception or call `TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()` explicitly |
| `SPRING_TRANSACTIONAL_HTTP_CALL` | WARNING | Outbound HTTP call (`RestTemplate`, `WebClient`, `HttpClient`, `@FeignClient`) inside a `@Transactional` method | Holds an open database connection for the entire network round-trip; a slow downstream service exhausts the connection pool under load | Perform the HTTP call before entering the transaction, or decouple it via messaging |

---

## Scheduling

| Rule ID | Severity | What it detects | Why it matters | Recommendation |
|---------|----------|-----------------|----------------|----------------|
| `SPRING_SCHEDULED_SIDE_EFFECT` | WARNING | `@Scheduled` method performs HTTP calls or database writes | Uncaught exceptions can silently stop the scheduler | Wrap side-effectful logic in try-catch and log failures explicitly |
| `SPRING_ASYNC_EXECUTOR_NOT_CONFIGURED` | WARNING | `@Async` is used without a custom `Executor` bean | Falls back to `SimpleAsyncTaskExecutor`, which creates an unbounded number of threads | Define a `ThreadPoolTaskExecutor` bean and reference it in `@Async` |
| `SPRING_SCHEDULED_EXECUTOR_SERVICE_NOT_CONFIGURED` | WARNING | Multiple `@Scheduled` methods without a dedicated `TaskScheduler` | The default single-threaded scheduler means a slow job delays all subsequent jobs | Configure a `ThreadPoolTaskScheduler` bean |
| `SPRING_SCHEDULED_SHORT_INTERVAL` | INFO | `fixedRate` or `fixedDelay` shorter than 1 minute | High-frequency polling increases load and may mask underlying inefficiencies | Consider event-driven alternatives or increase the interval |
| `SPRING_SCHEDULED_CRON_NO_ZONE` | INFO | `@Scheduled` cron expression has no `zone` attribute | Fires at different wall-clock times depending on the JVM default time zone | Add `zone = "UTC"` (or another explicit zone) to the annotation |
| `SPRING_EVENT_LISTENER_BLOCKING` | INFO | `@EventListener` or `@TransactionalEventListener` method has no `@Async` | Spring dispatches events synchronously on the publisher's thread; a slow listener (email, remote API, heavy computation) blocks the HTTP request thread that published the event | Annotate the listener with `@Async` and ensure a custom executor is configured, or publish to a queue and process out of band |

---

## HTTP Clients

| Rule ID | Severity | What it detects | Why it matters | Recommendation |
|---------|----------|-----------------|----------------|----------------|
| `SPRING_HTTP_CLIENT_NO_TIMEOUT` | WARNING | HTTP client created without visible timeout configuration | A slow upstream holds a thread indefinitely, exhausting the thread pool | Configure connect and read timeouts explicitly on `RestTemplate`, `WebClient`, or Feign |
| `SPRING_HTTP_PLAIN_URL` | WARNING | External service URL uses plain `http://` | Traffic is unencrypted and susceptible to interception | Switch to `https://`; enforce TLS in all environments |
| `SPRING_FEIGN_NO_FALLBACK_OR_TIMEOUT` | WARNING | `@FeignClient` has no fallback class or timeout configuration | Feign's default read timeout is effectively infinite | Configure timeouts via `feign.client.config` and add a fallback or circuit breaker |
| `SPRING_RESTTEMPLATE_NO_HTTP_STATUS_HANDLER` | WARNING | `RestTemplate` used without a custom `ResponseErrorHandler` | Error responses from downstream are lost or handled inconsistently | Register a `ResponseErrorHandler` or switch to `WebClient` with `.onStatus()` |
| `SPRING_HTTP_CLIENT_NO_RESILIENCE` | INFO | HTTP client has no visible retry or circuit-breaker configuration | Transient upstream failures propagate directly to the caller | Add retry logic (Spring Retry) or a circuit breaker (Resilience4j) |
| `SPRING_REST_TEMPLATE_NO_TIMEOUT` | WARNING | `new RestTemplate()` called with the no-arg constructor | The default `SimpleClientHttpRequestFactory` has zero connect and read timeouts; a hanging downstream service blocks the thread indefinitely | Pass a `SimpleClientHttpRequestFactory` or `HttpComponentsClientHttpRequestFactory` with explicit timeouts, or inject the auto-configured `RestTemplateBuilder` |

---

## Exception Handling

| Rule ID | Severity | What it detects | Why it matters | Recommendation |
|---------|----------|-----------------|----------------|----------------|
| `JAVA_EMPTY_CATCH_BLOCK` | WARNING | Empty `catch` block | The exception is silently discarded; failures are invisible to operators | At minimum, log the exception; rethrow or handle it meaningfully |
| `SPRING_INTERRUPTED_EXCEPTION_SWALLOWED` | WARNING | `InterruptedException` caught without restoring interrupt status | Breaks cooperative thread cancellation; the thread will not stop cleanly | Call `Thread.currentThread().interrupt()` before returning or rethrowing |
| `SPRING_BROAD_FATAL_ERROR_CATCH` | WARNING | Catches `Error` or `Throwable` | Masks JVM-level fatal conditions such as `OutOfMemoryError` | Catch only recoverable exceptions; let `Error` propagate |
| `SPRING_SWALLOWED_EXCEPTION_FALLBACK` | INFO | Exception caught, silently replaced with a fallback value | Failure is invisible to operators and monitoring | Log the exception at an appropriate level before using the fallback |
| `SPRING_BROAD_EXCEPTION_SPRING_BOUNDARY` | INFO | Catches `Exception` broadly in a controller, listener, or scheduler | Prevents more specific handlers from receiving the exception | Catch specific exception types; use `@ExceptionHandler` for controller boundaries |
| `SPRING_BROAD_EXCEPTION_HANDLER` | INFO | `@ExceptionHandler` method catches `Exception` broadly | Matches every exception type, overriding more specific handlers | Define handlers for specific exception types first |
| `SPRING_ASYNC_VOID_SWALLOWED_EXCEPTION` | INFO | `@Async void` method has no exception handling | Failures are routed to `AsyncUncaughtExceptionHandler`, which is often not configured | Change return type to `Future<?>` / `CompletableFuture<?>`, or register an `AsyncUncaughtExceptionHandler` |
| `SPRING_MESSAGING_LISTENER_NO_ERROR_HANDLER` | INFO | Messaging listener has no visible error handling | A poison message can cause the consumer to stop silently | Add a `@KafkaListener` error handler or configure a dead-letter topic |
| `SPRING_PRINT_STACK_TRACE` | INFO | `exception.printStackTrace()` used instead of application logging | Bypasses log-level controls and structured log formats | Use `log.error("message", exception)` |

---

## Validation

| Rule ID | Severity | What it detects | Why it matters | Recommendation |
|---------|----------|-----------------|----------------|----------------|
| `SPRING_REQUEST_BODY_NO_VALID` | INFO | `@RequestBody` parameter is missing `@Valid` | Bean Validation constraints on the DTO are silently ignored | Add `@Valid` (or `@Validated`) to the parameter |
| `SPRING_MODEL_ATTRIBUTE_NO_VALID` | INFO | `@ModelAttribute` parameter is missing `@Valid` | Same as above for form-binding scenarios | Add `@Valid` to the parameter |
| `SPRING_CONFIGURATION_PROPERTIES_NOT_VALIDATED` | INFO | `@ConfigurationProperties` class has no `@Validated` annotation | Bean Validation constraints on the class are silently ignored at startup | Add `@Validated` to the class so constraint violations fail fast on context load |

---

## Observability

| Rule ID | Severity | What it detects | Why it matters | Recommendation |
|---------|----------|-----------------|----------------|----------------|
| `SPRING_TIMED_PREFER_OBSERVED` | INFO | `@Timed` used in a Spring Boot 3+ project | `@Timed` covers only metrics; `@Observed` covers both metrics and distributed traces | Replace `@Timed` with `@Observed` and ensure `ObservationRegistry` is configured |
| `SPRING_SCHEDULED_NO_OBSERVABILITY` | INFO | `@Scheduled` method has no `@Observed` or `@Timed` annotation | Scheduled jobs run in the background and are invisible to distributed tracing | Add `@Observed` (Spring Boot 3+) or a `@Timed` annotation |
| `SPRING_LISTENER_NO_OBSERVABILITY` | INFO | Messaging listener has no observability annotation | Consumer lag and failures are invisible to distributed tracing | Add `@Observed` or instrument the listener with the `ObservationRegistry` API |
| `SPRING_ASYNC_NO_OBSERVABILITY` | INFO | `@Async` method has no `@Observed` or `@Timed` annotation | Background work dispatched asynchronously is invisible to distributed traces and metrics | Add `@Observed` to async methods that represent a meaningful unit of work |
| `SPRING_EVENT_LISTENER_NO_OBSERVABILITY` | INFO | `@EventListener` or `@TransactionalEventListener` method has no observability annotation | Application events that trigger significant work are invisible to tracing dashboards | Instrument with `@Observed` or the `ObservationRegistry` API |
| `SPRING_EXCEPTION_HANDLER_NO_METRICS` | INFO | `@ExceptionHandler` in a `@ControllerAdvice` records no metrics | Error rates are a core observability signal; without counters, error spikes are invisible until users complain | Inject `MeterRegistry` and increment a counter per exception type |
| `SPRING_OBSERVED_ON_PRIVATE_METHOD` | WARNING | `@Observed` placed on a private method | Spring AOP cannot intercept private methods; no observation span is created at runtime | Move the method to at least package-private visibility, or refactor to a separate bean |
| `SPRING_WEBCLIENT_MANUALLY_CONSTRUCTED` | INFO | `WebClient` created via `WebClient.create()` instead of the auto-configured `WebClient.Builder` bean | Manual construction bypasses Micrometer tracing, HTTP client metrics, and observation propagation that the auto-configured builder wires in | Inject `WebClient.Builder` and call `.build()` on it instead |
| `SPRING_UNMANAGED_THREAD` | WARNING | `new Thread(...).start()` inside a Spring-managed component (`@Service`, `@Component`, `@Controller`, `@Repository`) | Manually created threads run outside Spring's lifecycle — no transaction context, no security context, no MDC logging, and no unified error handling | Use `@Async` with a configured `TaskExecutor`, or `TaskExecutor.execute(...)` directly |
| `SPRING_SYSTEM_OUT_PRINTLN` | WARNING | `System.out.println()` or `System.err.println()` in production source code | Bypasses the application's logging framework and its configuration for log levels, correlation IDs, log shipping, and structured output | Replace with a `Logger` (SLF4J / Logback) at the appropriate level |

---

## Maintainability

| Rule ID | Severity | What it detects | Why it matters | Recommendation |
|---------|----------|-----------------|----------------|----------------|
| `SPRING_FIELD_INJECTION` | INFO | `@Autowired` on a field instead of a constructor | Hides dependencies, prevents `final` fields, and makes unit testing harder | Use constructor injection; let Lombok `@RequiredArgsConstructor` generate the boilerplate |
| `SPRING_BEAN_ON_NON_CONFIGURATION` | WARNING | `@Bean` method in a class annotated with `@Component` instead of `@Configuration` | Without CGLIB proxying, inter-bean method calls create new instances instead of singletons | Change the class annotation to `@Configuration` |
| `SPRING_SIDE_EFFECT_ORCHESTRATION_NO_BOUNDARY` | INFO | Multiple external calls with no compensation or consistency boundary | A failure mid-sequence leaves the system in a partially applied state | Use the Saga pattern, outbox pattern, or an explicit rollback strategy |
| `SPRING_REPEATED_FALLBACK_PARSING_PATTERN` | INFO | Try-parse-then-fallback repeated three or more times in the same class | Duplicated error-handling logic that should be extracted to a utility | Extract a reusable `parseOrDefault(value, fallback)` helper |
| `SPRING_ASYNC_PROXY_BYPASS` | WARNING | `@Async` on a private method | Spring's proxy cannot intercept private methods; the method runs synchronously | Make the method at least package-private, or move it to a separate bean |
| `SPRING_ASYNC_NON_FUTURE_RETURN` | WARNING | `@Async` method whose return type is not `void`, `Future`, `CompletableFuture`, `ListenableFuture`, `Mono`, or `Flux` | Spring's async proxy discards the actual return value; the caller always receives `null` | Change return type to `CompletableFuture<T>` and wrap the result, or change to `void` if the caller does not use the return value |
| `SPRING_HARDCODED_FILE_PATH` | WARNING | Absolute file system path literal (`/var/…`, `/tmp/…`, `C:\…`) passed to `new File(…)` or `Paths.get(…)` | Hardcoded paths break in containerised or cloud-native deployments; data written to a container's local disk is lost on restart or scale-out | Abstract storage behind an interface; use cloud-agnostic object storage (S3, Azure Blob, GCS) for persistent data; read paths from configuration properties |
| `SPRING_APPLICATION_CONTEXT_INJECTED` | WARNING | `ApplicationContext` injected as a field in a Spring-managed component | This is the service-locator anti-pattern: it bypasses compile-time dependency checking, hides real dependencies, and tightly couples the class to the Spring API | Inject specific beans by type directly; if multiple implementations must be selected dynamically, use a `Map<String, MyInterface>` injection or a factory bean |
| `SPRING_PROTOTYPE_BEAN_IN_SINGLETON` | WARNING | A `@Scope("prototype")` bean injected as a field or constructor parameter into a singleton bean | Spring instantiates the prototype once at startup and reuses the same instance for the singleton's lifetime, defeating per-use semantics and causing shared-state bugs | Inject `ObjectFactory<MyPrototypeBean>` or `Provider<MyPrototypeBean>` and call `getObject()` each time a fresh instance is needed, or use `@Lookup` method injection |
| `SPRING_WEBFLUX_BLOCKING_CALL` | WARNING | `.block()`, `.blockFirst()`, `.blockLast()`, or `Thread.sleep()` called inside a Spring-managed component | In WebFlux, these block the Netty event-loop thread, preventing it from handling other requests and causing cascading latency under load; in Servlet apps `.block()` signals a reactive-to-blocking impedance mismatch | Remove `.block()` and propagate the `Mono`/`Flux` to the caller; if blocking I/O is unavoidable, offload to `Schedulers.boundedElastic()`; replace `Thread.sleep()` with `Mono.delay(…)` or `@Scheduled` |
| `SPRING_REPOSITORY_IN_CONTROLLER` | WARNING | `@RestController` or `@Controller` class directly injects a `Repository`, `Dao`, or `DAO` type | Couples the HTTP layer to persistence; business rules, transactions, and caching have no single home and accumulate in the controller | Introduce a `@Service` class that owns business logic and calls the repository; the controller should call only the service |
| `SPRING_STATIC_MUTABLE_FIELD` | WARNING | `static` non-`final` field of a mutable collection type (`List`, `Map`, `Set`, etc.) in a Spring-managed component | Shared across all threads and all requests with no coordination; any concurrent write is a data race; in a multi-instance deployment each pod has its own inconsistent copy | Use method-local variables for per-request state, Spring `@Cacheable` with a TTL-capable provider for shared caches, or Micrometer counters for shared metrics |

---

## Conditional Beans

| Rule ID | Severity | What it detects | Why it matters | Recommendation |
|---------|----------|-----------------|----------------|----------------|
| `SPRING_CONDITIONAL_VALUE_MISMATCH` | WARNING | Configured property value does not match any `@ConditionalOnProperty(havingValue = …)` in the codebase | No bean will be activated for the configured value; silent no-op | Check that the property value matches one of the declared `havingValue` strings exactly |
| `SPRING_CONDITIONAL_MATCH_IF_MISSING_OVERLAP` | WARNING | Multiple beans use `matchIfMissing = true` for the same property | When the property is absent, which bean is active depends on classpath ordering | Ensure only one bean sets `matchIfMissing = true`; make the default explicit |

---

## Startup

| Rule ID | Severity | What it detects | Why it matters | Recommendation |
|---------|----------|-----------------|----------------|----------------|
| `SPRING_STARTUP_SIDE_EFFECT` | WARNING | `@PostConstruct`, `CommandLineRunner`, or `ApplicationReadyEvent` listener performs outbound I/O | A slow or unavailable dependency delays or prevents startup | Use readiness probes and deferred initialization; consider event-driven initialization after readiness |

---

## Actuator

| Rule ID | Severity | What it detects | Why it matters | Recommendation |
|---------|----------|-----------------|----------------|----------------|
| `SPRING_ACTUATOR_ENDPOINT_EXPOSED_PROD` | WARNING | Sensitive actuator endpoints (`env`, `heapdump`, `shutdown`) exposed without authentication | Provides read access to environment variables (including secrets) and can shut down the process | Restrict endpoint exposure in `management.endpoints.web.exposure.include`; secure with Spring Security |

---

## API Surface

| Rule ID | Severity | What it detects | Why it matters | Recommendation |
|---------|----------|-----------------|----------------|----------------|
| `SPRING_REQUEST_MAPPING_NO_METHOD` | INFO | `@RequestMapping` has no `method` attribute | Matches all HTTP verbs, including mutating methods like DELETE and PUT | Use `@GetMapping`, `@PostMapping`, etc., or add `method = RequestMethod.GET` explicitly |
| `SPRING_ENTITY_EXPOSED_IN_API` | WARNING | `@RestController` method returns a JPA `@Entity` type directly (or inside `ResponseEntity` / `List`) | Exposes internal persistence structure, lazy-loading proxies, and potentially sensitive columns; breaks API evolution when the entity schema changes | Introduce a dedicated DTO or record; map the entity to it in the service layer |
| `SPRING_REACTIVE_API_IN_SERVLET_APP` | INFO | Reactive types (`Mono`, `Flux`, WebFlux imports) used in a Servlet/MVC project | Mixing reactive and blocking code paths causes subtle threading issues and prevents back-pressure | Use either the servlet stack or the reactive stack; do not mix `spring-boot-starter-web` and `spring-boot-starter-webflux` |

---

## Testing Practice

| Rule ID | Severity | What it detects | Why it matters | Recommendation |
|---------|----------|-----------------|----------------|----------------|
| `SPRING_TEST_SPRINGBOOTTEST_OVERUSED` | WARNING | `@SpringBootTest` used in a test that only injects a controller or repository | Loads the full application context when a focused slice test (`@WebMvcTest`, `@DataJpaTest`) would be faster and more precise | Replace with the appropriate slice annotation; the full context is rarely needed for single-layer tests |
| `SPRING_TEST_NO_TRANSACTIONAL_ROLLBACK` | WARNING | Integration test class injects a repository without class-level `@Transactional` | Database writes persist between tests, causing order-dependent failures and a polluted test database | Add `@Transactional` to the test class so each test method rolls back automatically |
| `SPRING_TEST_MOCKBEAN_OVERUSE` | INFO | Test class declares more than five `@MockBean` fields | Each `@MockBean` forces a Spring context reload, slowing the entire test suite; many mocks also signal that the class under test has too many dependencies | Reduce mocks by refactoring large dependencies; use `@SpyBean` for partial mocks; consider moving to pure unit tests |
| `SPRING_TEST_FIXED_CLOCK_MISSING` | INFO | Test calls `LocalDateTime.now()` or similar without injecting a fixed `Clock` | Time-sensitive assertions are non-deterministic and can fail around midnight or DST transitions | Inject a `Clock` bean; use `Clock.fixed(...)` in the test configuration |
| `SPRING_TEST_SPRINGBOOTTEST_WEBENV_NONE_MISSING` | INFO | `@SpringBootTest` starts a mock web layer that the test class never uses | Unnecessary servlet infrastructure is initialised for every test method | Set `webEnvironment = SpringBootTest.WebEnvironment.NONE` to skip the mock layer |

---

## Caching

| Rule ID | Severity | What it detects | Why it matters | Recommendation |
|---------|----------|-----------------|----------------|----------------|
| `SPRING_CACHEABLE_VOID_RETURN` | **ERROR** | `@Cacheable` on a `void` method | Spring attempts to cache a `null` return; depending on the `CacheManager` this throws at runtime or permanently stores `null`, breaking subsequent calls | Remove `@Cacheable` from `void` methods; use `@CacheEvict` if the intent is cache invalidation |
| `SPRING_CACHEABLE_MUTABLE_RETURN_TYPE` | WARNING | `@Cacheable` or `@CachePut` returns a mutable collection (`List`, `Map`, `Set`, …) | Callers that modify the returned collection mutate the cached instance directly, corrupting future cache hits | Return an immutable view (`List.copyOf(...)`) or use a DTO that callers cannot mutate |
| `SPRING_CACHE_ON_PRIVATE_METHOD` | WARNING | Cache annotation (`@Cacheable`, `@CacheEvict`, `@CachePut`) on a private method | Spring AOP cannot intercept private methods; the annotation is silently ignored at runtime | Make the method at least package-private, or move it to a separate bean |
| `SPRING_CACHE_SELF_INVOCATION` | WARNING | Cached method called directly from within the same class | Internal calls bypass the Spring proxy, so the cache annotation has no effect | Extract the cached method to a separate bean, or inject a self-reference with `@Autowired` |
| `SPRING_CACHE_EVICT_WITHOUT_ALL_ENTRIES` | INFO | `@CacheEvict` on a no-arg method without `allEntries = true` | Without parameters, Spring uses `SimpleKey.EMPTY` as the eviction key, which only removes a single entry stored under that key — not the whole cache | Add `allEntries = true` if the intent is to clear all entries; add parameters if you need key-targeted eviction |
| `SPRING_CACHEABLE_SYNC_INCOMPATIBLE` | **ERROR** | `@Cacheable(sync = true)` combined with `unless` attribute or multiple cache names | Spring's `CacheAspectSupport` explicitly rejects these combinations and throws `IllegalArgumentException` on the first method invocation | Remove `unless` when using `sync = true`; use a single cache name per synchronized `@Cacheable` |
| `SPRING_CACHEPUT_AND_CACHEABLE_SAME_METHOD` | **ERROR** | Method annotated with both `@CachePut` and `@Cacheable` | The two annotations have conflicting semantics; Spring's `CacheAspectSupport` throws `IllegalStateException` on the first call | Use only one of `@Cacheable` or `@CachePut` on any given method |
| `SPRING_CACHEABLE_NO_TTL_PROVIDER` | WARNING | `@Cacheable` used without a cache provider that supports TTL (Caffeine, Redis, JCache) | The default `ConcurrentHashMap`-backed cache has no eviction or TTL policy; cached values accumulate indefinitely, causing memory growth and stale data | Configure a TTL-capable provider via `spring.cache.type`, `spring.cache.caffeine.spec`, or `spring.cache.redis.*` |
| `SPRING_CACHEABLE_NO_EVICTION` | WARNING | Class has `@Cacheable` methods but no `@CacheEvict` or `@CachePut` in the same class | Update or delete operations on the underlying data will not invalidate cached results, causing clients to receive stale data | Add `@CacheEvict` to every write method that modifies data covered by the cache |

---

## Dependency Compatibility

| Rule ID | Severity | What it detects | Why it matters | Recommendation |
|---------|----------|-----------------|----------------|----------------|
| `SPRING_HIBERNATE_VERSION_MISMATCH` | **ERROR** | Resolved `hibernate-core` version is below 6.x while the resolved Spring Boot version is 3.x | Spring Boot 3 requires Hibernate 6; an older Hibernate causes startup failures and incompatible JPA behaviour. Only detected when Gradle model data is available (`EXTENDED` mode). | Remove the explicit Hibernate version override and let the Spring Boot BOM manage it, or upgrade to Hibernate 6+ |
| `SPRING_BOOT3_REQUIRES_JAVA17` | **ERROR** | Spring Boot version is 3.x but the detected Java version (from Gradle toolchain or build-file hint) is below 17 | Spring Boot 3 requires Java 17 as a minimum; the application will fail to start on Java 11 or earlier | Upgrade to Java 17+ or downgrade to Spring Boot 2.x |

---

## Known limitations and false positives

Static analysis without compilation or runtime context will produce false positives. Common scenarios where a finding may not apply:

**Configuration analysis**
- Rules that look for properties in "production-oriented" profiles use heuristics on profile names (e.g., `prod`, `production`, `release`). A profile named differently may not be recognized.
- `SPRING_VALUE_NO_DEFAULT` may fire on `@Value` fields that are guaranteed to be present via a required external configuration source.

**Security rules**
- `SPRING_SECRET_LITERAL` may fire on non-sensitive values that happen to contain the word "password" or "secret" in the property key.
- `SPRING_CORS_ALLOW_ALL` may be intentional for public APIs where any origin is acceptable.
- `SPRING_CSRF_DISABLED` is appropriate for stateless REST APIs using token-based authentication.

**Persistence rules**
- `SPRING_JPA_LAZY_LOADING_OUTSIDE_TRANSACTION` uses heuristics to detect service methods; it may miss transactional boundaries provided by outer callers.
- `SPRING_FLYWAY_MISSING_MIGRATIONS` checks for `V*.sql` files under `db/migration`. Custom migration locations configured via `spring.flyway.locations` are not followed.

**Transaction rules**
- `SPRING_TRANSACTIONAL_SELF_INVOCATION` cannot always determine whether a class injects itself. True self-injections that use `@Autowired ApplicationContext` to retrieve the proxy will still trigger the finding.

**Scheduling and async rules**
- `SPRING_ASYNC_EXECUTOR_NOT_CONFIGURED` may fire even if a custom executor is configured in a separate `@Configuration` class not visible to the analyzer at scan time.

**General**
- The analyzer works on source text. Generated code (e.g., from annotation processors or build plugins) is not analyzed.
- Multi-module Maven/Gradle projects: rules that correlate across files (e.g., `SPRING_CONDITIONAL_VALUE_MISMATCH`) may not see the full picture if modules are analyzed independently.

If a finding does not apply to your project, it can be suppressed by adjusting the analyzer configuration. Refer to the [README](../README.md) for configuration options.
