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
| `SPRING_CSRF_DISABLED` | WARNING | CSRF protection explicitly disabled via `.csrf().disable()` | Removes a built-in defense for state-changing browser requests | Only disable for stateless APIs that enforce token-based auth on every request |
| `SPRING_CORS_ALLOW_ALL` | WARNING | `allowedOrigins("*")` or `allowedOriginPatterns("*")` in CORS configuration | Permits cross-origin requests from any domain | Restrict to known origins; enumerate them explicitly in each profile |
| `SPRING_REQUEST_PARAM_SENSITIVE_NAME` | WARNING | Password, token, or secret passed as a URL parameter or path variable | URLs are logged by proxies, load balancers, and browser history | Move secrets to request headers or the request body |
| `SPRING_SQL_INJECTION_QUERY_CONCATENATION` | WARNING | Native SQL query built with Java string concatenation | String-built queries are vulnerable to SQL injection | Use named parameters (`:param`) or `JdbcTemplate` with `?` placeholders |
| `SPRING_LOGGING_PII_EXPOSURE` | WARNING | Sensitive value (password, token, credential) passed to a logging call | Log aggregators, log files, and SIEM tools may retain the value | Redact or omit sensitive fields before logging |
| `SPRING_SECURITY_STARTER_MISSING` | INFO | Web project has no `spring-boot-starter-security` dependency | All endpoints are publicly accessible without authentication by default | Add the starter and configure an appropriate security policy |

---

## Configuration

| Rule ID | Severity | What it detects | Why it matters | Recommendation |
|---------|----------|-----------------|----------------|----------------|
| `SPRING_RISKY_PROD_CONFIG` | WARNING | Property with known operational risk (`debug=true`, `show-sql`, etc.) in a production-oriented profile | Debug settings in production increase attack surface and performance overhead | Move debug properties to development profiles only |
| `SPRING_VALUE_NO_DEFAULT` | WARNING | `@Value("${prop}")` without a `:default` fallback | Application fails to start if the property is absent in any environment | Add a safe default (`@Value("${prop:false}")`) or validate eagerly with `@ConfigurationProperties` |
| `SPRING_JPA_SHOW_SQL_PROD` | WARNING | `spring.jpa.show-sql=true` in a production-oriented profile | SQL is written directly to stdout, bypassing log-level controls and structured logging | Use `logging.level.org.hibernate.SQL=DEBUG` instead, gated to non-production profiles |
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

---

## Persistence

| Rule ID | Severity | What it detects | Why it matters | Recommendation |
|---------|----------|-----------------|----------------|----------------|
| `SPRING_MODIFYING_NO_TRANSACTION` | ERROR | Spring Data `@Modifying` query without `@Transactional` | Always throws `TransactionRequiredException` at runtime — the method can never succeed | Add `@Transactional` to the repository method or its service caller |
| `SPRING_DDL_AUTO_DESTRUCTIVE_PROD` | ERROR | `create` or `create-drop` for `spring.jpa.hibernate.ddl-auto` in a production-oriented profile | Drops and recreates all tables on every application startup — catastrophic data loss | Set `ddl-auto=validate` or `none` in production; manage schema with Flyway or Liquibase |
| `SPRING_JPA_OPEN_IN_VIEW` | WARNING | `spring.jpa.open-in-view` not explicitly set to `false` | Keeps the Hibernate session open through the entire HTTP request, masking N+1 queries | Set `spring.jpa.open-in-view=false` and resolve resulting `LazyInitializationException` errors intentionally |
| `SPRING_FLYWAY_DDL_AUTO_MIX` | WARNING | Flyway is active alongside a schema-mutating Hibernate DDL setting | Two competing schema authorities can corrupt the schema on startup | Use Flyway exclusively; set `ddl-auto=validate` or `none` |
| `SPRING_FLYWAY_MISSING_MIGRATIONS` | WARNING | Flyway is enabled but no `V*.sql` files were found under `db/migration` | Flyway will fail to start or will manage an empty migration history | Add migration scripts or disable Flyway if schema management is handled elsewhere |
| `SPRING_JPA_ONETOMANY_MISSING_MAPPED_BY` | WARNING | `@OneToMany` or `@ManyToMany` has no `mappedBy` attribute | Hibernate silently creates an unintended join table | Add `mappedBy` pointing to the owning side of the relationship |
| `SPRING_JPA_LAZY_LOADING_OUTSIDE_TRANSACTION` | WARNING | Service method may trigger lazy loading outside a transaction | Throws `LazyInitializationException` at runtime when open-in-view is disabled | Ensure lazy associations are accessed within a `@Transactional` boundary, or use fetch joins |
| `SPRING_JPA_MANYTOONE_EAGER_DEFAULT` | INFO | `@ManyToOne` or `@OneToOne` relies on the eager-loading default | Issues an extra SQL join on every parent load, even when the association is not needed | Add `fetch = FetchType.LAZY` explicitly and load eagerly only where required |

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
| `SPRING_TRANSACTION_SELF_INVOCATION` | INFO | `@Transactional` method called via `this.method()` within the same bean | The call bypasses the Spring AOP proxy, so `@Transactional` is silently ignored | Inject a self-reference via `@Autowired` or refactor into a separate bean |
| `SPRING_TRANSACTION_PRIVATE_METHOD` | INFO | `@Transactional` on a private method | Spring's proxy cannot intercept private methods; the annotation is silently ignored | Move the transactional logic to a package-private or public method |
| `SPRING_TRANSACTION_MISSING_BOUNDARY` | INFO | Service method with save/delete/update calls has no visible `@Transactional` | Multiple writes may execute in separate transactions, leaving data partially applied on failure | Annotate the service method with `@Transactional` |

