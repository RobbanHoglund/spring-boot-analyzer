# Architecture

Spring Boot Analyzer is a Spring Boot application that accepts a Git repository URL, clones it into a temporary workspace, runs a suite of static analyzers over the source tree, and returns a structured set of findings. A compiled frontend bundle is served from the same process.

---

## Table of Contents

1. [High-level request flow](#high-level-request-flow)
2. [Package map](#package-map)
3. [API layer](#api-layer)
4. [Application layer](#application-layer)
5. [Workspace lifecycle](#workspace-lifecycle)
6. [Git integration](#git-integration)
7. [Analysis pipeline](#analysis-pipeline)
8. [Sub-analyzers](#sub-analyzers)
9. [Finding model](#finding-model)
10. [Finding normalization](#finding-normalization)
11. [Session registry and source snippets](#session-registry-and-source-snippets)
12. [Configuration reference](#configuration-reference)
13. [Frontend / backend boundary](#frontend--backend-boundary)
14. [Analysis modes](#analysis-modes)
15. [Key extension points](#key-extension-points)

---

## High-level request flow

```
HTTP client
    │
    │  POST /api/analyze
    ▼
AnalysisController
    │  builds GitRepositoryReference, delegates
    ▼
RepositoryAnalysisService
    ├─ WorkspaceService          creates temp dir under analyzer.workspace-root
    ├─ GitCloneService           clones repo into workspace/repository/ via JGit
    ├─ SpringBootProjectAnalyzer runs all sub-analyzers (see below)
    ├─ FindingNormalizer         Union-Find deduplication of overlapping findings
    ├─ GitHubLinkBuilder         enriches SourceLocations with GitHub permalink URLs
    └─ AnalysisSessionRegistry   stores session (analysisId → workspace path) in memory
    │
    │  returns AnalyzeRepositoryResponse (JSON)
    ▼
HTTP client

                         ┌── Source snippet fetch ──────────────────────────────┐
                         │  GET /api/analyses/{id}/source-snippet               │
                         │  AnalysisController → SourceSnippetService           │
                         │  SourceSnippetService looks up workspace path via     │
                         │  AnalysisSessionRegistry, reads file from disk        │
                         └──────────────────────────────────────────────────────┘
```

### Analysis pipeline (inside SpringBootProjectAnalyzer)

```
BuildFileAnalyzer          pom.xml / build.gradle / gradle.properties / libs.versions.toml
        │ BuildInfo
JavaSourceAnalyzer         walks src/main/java, JavaParser AST, Spring stereotype detection
        │ SourceAnalysis (DetectedClass list + structural findings)
ConfigurationAnalyzer      application.properties / application.yml (+ profile variants)
        │ ConfigurationAnalysis
GradleModelAnalyzer        EXTENDED mode only — Gradle Tooling API or CLI
        │ GradleModelAnalysis
        ▼
RuntimeStackAnalyzer       classifies web stack, persistence, virtual threads, etc.
        │ RuntimeStackAnalysis
HttpSurfaceAnalyzer        maps @RequestMapping endpoints + outbound HTTP calls + actuator
        │ HttpSurfaceAnalysis
SchedulingAnalyzer         finds @Scheduled / @Async methods
MessagingAnalyzer          finds @KafkaListener / @RabbitListener / @JmsListener / @SqsListener
        │
StaticPracticeFindingAnalyzer    source-code rule findings (transactions, async, exceptions …)
ConfigurationFindingAnalyzer     configuration + Gradle model rule findings
ObservabilityFindingAnalyzer     observability gap findings (@Timed vs @Observed, etc.)
TestingPracticeFindingAnalyzer   testing practice rule findings
CachingPracticeFindingAnalyzer   caching practice rule findings
ObservabilityGapFindingAnalyzer  observability coverage gap findings
        │
        └──► AnalysisResult (all findings + all sub-analyses combined)
```

---

## Package map

```
com.robbanhoglund.springbootanalyzer
│
├── SpringBootAnalyzerApplication.java      entry point
│
├── api/                                    HTTP layer
│   ├── AnalysisController.java             POST /api/analyze, GET /api/analyses/{id}/source-snippet
│   └── dto/                                request/response DTOs
│       ├── AnalyzeRepositoryRequest.java
│       ├── AnalyzeRepositoryResponse.java
│       ├── AnalysisMode.java               STATIC_ONLY | EXTENDED
│       ├── AnalyzeRepositoryCredentials.java
│       ├── SourceSnippetResponse.java
│       └── SourceSnippetLine.java
│
├── application/                            service layer
│   ├── RepositoryAnalysisService.java      orchestrates clone → analyze → normalize → register
│   ├── FindingNormalizer.java              Union-Find deduplication
│   ├── AnalysisSessionRegistry.java        in-memory map of analysisId → AnalysisSession
│   ├── SourceSnippetService.java           reads file lines from retained workspace
│   ├── InvalidSourceSnippetRequestException.java
│   └── SourceSnippetNotFoundException.java
│
├── analyzer/                               analysis pipeline
│   ├── StaticAnalyzer.java                 interface: analyze(ref, root, workspaceId)
│   ├── SpringBootProjectAnalyzer.java      orchestrator (implements StaticAnalyzer)
│   ├── BuildFileAnalyzer.java              build script parsing
│   ├── JavaSourceAnalyzer.java             Java AST analysis (JavaParser)
│   ├── StaticPracticeFindingAnalyzer.java  source-code practice rules
│   ├── ConfigurationFindingAnalyzer.java   configuration + Gradle model rules
│   ├── ObservabilityFindingAnalyzer.java   observability gap rules (@Timed vs @Observed)
│   ├── TestingPracticeFindingAnalyzer.java testing practice rules
│   ├── CachingPracticeFindingAnalyzer.java caching practice rules
│   ├── ObservabilityGapFindingAnalyzer.java observability coverage gap rules
│   │
│   ├── configuration/                      application.properties / YAML parsing
│   │   ├── ConfigurationAnalyzer.java
│   │   ├── ConfigurationFileScanner.java
│   │   ├── ConfigurationPropertiesClassAnalyzer.java
│   │   ├── PropertiesFileParser.java
│   │   ├── YamlConfigurationParser.java
│   │   ├── PropertyReferenceAnalyzer.java
│   │   ├── SensitivePropertyValueRedactor.java
│   │   └── SpringConfigurationMetadataCatalog.java
│   │
│   ├── gradle/                             Gradle Tooling API integration (EXTENDED mode)
│   │   ├── GradleModelAnalyzer.java        entry point
│   │   ├── GradleSafetyPolicy.java         decides whether a build is safe to run
│   │   ├── GradleToolingApiExecutionService.java
│   │   ├── GradleExecutionService.java
│   │   ├── GradleCommandBuilder.java
│   │   ├── GradleExecutableLocator.java
│   │   ├── GradleFailureClassifier.java
│   │   ├── GradleModelReportParser.java
│   │   ├── GradleJavaCompatibilityService.java
│   │   ├── GradleSettingsPluginScanner.java
│   │   ├── SettingsPluginWorkaroundService.java
│   │   └── plugin/                         plugin declaration scanning
│   │       ├── GradlePluginDeclarationScanner.java
│   │       ├── GradlePluginResolutionBridge.java
│   │       ├── GradleCorePluginDetector.java
│   │       └── GradleVersionCatalogPluginScanner.java
│   │
│   ├── http/
│   │   └── HttpSurfaceAnalyzer.java
│   │
│   ├── runtime/
│   │   └── RuntimeStackAnalyzer.java
│   │
│   ├── scheduling/
│   │   └── SchedulingAnalyzer.java
│   │
│   ├── messaging/
│   │   └── MessagingAnalyzer.java
│   │
│   └── model/                              domain model records
│       ├── AnalysisResult.java             top-level result record
│       ├── Finding.java                    individual finding record
│       ├── FindingRules.java               ~50 static rule constants
│       ├── FindingRule.java                rule descriptor record
│       ├── FindingFactory.java             fluent builder for Finding
│       ├── FindingOccurrence.java          single occurrence site for multi-site findings
│       ├── FindingCategory.java            enum: SECURITY, TRANSACTION, PERSISTENCE, …
│       ├── FindingSeverity.java            enum: ERROR, WARNING, INFO
│       ├── FindingConfidence.java          enum: HIGH, MEDIUM, LOW
│       ├── FindingRuntimeDetection.java
│       ├── SourceLocation.java             rich source position (file, line, column, symbol, URL)
│       ├── HighlightRange.java             UI highlight range within a source file
│       ├── RelatedFindingSignal.java       demoted finding attached to a primary finding
│       ├── BuildInfo.java                  extracted build metadata
│       ├── BuildTool.java                  enum: GRADLE, MAVEN, UNKNOWN
│       ├── DetectedClass.java              Spring-stereotyped class found by JavaSourceAnalyzer
│       ├── SpringComponentType.java        enum of Spring stereotypes
│       ├── configuration/                  configuration sub-model records
│       ├── gradle/                         Gradle sub-model records
│       ├── http/                           HTTP surface sub-model records
│       ├── messaging/                      messaging sub-model records
│       ├── runtime/                        runtime stack sub-model records
│       └── scheduling/                     scheduling sub-model records
│
├── config/
│   ├── AnalyzerProperties.java             @ConfigurationProperties(prefix="analyzer")
│   └── FrontendResourceConfiguration.java  serves compiled frontend from /static
│
├── git/
│   ├── GitCloneService.java                JGit clone + HEAD commit resolution
│   ├── GitRepositoryReference.java         URL + branch + credentials + mode
│   ├── GitRepositoryCredentials.java       username + token (never persisted)
│   ├── GitHubLinkBuilder.java              builds blob permalink URLs
│   ├── GitCloneException.java
│   ├── InvalidRepositoryReferenceException.java
│   └── UnsupportedRepositoryProtocolException.java
│
├── workspace/
│   ├── WorkspaceService.java               creates / deletes temp directories
│   └── WorkspaceCleanupScheduler.java      scheduled job for stale workspace removal
│
└── error/
    └── GlobalExceptionHandler.java         maps domain exceptions to HTTP status codes
```

---

## API layer

`AnalysisController` (`api/`) handles two endpoints:

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/analyze` | Runs a full analysis. Accepts `AnalyzeRepositoryRequest` (JSON), returns `AnalyzeRepositoryResponse`. |
| `GET` | `/api/analyses/{analysisId}/source-snippet` | Returns lines from a source file in the retained workspace. Query params: `path`, `startLine`, `endLine`, `context`. |

The controller maps request DTOs to domain types (`GitRepositoryReference`, `GitRepositoryCredentials`) and delegates all work to `RepositoryAnalysisService` or `SourceSnippetService`. It never touches the file system directly.

Credentials (username + token) are accepted in the request body, used only for the JGit clone, and never stored beyond the lifetime of the request.

---

## Application layer

`RepositoryAnalysisService` (`application/`) is the main coordinator:

1. Calls `WorkspaceService.createWorkspace()` — allocates a UUID-named directory under `analyzer.workspace-root`.
2. Calls `GitCloneService.cloneRepository()` — clones into `<workspace>/repository/`.
3. Calls `SpringBootProjectAnalyzer.analyze()` — runs all sub-analyzers.
4. Calls `FindingNormalizer.normalize()` — deduplicates overlapping findings.
5. Calls `GitHubLinkBuilder` to add GitHub permalink URLs to each `SourceLocation`.
6. Registers the session in `AnalysisSessionRegistry` so post-analysis source snippet reads can locate the workspace.
7. In the `finally` block, optionally deletes the workspace (see [Workspace lifecycle](#workspace-lifecycle)).

---

## Workspace lifecycle

Each analysis gets its own temporary directory. The lifecycle is:

```
createWorkspace()        UUID dir created under analyzer.workspace-root
       │
cloneRepository()        <workspace>/repository/ populated
       │
analyze()
       │
(finally block)
       ├── analyzer.cleanup-after-analysis = false  →  keep workspace
       ├── analysis completed successfully           →  keep workspace (source snippet browsing)
       ├── analyzer.workspace-keep-on-gradle-failure = true
       │   and Gradle failed                         →  keep workspace (debugging)
       └── otherwise                                 →  deleteWorkspace()
```

`WorkspaceCleanupScheduler` runs on a configurable interval and calls `WorkspaceService.deleteWorkspacesOlderThan(maxAge)` to remove directories that were retained but are now stale. The default max-age is 7 days; the scheduler runs 4 times per day by default.

`WorkspaceService.deleteWorkspace()` retries up to 5 times with increasing delays on `AccessDeniedException` (relevant on Windows). If all retries fail it schedules a background daemon thread that retries for up to 30 seconds.

---

## Git integration

`GitCloneService` (`git/`) uses the Eclipse JGit library to perform a shallow or full clone depending on the branch/commit reference. It also resolves the HEAD commit SHA after cloning, which is used to build stable GitHub blob permalink URLs.

`GitRepositoryReference` bundles:
- `repositoryUrl` — the HTTPS or SSH URL
- `branch` — branch name or commit SHA (null uses the remote default)
- `credentials` — `GitRepositoryCredentials` (username + token, nullable)
- `analysisMode` — `STATIC_ONLY` or `EXTENDED`

`GitHubLinkBuilder` constructs `https://github.com/{owner}/{repo}/blob/{sha}/{path}#L{start}-L{end}` URLs. These are attached to `SourceLocation` records after the initial analysis so that the UI can link directly to the relevant lines on GitHub.

---

## Analysis pipeline

`SpringBootProjectAnalyzer` implements `StaticAnalyzer` and runs each sub-analyzer in a fixed sequence, threading the outputs of earlier stages into the inputs of later ones:

| Stage | Analyzer | Input | Output |
|-------|----------|-------|--------|
| 1 | `BuildFileAnalyzer` | repository root | `BuildInfo` |
| 2 | `JavaSourceAnalyzer` | repository root | `SourceAnalysis` (classes + findings) |
| 3 | `ConfigurationAnalyzer` | repository root + `BuildInfo` | `ConfigurationAnalysis` + findings |
| 4 | `GradleModelAnalyzer` | repository root + `BuildInfo` + `AnalyzerProperties` | `GradleModelAnalysis` + findings |
| 5 | structure check | `DetectedClass` list | component-scan findings |
| 6 | `RuntimeStackAnalyzer` | root + `BuildInfo` + `GradleModelAnalysis` + `ConfigurationAnalysis` + classes | `RuntimeStackAnalysis` + findings |
| 7 | `HttpSurfaceAnalyzer` | root + `ConfigurationAnalysis` + `BuildInfo` + `WebStack` | `HttpSurfaceAnalysis` + findings |
| 8 | `StaticPracticeFindingAnalyzer` | root + all prior analyses + classes | findings |
| 9 | `ConfigurationFindingAnalyzer` | root + `BuildInfo` + `ConfigurationAnalysis` + `GradleModelAnalysis` | findings |
| 10 | `ObservabilityFindingAnalyzer` | root + `RuntimeStackAnalysis` | findings |
| 11 | `TestingPracticeFindingAnalyzer` | root + classes | findings |
| 12 | `CachingPracticeFindingAnalyzer` | root + classes | findings |
| 13 | `ObservabilityGapFindingAnalyzer` | root + `RuntimeStackAnalysis` + classes | findings |
| 14 | `SchedulingAnalyzer` | repository root | `SchedulingAnalysis` |
| 15 | `MessagingAnalyzer` | repository root | `MessagingAnalysis` |

All collected findings are assembled into an `AnalysisResult` record and returned. No deduplication happens inside the analyzer — that is done by `FindingNormalizer` in the application layer.

---

## Sub-analyzers

### BuildFileAnalyzer

Regex-based parsing of Gradle and Maven build files. Reads:
- `build.gradle` / `build.gradle.kts` — Spring Boot plugin version, Java toolchain version, dependency declarations
- `pom.xml` — Spring Boot parent version, Java source/target version, dependency management
- `gradle.properties` — `org.gradle.java.home`, version overrides
- `settings.gradle` / `settings.gradle.kts` — project name, included subprojects
- `libs.versions.toml` — Gradle version catalog entries

Produces `BuildInfo`: detected build tool, Spring Boot version string, Java version hint, whether Spring Boot was detected, and the full dependency list.

### JavaSourceAnalyzer

Walks `src/main/java`, parses every `.java` file with JavaParser (configured for Java 25 language level), and collects:
- Spring stereotype annotations (`@Service`, `@Repository`, `@Controller`, `@RestController`, `@Component`, `@Configuration`, `@SpringBootApplication`, etc.) → `DetectedClass` records
- Structural findings (default package usage, multiple main classes, components outside the root package)

### ConfigurationAnalyzer

Scans `src/main/resources` for:
- `application.properties`, `application.yml`
- Profile-specific variants: `application-{profile}.properties`, `application-{profile}.yml`

Delegates to `PropertiesFileParser` and `YamlConfigurationParser` to extract key-value pairs as `ApplicationProperty` records. `ConfigurationPropertiesClassAnalyzer` cross-references `@ConfigurationProperties`-annotated classes found by `JavaSourceAnalyzer`. `SensitivePropertyValueRedactor` strips plain-text secret values before they reach the API response.

### GradleModelAnalyzer

Only active in `EXTENDED` analysis mode. Uses the Gradle Tooling API (in-process) or the Gradle CLI (subprocess) to:
- Resolve the full dependency graph per configuration
- Detect declared Gradle plugins (via `GradlePluginDeclarationScanner`, `GradleVersionCatalogPluginScanner`, and `GradleCorePluginDetector`)

`GradleSafetyPolicy` is consulted before invoking Gradle. Builds are considered unsafe when the settings file applies unknown plugins that cannot be pre-resolved — in that case analysis falls back to `STATIC_ONLY` behavior. `GradlePluginResolutionBridge` pre-fetches plugins over HTTP before starting Gradle to work around plugin-portal connectivity issues in sandboxed environments.

### RuntimeStackAnalyzer

Classifies the detected runtime stacks from `DetectedClass` lists and build dependencies:
- Web stack: `SERVLET` (spring-boot-starter-web) vs `REACTIVE` (spring-boot-starter-webflux)
- Persistence: JPA/Hibernate, R2DBC, MongoDB, Redis, Cassandra, etc.
- Virtual threads: enabled via configuration or Spring Boot 3.2+ detection
- Messaging: Kafka, RabbitMQ, JMS, SQS

### HttpSurfaceAnalyzer

Walks Java source files to find:
- Inbound endpoints: classes/methods annotated with `@RequestMapping`, `@GetMapping`, `@PostMapping`, etc. → `InboundEndpoint` records
- Outbound HTTP calls: `RestTemplate`, `WebClient`, `RestClient`, `FeignClient` bean declarations → `OutboundEndpoint` records
- Actuator exposure: reads `management.endpoints.web.exposure.include` from `ConfigurationAnalysis`

### SchedulingAnalyzer

Finds methods annotated with `@Scheduled` or `@Async`. Extracts cron expressions, fixedRate/fixedDelay values, and whether an explicit time zone is set. Returns a `SchedulingAnalysis` containing `ScheduledTaskEndpoint` and `AsyncMethodEndpoint` lists.

### MessagingAnalyzer

Finds methods annotated with `@KafkaListener`, `@RabbitListener`, `@JmsListener`, or `@SqsListener`. Returns a `MessagingAnalysis` containing `MessageListenerEndpoint` records including the topics/queues and whether error handling is visible at the method level.

### StaticPracticeFindingAnalyzer

Source-code rule engine that uses the JavaParser AST to detect bad practices. Rules include: `@Transactional` self-invocation, `@Async` on private methods, empty catch blocks, `InterruptedException` swallowing, `@RequestBody` without `@Valid`, field injection, CORS wildcard origins, CSRF disabled, SQL query concatenation, and more. See `FindingRules` for the complete catalogue.

### ConfigurationFindingAnalyzer

Configuration and build model rules: plaintext secrets (`SPRING_SECRET_LITERAL`), profile drift (`SPRING_PROFILE_DRIFT`), risky production configuration (`SPRING_RISKY_PROD_CONFIG`), destructive DDL auto in prod (`SPRING_DDL_AUTO_DESTRUCTIVE_PROD`), exposed sensitive actuator endpoints (`SPRING_ACTUATOR_ENDPOINT_EXPOSED_PROD`), Flyway mixed with schema-mutating DDL, and others.

### ObservabilityFindingAnalyzer

Checks that `@Scheduled` methods and messaging listeners carry `@Timed` or `@Observed`. On Spring Boot 3+ projects, flags uses of `@Timed` and recommends `@Observed` instead (which produces both a metric and a trace span).

### TestingPracticeFindingAnalyzer

Detects testing anti-patterns: `@SpringBootTest` overuse where a slice test would suffice, integration test classes missing `@Transactional` rollback, excessive `@MockBean` usage, time-sensitive tests missing a fixed `Clock`, and `@SpringBootTest` starting an unnecessary mock web environment.

### CachingPracticeFindingAnalyzer

Detects caching anti-patterns: `@Cacheable` on `void` methods, `@Cacheable`/`@CachePut` returning mutable collections, cache annotations on private methods (silently ignored by Spring AOP), cache self-invocation, and `@CacheEvict` without `allEntries = true` on no-arg methods.

### ObservabilityGapFindingAnalyzer

Detects observability coverage gaps: async methods, event listeners, and exception handlers that carry no `@Observed` or `@Timed` annotation; `WebClient` constructed manually instead of via the auto-configured `WebClient.Builder` (which bypasses Micrometer tracing); and `@Observed` placed on private methods where Spring AOP cannot intercept.

---

## Finding model

A `Finding` is an immutable Java record. The key fields:

| Field | Type | Description |
|-------|------|-------------|
| `ruleId` | `String` | Stable rule identifier, e.g. `SPRING_SECRET_LITERAL`. Clients may suppress by rule ID. |
| `title` | `String` | Short human-readable rule name. |
| `severity` | `FindingSeverity` | `ERROR`, `WARNING`, or `INFO`. |
| `category` | `FindingCategory` | Broad area: `SECURITY`, `TRANSACTION`, `PERSISTENCE`, `SCHEDULING`, `OBSERVABILITY`, etc. |
| `confidence` | `FindingConfidence` | `HIGH`, `MEDIUM`, or `LOW`. |
| `message` | `String` | Context-specific headline for this occurrence. |
| `evidence` | `String` | Snippet of evidence from the source. |
| `recommendation` | `String` | Actionable fix guidance. |
| `whyBadPractice` | `String` | Educational explanation. |
| `possibleImpact` | `String` | Potential consequences. |
| `limitations` | `String` | Known false-positive scenarios. |
| `sourceFile` | `String` | Relative path to the source file (flat, for simple display). |
| `line` | `Integer` | 1-based line number. |
| `primaryLocation` | `SourceLocation` | Rich position: file, startLine, endLine, startColumn, endColumn, symbol, language, GitHub URL. |
| `highlightRanges` | `List<HighlightRange>` | Ranges to visually mark in the UI. |
| `occurrences` | `List<FindingOccurrence>` | Individual sites for multi-location findings. |
| `relatedSignals` | `List<RelatedFindingSignal>` | Findings demoted by `FindingNormalizer`. |
| `target` | `String` | The symbol that is the subject of the finding (method name, property key, etc.). |

### Creating findings

Analyzers create findings exclusively through `FindingFactory.Builder`:

```java
Finding f = FindingFactory.builder(FindingRules.SPRING_SECRET_LITERAL, FindingConfidence.HIGH)
        .shortMessage("Hardcoded password found")
        .whyBadPractice("Plain-text secrets are readable by anyone with file access.")
        .recommendation("Use an environment-variable placeholder: ${DB_PASSWORD}")
        .evidence("spring.datasource.password=hunter2  (line 12)")
        .source("src/main/resources/application.properties", 12)
        .target("spring.datasource.password")
        .build();
```

The `builder(FindingRule, FindingConfidence)` overload is strongly preferred because it ensures `ruleId`, `title`, `severity`, `category`, and `runtimeDetection` stay consistent with the catalogue entry in `FindingRules`.

### Rule catalogue

All 86 rule constants live in `FindingRules.java`. Rule IDs are stable public identifiers — do not rename them without a migration step, as clients may suppress specific rules by ID.

---

## Finding normalization

`FindingNormalizer` deduplicates the raw finding list before it is returned in the API response. The algorithm is a Union-Find over the findings:

1. Two catch-block-level findings (rules in `CATCH_BLOCK_RULES`) are grouped if they share the same source file and the same catch-clause start line.
2. Two method-level findings are grouped if they share the same source file and enclosing method (`target`), and their rule ID pair appears in `TARGET_OVERLAP_PAIRS`.
3. Within each group, the finding with the highest dominance score (defined in the `DOMINANCE` map) becomes the primary. All others are demoted to `relatedSignals` on the primary.
4. Only primary findings appear in the returned list. Demoted findings are accessible through `Finding.relatedSignals()`.

Groups of size 1 are returned as-is. Findings with no `ruleId` are always treated as non-overlapping.

---

## Session registry and source snippets

After a successful analysis, `RepositoryAnalysisService` registers an `AnalysisSession` in `AnalysisSessionRegistry`:

```
analysisId (UUID string) → AnalysisSession { repositoryRoot, repositoryUrl, branch, commitSha }
```

The registry is a `ConcurrentHashMap` held in memory for the lifetime of the process. Sessions are never evicted explicitly — stale workspace directories are removed by `WorkspaceCleanupScheduler`, which will cause subsequent source snippet requests for those sessions to fail with 404.

`SourceSnippetService` handles `GET /api/analyses/{id}/source-snippet`:
1. Looks up the `AnalysisSession` in the registry.
2. Resolves the requested `path` relative to `repositoryRoot`.
3. Reads the requested line range (plus `context` lines of surrounding context).
4. Returns a `SourceSnippetResponse` with line number, content, and highlight flags.

---

## Configuration reference

All externalized configuration is bound from the `analyzer.*` namespace via `AnalyzerProperties` (`@ConfigurationProperties(prefix = "analyzer")`).

| Property | Default | Description |
|----------|---------|-------------|
| `analyzer.workspace-root` | — | Directory where per-analysis workspaces are created. **Required.** |
| `analyzer.cleanup-after-analysis` | `true` | Delete workspace when analysis finishes. Set to `false` to retain all workspaces. |
| `analyzer.workspace-keep-on-gradle-failure` | `false` | Retain workspace when Gradle model analysis fails (useful for debugging). |
| `analyzer.scheduled-workspace-cleanup.enabled` | `true` | Enable the periodic stale-workspace cleanup job. |
| `analyzer.scheduled-workspace-cleanup.max-age` | `7d` | Workspaces older than this are deleted by the scheduled job. |
| `analyzer.scheduled-workspace-cleanup.runs-per-day` | `4` | How many times per day the cleanup runs. |
| `analyzer.gradle.enabled` | `true` | Whether to attempt Gradle model analysis in EXTENDED mode. |
| `analyzer.gradle.timeout` | `120s` | Per-invocation Gradle timeout. |
| `analyzer.gradle.execution-mode` | `TOOLING_API` | `TOOLING_API` (in-process) or `CLI` (subprocess). |
| `analyzer.gradle.diagnostic-gradle-version` | `9.5.0` | Gradle wrapper version to use when the project has no wrapper. |
| `analyzer.gradle.distribution-cache` | system temp | Directory for cached Gradle distributions. |
| `analyzer.gradle.max-output-bytes` | `1048576` | Truncates Gradle stdout/stderr at this byte count (1 MiB). |
| `analyzer.gradle.max-resolved-dependencies` | `10000` | Caps dependency coordinates in the result. |
| `analyzer.gradle.plugin-resolution-bridge.*` | — | Pre-fetches Gradle plugins over HTTP before invoking Gradle. |
| `analyzer.gradle.settings-plugin-workarounds.*` | — | Strips non-essential settings plugins that block Gradle invocation. |

---

## Frontend / backend boundary

The frontend is a compiled JavaScript bundle embedded in the Spring Boot JAR under `src/main/resources/static/`. `FrontendResourceConfiguration` registers a resource handler that serves any path not matched by `/api/**` from that bundle, enabling client-side routing.

At runtime:
- `/api/*` — REST JSON endpoints
- everything else — frontend bundle (SPA shell)

The frontend calls `POST /api/analyze` to trigger analysis and then `GET /api/analyses/{id}/source-snippet` to load code excerpts on demand. The analysis result JSON is large and includes the full finding list; the UI fetches source snippets lazily as the user navigates findings.

---

## Analysis modes

| Mode | Description |
|------|-------------|
| `STATIC_ONLY` | Default. No build tool execution. Safe for any repository. |
| `EXTENDED` | Opt-in. Invokes the Gradle Tooling API (or CLI) to resolve the dependency graph and declared plugins. Only safe if `GradleSafetyPolicy` approves the build. Has configurable safety gates (plugin preflight checks, settings plugin workarounds, plugin resolution bridge). |

The mode is sent by the client in the `analysisMode` field of `AnalyzeRepositoryRequest` and carried through as `GitRepositoryReference.analysisMode()`. `GradleModelAnalyzer` checks it before deciding whether to invoke Gradle.

---

## Key extension points

**Adding a new finding rule:**
1. Add a constant to `FindingRules.java` using the private `rule(...)` helper. Choose a stable, descriptive rule ID.
2. Implement detection logic in the appropriate analyzer (or create a new one implementing the same `analyze(...)` pattern).
3. Emit findings using `FindingFactory.builder(FindingRules.YOUR_RULE, confidence)...build()`.

**Adding a new sub-analyzer:**
1. Create a class in the appropriate sub-package under `analyzer/`.
2. Annotate it `@Component` and inject it into `SpringBootProjectAnalyzer`.
3. Add a call to it in `SpringBootProjectAnalyzer.analyze()` at the appropriate stage (observing data dependencies on earlier stages).
4. If the analyzer returns structural data (not just findings), define a result record and add it to `AnalysisResult`.

**Adding a new configuration property:**
Add a field to `AnalyzerProperties` (or one of its nested records). Spring Boot will bind it from `application.properties` / environment variables automatically via `@ConfigurationProperties`.
