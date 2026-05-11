# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

### Added

- Initial Spring Boot static analysis engine with 45+ rules across security, configuration, persistence, transactions, HTTP clients, exception handling, observability, and maintainability categories
- Component inventory detection for `@SpringBootApplication`, `@RestController`, `@Service`, `@Repository`, `@Component`, `@Configuration`, `@Entity`, and `@ConfigurationProperties`
- HTTP surface analysis covering inbound REST endpoints, outbound HTTP calls, and actuator exposure
- Scheduling and messaging analysis for `@Scheduled`, `@Async`, `@KafkaListener`, `@RabbitListener`, `@JmsListener`, and `@SqsListener`
- Configuration analysis for `application.properties`, `application.yml`, and profile-specific variants, with sensitive value redaction
- Gradle model analysis in EXTENDED mode via the Gradle Tooling API
- STATIC_ONLY and EXTENDED analysis modes
- Private repository support via HTTPS credentials
- GitHub permalink generation for findings
- Browser UI with findings table, configuration review, HTTP surface, components, dependencies, and Gradle model tabs
- CI pipeline (GitHub Actions) for backend and frontend
- Apache 2.0 license

### Changed

### Fixed

### Security

---

This project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
