# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

### Added

- New **Spring Boot 3 Migration** category with six rules: `SPRING_SECURITY_WEBSECURITYCONFIGURERADAPTER`, `SPRING_SECURITY_ANTMATCHERS_REMOVED`, `SPRING_SECURITY_ENABLE_GLOBAL_METHOD_SECURITY`, `SPRING_JAKARTA_NAMESPACE_ON_BOOT3` (gated on a detected Spring Boot 3+ version), `SPRING_PROFILES_PROPERTY_DEPRECATED`, and `SPRING_ACTUATOR_HTTPTRACE_RENAMED`
- Four additional security rules: `SPRING_JDBC_URL_EMBEDDED_CREDENTIALS`, `SPRING_DEFAULT_USER_PASSWORD_LITERAL`, `SPRING_LOGGING_AUTH_HEADER`, and `SPRING_BCRYPT_LOW_STRENGTH`
- Three additional reliability rules: `SPRING_RESTTEMPLATE_NEW_PER_REQUEST`, `SPRING_JPA_QUERY_NO_PAGINATION`, and `SPRING_REQUIRES_NEW_IN_LOOP`

### Removed

- `SPRING_TRANSACTIONAL_ON_SCHEDULED` — its premise was wrong: the scheduler invokes the method through the transactional proxy, so the transaction behaves normally
- `SPRING_RESTTEMPLATE_NO_HTTP_STATUS_HANDLER` — RestTemplate's default error handler already throws with the full status, headers and body; suppression entries for either ID are now reported as unknown

### Changed

- `SPRING_ASYNC_NON_FUTURE_RETURN` is now ERROR (every call fails on Spring Framework 6+), treats `Mono`/`Flux` as unsupported, and applies only when `@EnableAsync` is present
- `SPRING_TX_EVENT_LISTENER_WRITE_LOST` is now WARNING (write calls are matched by name) and exempts `@Async` listeners when `@EnableAsync` is present
- False positives removed: `@Scheduled(initialDelay = …)` on Spring Boot 3.2+, SnakeYAML `new Yaml()` on Spring Boot 3.1+, every `@ExceptionHandler` whose type name ends in `Exception`, `@DataJpaTest` and real-server tests in the testing rules, POST form parameters, redirects with a fixed host, packaged-only DevTools checks, classpath-selected cache providers, unmodifiable cached collections, and Feign clients with configured timeouts
- `SPRING_SCHEDULED_TRIGGER_MISSING_OR_CONFLICTING` also reports a cron trigger combined with `initialDelay`; `SPRING_VALUE_NO_DEFAULT` now reports only properties that some profiles lack; `SPRING_HIBERNATE_VERSION_MISMATCH` and `SPRING_BOOT3_REQUIRES_JAVA17` cover Spring Boot 4
- The results view keeps each rule's catalog title (matching Settings), labels findings without a source file as project-wide, explains confidence, runtime detection and rule IDs in badge tooltips, and no longer counts retired rule IDs as disabled

## [0.1.0] — 2026-05-11

### Added

- 86 static-analysis rules across 18 categories: Security, Configuration, Profile Drift, Persistence, Transaction, Scheduling, HTTP Clients, Exception Handling, Validation, Maintainability, Observability, Caching, Testing Practice, Conditional Beans, Startup, Actuator, API Surface, Dependency Compatibility
- Java version compatibility rules: `SPRING_BOOT3_REQUIRES_JAVA17` (ERROR) and `SPRING_VIRTUAL_THREADS_JAVA_TOO_OLD` (WARNING)
- Finding suppression via `.analyzer-suppress.yml` in the analyzed repository root — suppress by `ruleId` with optional `reason`
- Collapsible dependency tree in the Dependencies tab, grouped by Maven group ID, Spring groups pinned to the top
- Severity filter toggle buttons and per-category counts in the category dropdown
- 5 profile-drift detection rules (cross-profile property drift, H2 in non-test profiles, Flyway disabled in test, etc.)
- 8 observability gap rules (`@Observed` / `@Timed` coverage for controllers, listeners, async methods, event listeners, exception handlers)
- 5 caching-practice rules (`@Cacheable` on void methods, mutable return types, private methods, self-invocation, evict without `allEntries`)
- 5 testing-practice rules (`@SpringBootTest` overuse, missing transactional rollback, `@MockBean` overuse, missing fixed clock, unnecessary web environment)
- Structured Markdown report export (download as `.md`) alongside existing JSON, SARIF 2.1.0, and plain-text summary exports
- CLI mode: `java -jar spring-boot-analyzer.jar --repo <url>` with `--format text|json|sarif`, `--fail-on`, `--quiet`, and `--output` flags
- Multi-stage Dockerfile; `docker run -p 8085:8085 spring-boot-analyzer` starts the full stack
- STATIC_ONLY and EXTENDED analysis modes; EXTENDED uses the Gradle Tooling API for resolved dependency versions
- GitHub permalink generation for every finding location
- Real-time analysis progress streamed to the browser
- Settings page: HTTPS token profiles and saved repository profiles stored in browser `localStorage`
- Apache 2.0 license

### Changed

- Dependency tree replaces the flat resolved-dependencies table
- Gradle model uses resolved library versions in finding rules (Hibernate version mismatch, Spring Boot BOM checks)
- CI pipeline (GitHub Actions) runs `spotlessCheck` on every push and PR

### Fixed

- Section anchor links in the results view now scroll below the sticky jump navigation bar (`scroll-margin-top`)
- Deep-link targets for HTTP subsections (inbound, outbound, actuator) point to the correct table rather than the top of the HTTP section

---

This project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
