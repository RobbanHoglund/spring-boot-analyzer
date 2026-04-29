# Spring Boot Analyzer

A static analysis tool for Spring Boot projects. Point it at any Git repository and get a structured report of findings, component inventory, HTTP surface, configuration risks, and anti-patterns — without cloning to your machine, without executing the project, and without a running JVM.

---

## What it does

Spring Boot Analyzer clones a repository into a temporary workspace and inspects it using [JGit](https://www.eclipse.org/jgit/), [JavaParser](https://javaparser.org/), and the Gradle Tooling API. It produces a prioritized list of findings across security, configuration, persistence, transactions, HTTP surface, and code quality.

**It never executes code from the analyzed repository.** No Gradle tasks, no Maven goals, no application startup, no test runs.

---

## Features

### Component inventory
Detects Spring stereotypes and maps the application's component structure:
`@SpringBootApplication`, `@RestController`, `@Controller`, `@ControllerAdvice`, `@RestControllerAdvice`, `@Service`, `@Repository`, `@Component`, `@Configuration`, `@Entity`, `@ConfigurationProperties`

### HTTP surface analysis
- Inbound REST endpoints via Spring MVC (`@GetMapping`, `@PostMapping`, `@PutMapping`, `@PatchMapping`, `@DeleteMapping`, `@RequestMapping`)
- WebFlux functional routes (`route()`, `GET()`, `POST()`, etc.)
- Outbound HTTP calls via `RestTemplate`, `WebClient`, `HttpClient`, and `@FeignClient`
- Actuator endpoint exposure (`management.endpoints.web.exposure.*`)
- Base URL resolution from property placeholders

### Build analysis
- Gradle and Maven support
- Spring Boot version detection with confidence level
- Java version hint extraction
- Full dependency inventory

### Configuration analysis
- `application.properties` and `application.yml` parsing including profile-specific files
- Property placeholder resolution and cross-profile drift detection
- Sensitive value identification and redaction
- Spring configuration metadata catalog integration
- `@ConfigurationProperties` class extraction

### Gradle model analysis *(extended mode)*
- Resolved dependency tree via Gradle Tooling API
- Plugin declarations and version catalog support
- Java toolchain detection

---

## Findings

The analyzer produces 40+ rules across the following categories. Each finding includes severity, confidence, why it matters, possible impact, recommendation, evidence, and a direct link to the source location in GitHub.

### Security
| Rule | Severity | Description |
|------|----------|-------------|
| `SPRING_SECRET_LITERAL` | WARNING | Sensitive property uses a literal value in config |
| `SPRING_SECRET_WEAK_PLACEHOLDER_DEFAULT` | WARNING | Secret placeholder has a weak default value |
| `SPRING_SECRET_MULTI_PROFILE` | INFO | Sensitive property duplicated across multiple profiles |
| `SPRING_RAW_EXCEPTION_MESSAGE_HTTP` | WARNING | Raw exception message exposed in HTTP response |
| `SPRING_CSRF_DISABLED` | WARNING | CSRF protection disabled via `csrf().disable()` or `csrf(AbstractHttpConfigurer::disable)` |
| `SPRING_CORS_ALLOW_ALL` | WARNING | CORS configured with `allowedOrigins("*")` |
| `SPRING_REQUEST_PARAM_SENSITIVE_NAME` | WARNING | Password, token, or secret passed as a URL parameter or path variable |
| `SPRING_SECURITY_STARTER_MISSING` | INFO | Web application has no Spring Security dependency |
| `SPRING_HTTP_PLAIN_URL` | WARNING | Outbound HTTP client uses plain `http://` URL |

### Configuration
| Rule | Severity | Description |
|------|----------|-------------|
| `SPRING_RISKY_PROD_CONFIG` | WARNING | Risky property active in production-oriented profile (`debug=true`, `show-sql`, `lazy-initialization`, etc.) |
| `SPRING_PROFILE_DRIFT` | INFO | Same property has different values across profiles |
| `SPRING_VALUE_NO_DEFAULT` | WARNING | `@Value("${prop}")` without a `:default` fallback — causes hard startup failure if property is absent |
| `SPRING_JPA_SHOW_SQL_PROD` | WARNING | `spring.jpa.show-sql=true` in production profile — bypasses logging framework |
| `SPRING_CONFIGURATION_PROPERTIES_NOT_VALIDATED` | INFO | `@ConfigurationProperties` class without `@Validated` — constraint annotations are silently ignored |

### Persistence
| Rule | Severity | Description |
|------|----------|-------------|
| `SPRING_MODIFYING_NO_TRANSACTION` | **ERROR** | `@Modifying` query without `@Transactional` — always throws `TransactionRequiredException` at runtime |
| `SPRING_DDL_AUTO_DESTRUCTIVE_PROD` | **ERROR** | `hibernate.ddl-auto=create` or `create-drop` in production profile — drops and recreates tables on startup |
| `SPRING_JPA_OPEN_IN_VIEW` | WARNING | `spring.jpa.open-in-view` not explicitly disabled — defaults to `true`, silently enabling lazy loading outside the service layer |
| `SPRING_FLYWAY_DDL_AUTO_MIX` | WARNING | Flyway migration combined with schema-mutating Hibernate DDL |
| `SPRING_FLYWAY_MISSING_MIGRATIONS` | WARNING | Flyway is enabled but no migration files were found |
| `SPRING_JPA_ONETOMANY_MISSING_MAPPED_BY` | WARNING | `@OneToMany` or `@ManyToMany` without `mappedBy` — creates an unintended join table |
| `SPRING_JPA_MANYTOONE_EAGER_DEFAULT` | INFO | `@ManyToOne` or `@OneToOne` without explicit fetch type — eager by default |

### Transactions
| Rule | Severity | Description |
|------|----------|-------------|
| `SPRING_TRANSACTION_SELF_INVOCATION` | INFO | `@Transactional` method called via `this` — bypasses the proxy |
| `SPRING_TRANSACTION_PRIVATE_METHOD` | INFO | `@Transactional` on a private method — proxy cannot intercept it |
| `SPRING_TRANSACTION_MISSING_BOUNDARY` | INFO | Write-heavy service method with no visible transaction boundary |
| `SPRING_TRANSACTIONAL_ON_SCHEDULED` | WARNING | `@Transactional` and `@Scheduled` on the same method — scheduler thread has no outer transaction context |

### Scheduling
| Rule | Severity | Description |
|------|----------|-------------|
| `SPRING_SCHEDULED_SIDE_EFFECT` | WARNING | Scheduled method performs HTTP calls or database writes without distributed coordination |
| `SPRING_SCHEDULED_SHORT_INTERVAL` | INFO | Scheduled job runs on an interval shorter than one minute |
| `SPRING_SCHEDULED_CRON_NO_ZONE` | INFO | Cron expression without an explicit time zone |

### HTTP clients
| Rule | Severity | Description |
|------|----------|-------------|
| `SPRING_HTTP_CLIENT_NO_TIMEOUT` | WARNING | HTTP client has no visible timeout configuration |
| `SPRING_HTTP_CLIENT_NO_RESILIENCE` | INFO | HTTP client has no visible retry or circuit-breaker handling |

### Exception handling
| Rule | Severity | Description |
|------|----------|-------------|
| `JAVA_EMPTY_CATCH_BLOCK` | WARNING | Empty catch block |
| `SPRING_SWALLOWED_EXCEPTION_FALLBACK` | INFO | Exception swallowed and replaced with a fallback value |
| `SPRING_INTERRUPTED_EXCEPTION_SWALLOWED` | WARNING | `InterruptedException` swallowed — thread interrupt status not restored |
| `SPRING_BROAD_FATAL_ERROR_CATCH` | WARNING | Catching `Error` or `Throwable` broadly |
| `SPRING_BROAD_EXCEPTION_SPRING_BOUNDARY` | INFO | Broad exception catch in a Spring lifecycle boundary |
| `SPRING_ASYNC_VOID_SWALLOWED_EXCEPTION` | INFO | `@Async` void method with no exception handling — failures route to `AsyncUncaughtExceptionHandler` and are easily lost |
| `SPRING_MESSAGING_LISTENER_NO_ERROR_HANDLER` | INFO | `@KafkaListener` / `@RabbitListener` / `@JmsListener` with no exception handling |

### Async & messaging
| Rule | Severity | Description |
|------|----------|-------------|
| `SPRING_ASYNC_PROXY_BYPASS` | WARNING | `@Async` on a private method — proxy cannot intercept it, executes synchronously |

### Startup
| Rule | Severity | Description |
|------|----------|-------------|
| `SPRING_STARTUP_SIDE_EFFECT` | WARNING | Startup hook (`@PostConstruct`, `CommandLineRunner`, `ApplicationReadyEvent`) performs HTTP calls or database writes |

### Conditional beans
| Rule | Severity | Description |
|------|----------|-------------|
| `SPRING_CONDITIONAL_VALUE_MISMATCH` | WARNING | Configured provider value does not match any `@ConditionalOnProperty` bean |
| `SPRING_CONDITIONAL_MATCH_IF_MISSING_OVERLAP` | WARNING | Multiple conditional beans all default to `matchIfMissing=true` |

### Validation
| Rule | Severity | Description |
|------|----------|-------------|
| `SPRING_REQUEST_BODY_NO_VALID` | INFO | `@RequestBody` parameter is missing `@Valid` |

### Maintainability
| Rule | Severity | Description |
|------|----------|-------------|
| `SPRING_FIELD_INJECTION` | INFO | `@Autowired` field injection — hides dependencies, enables circular wiring |
| `SPRING_BEAN_ON_NON_CONFIGURATION` | WARNING | `@Bean` method in a class without `@Configuration` — lite mode, no CGLIB proxy |
| `SPRING_SIDE_EFFECT_ORCHESTRATION_NO_BOUNDARY` | INFO | Service method coordinates multiple side effects with no explicit consistency boundary |
| `SPRING_REPEATED_FALLBACK_PARSING_PATTERN` | INFO | Silent parse-and-fallback pattern repeated across multiple classes |
| `SPRING_PRINT_STACK_TRACE` | INFO | `printStackTrace()` used instead of structured logging |

### API surface
| Rule | Severity | Description |
|------|----------|-------------|
| `SPRING_REQUEST_MAPPING_NO_METHOD` | INFO | `@RequestMapping` on a controller method without an HTTP method constraint — accepts all verbs |

---

## Requirements

| Component | Version |
|-----------|---------|
| Java | 25 |
| Node.js | 18+ |
| Gradle | via wrapper (`./gradlew`) |

---

## Quick start

**1. Start the backend**

```bash
./gradlew bootRun
```

**2. Open the UI**

```
http://localhost:8085/
```

The UI is served by Spring Boot from `frontend/dist`. A pre-built frontend is included; run the full build below if you need to rebuild it.

---

## Building

**Build everything (frontend + backend)**

```bash
cd frontend && npm install && npm run build && cd ..
./gradlew bootRun
```

**Run tests**

```bash
./gradlew clean test
```

---

## Frontend development

The Vite dev server proxies `/api` requests to `http://localhost:8085`.

```bash
# Terminal 1 — backend
./gradlew bootRun

# Terminal 2 — frontend
cd frontend
npm install
npm run dev
```

Open `http://localhost:5173/` for the dev UI with hot reload.

---

## API

### Analyze a repository

```
POST /api/analyze
Content-Type: application/json
```

**Public repository**
```bash
curl -X POST http://localhost:8085/api/analyze \
  -H "Content-Type: application/json" \
  -d '{
    "repositoryUrl": "https://github.com/example/my-spring-app.git",
    "branch": "main"
  }'
```

**Private HTTPS repository**
```bash
curl -X POST http://localhost:8085/api/analyze \
  -H "Content-Type: application/json" \
  -d '{
    "repositoryUrl": "https://github.com/example/private-app.git",
    "branch": "main",
    "credentials": {
      "username": "octocat",
      "token": "ghp_..."
    }
  }'
```

**Extended analysis** *(includes Gradle dependency resolution)*
```bash
curl -X POST http://localhost:8085/api/analyze \
  -H "Content-Type: application/json" \
  -d '{
    "repositoryUrl": "https://github.com/example/my-spring-app.git",
    "branch": "main",
    "analysisMode": "EXTENDED"
  }'
```

**`analysisMode` values**

| Value | Description |
|-------|-------------|
| `STATIC_ONLY` | Default. Build file parsing, Java source analysis, configuration analysis. |
| `EXTENDED` | All of `STATIC_ONLY` plus Gradle Tooling API dependency resolution. |

### Fetch a source snippet

```
GET /api/analyses/{analysisId}/source-snippet?path=src/main/java/...&startLine=10&endLine=20&context=4
```

Returns a source snippet around a finding location. Used by the UI to render inline code previews.

---

## UI

The browser UI provides two tabs:

**Analyze** — Enter a repository URL and branch, select an optional token profile, run the analysis, and browse findings filtered by severity and category. Each finding shows the rule, severity, confidence, explanation, recommendation, evidence, and an inline source preview with a direct link to GitHub.

**Settings** — Manage HTTPS token profiles and saved repository profiles. Both are stored in browser `localStorage` and never sent to the backend except during an active analysis request.

---

## Security model

- **No server-side credential storage.** HTTPS tokens are held in browser `localStorage` and transmitted only as part of an `/api/analyze` request. The backend does not persist them.
- **No code execution.** The analyzer performs purely static analysis. It does not run Gradle, Maven, shell scripts, or any code from the cloned repository.
- **Temporary workspaces.** Cloned repositories are written to a temporary workspace directory and cleaned up after analysis.
- **SSH repositories** use the SSH configuration of the server running the backend (e.g., `~/.ssh/known_hosts`, agent forwarding).

---

## Tech stack

| Layer | Technology |
|-------|-----------|
| Backend | Spring Boot 3.5, Java 25 |
| Git operations | JGit 7.6 |
| Java source parsing | JavaParser 3.28 |
| Build introspection | Gradle Tooling API 9.5 |
| Frontend | TypeScript, Vite |