---

## Scheduling

| Rule ID | Severity | What it detects | Why it matters | Recommendation |
|---------|----------|-----------------|----------------|----------------|
| `SPRING_SCHEDULED_SIDE_EFFECT` | WARNING | `@Scheduled` method performs HTTP calls or database writes | Uncaught exceptions can silently stop the scheduler | Wrap side-effectful logic in try-catch and log failures explicitly |
| `SPRING_ASYNC_EXECUTOR_NOT_CONFIGURED` | WARNING | `@Async` is used without a custom `Executor` bean | Falls back to `SimpleAsyncTaskExecutor`, which creates an unbounded number of threads | Define a `ThreadPoolTaskExecutor` bean and reference it in `@Async` |
| `SPRING_SCHEDULED_EXECUTOR_SERVICE_NOT_CONFIGURED` | WARNING | Multiple `@Scheduled` methods without a dedicated `TaskScheduler` | The default single-threaded scheduler means a slow job delays all subsequent jobs | Configure a `ThreadPoolTaskScheduler` bean |
| `SPRING_SCHEDULED_SHORT_INTERVAL` | INFO | `fixedRate` or `fixedDelay` shorter than 1 minute | High-frequency polling increases load and may mask underlying inefficiencies | Consider event-driven alternatives or increase the interval |
| `SPRING_SCHEDULED_CRON_NO_ZONE` | INFO | `@Scheduled` cron expression has no `zone` attribute | Fires at different wall-clock times depending on the JVM default time zone | Add `zone = "UTC"` (or another explicit zone) to the annotation |

---

## HTTP Clients

| Rule ID | Severity | What it detects | Why it matters | Recommendation |
|---------|----------|-----------------|----------------|----------------|
| `SPRING_HTTP_CLIENT_NO_TIMEOUT` | WARNING | HTTP client created without visible timeout configuration | A slow upstream holds a thread indefinitely, exhausting the thread pool | Configure connect and read timeouts explicitly on `RestTemplate`, `WebClient`, or Feign |
| `SPRING_HTTP_PLAIN_URL` | WARNING | External service URL uses plain `http://` | Traffic is unencrypted and susceptible to interception | Switch to `https://`; enforce TLS in all environments |
| `SPRING_FEIGN_NO_FALLBACK_OR_TIMEOUT` | WARNING | `@FeignClient` has no fallback class or timeout configuration | Feign's default read timeout is effectively infinite | Configure timeouts via `feign.client.config` and add a fallback or circuit breaker |
| `SPRING_RESTTEMPLATE_NO_HTTP_STATUS_HANDLER` | WARNING | `RestTemplate` used without a custom `ResponseErrorHandler` | Error responses from downstream are lost or handled inconsistently | Register a `ResponseErrorHandler` or switch to `WebClient` with `.onStatus()` |
| `SPRING_HTTP_CLIENT_NO_RESILIENCE` | INFO | HTTP client has no visible retry or circuit-breaker configuration | Transient upstream failures propagate directly to the caller | Add retry logic (Spring Retry) or a circuit breaker (Resilience4j) |

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

---

## Maintainability

| Rule ID | Severity | What it detects | Why it matters | Recommendation |
|---------|----------|-----------------|----------------|----------------|
| `SPRING_FIELD_INJECTION` | INFO | `@Autowired` on a field instead of a constructor | Hides dependencies, prevents `final` fields, and makes unit testing harder | Use constructor injection; let Lombok `@RequiredArgsConstructor` generate the boilerplate |
| `SPRING_BEAN_ON_NON_CONFIGURATION` | WARNING | `@Bean` method in a class annotated with `@Component` instead of `@Configuration` | Without CGLIB proxying, inter-bean method calls create new instances instead of singletons | Change the class annotation to `@Configuration` |
| `SPRING_SIDE_EFFECT_ORCHESTRATION_NO_BOUNDARY` | INFO | Multiple external calls with no compensation or consistency boundary | A failure mid-sequence leaves the system in a partially applied state | Use the Saga pattern, outbox pattern, or an explicit rollback strategy |
| `SPRING_REPEATED_FALLBACK_PARSING_PATTERN` | INFO | Try-parse-then-fallback repeated three or more times in the same class | Duplicated error-handling logic that should be extracted to a utility | Extract a reusable `parseOrDefault(value, fallback)` helper |
| `SPRING_ASYNC_PROXY_BYPASS` | WARNING | `@Async` on a private method | Spring's proxy cannot intercept private methods; the method runs synchronously | Make the method at least package-private, or move it to a separate bean |

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
- `SPRING_TRANSACTION_SELF_INVOCATION` cannot always determine whether a class injects itself. True self-injections that use `@Autowired ApplicationContext` to retrieve the proxy will still trigger the finding.

**Scheduling and async rules**
- `SPRING_ASYNC_EXECUTOR_NOT_CONFIGURED` may fire even if a custom executor is configured in a separate `@Configuration` class not visible to the analyzer at scan time.

**General**
- The analyzer works on source text. Generated code (e.g., from annotation processors or build plugins) is not analyzed.
- Multi-module Maven/Gradle projects: rules that correlate across files (e.g., `SPRING_CONDITIONAL_VALUE_MISMATCH`) may not see the full picture if modules are analyzed independently.

If a finding does not apply to your project, it can be suppressed by adjusting the analyzer configuration. Refer to the [README](../README.md) for configuration options.
