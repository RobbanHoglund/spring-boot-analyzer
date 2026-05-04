# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build frontend + backend
cd frontend && npm install && npm run build && cd ..
./gradlew bootRun

# Backend only (uses pre-built frontend from frontend/dist)
./gradlew bootRun

# Run all tests
./gradlew clean test

# Run a single test class
./gradlew test --tests "com.robbanhoglund.springbootanalyzer.analyzer.JavaSourceAnalyzerTest"

# Frontend dev server (hot reload, proxies /api to :8085)
cd frontend && npm install && npm run dev
```

App runs on `http://localhost:8085`. Frontend dev server on `http://localhost:5173`.

## Architecture

The application is a static analysis tool for Spring Boot projects. It clones a target Git repository and analyzes it without executing any of its code.

### Request flow

```
POST /api/analyze
  → AnalysisController
  → RepositoryAnalysisService
      → WorkspaceService       (creates a temp workspace dir)
      → GitCloneService        (clones the target repo via JGit)
      → SpringBootProjectAnalyzer  (orchestrates all sub-analyzers)
      → AnalysisSessionRegistry   (stores result in-memory by session ID)
  ← AnalyzeRepositoryResponse (findings + summary)
```

Source snippets are fetched separately via `GET /api/analyses/{id}/source-snippet` — the session registry holds the workspace path so the backend can read file content post-analysis.

### Sub-analyzers (all called by `SpringBootProjectAnalyzer`)

| Analyzer | What it does |
|---|---|
| `BuildFileAnalyzer` | Parses `build.gradle` / `pom.xml` — Spring Boot version, Java version, dependencies |
| `JavaSourceAnalyzer` | Walks `.java` files with JavaParser — detects Spring stereotypes, component inventory |
| `ConfigurationAnalyzer` | Parses `application.properties` / `application.yml` across profiles — sensitive values, drift, placeholders |
| `HttpSurfaceAnalyzer` | Detects inbound endpoints (MVC/WebFlux) and outbound HTTP clients |
| `RuntimeStackAnalyzer` | Infers web stack (MVC vs WebFlux) and virtual thread usage |
| `GradleModelAnalyzer` | Optional EXTENDED mode — runs Gradle Tooling API for resolved dependency tree |
| `StaticPracticeFindingAnalyzer` | Cross-cutting rule engine — emits `Finding` objects using all of the above outputs |

### Finding model

All rules are declared as constants in `FindingRules`. Each `Finding` has a `FindingRule` (id, severity, category), `FindingConfidence`, list of `FindingOccurrence` (source locations + evidence), and optional `HighlightRange` for inline code previews.

`FindingFactory` is the sole constructor for findings — use it rather than constructing `Finding` directly.

### Adding a new rule

1. Declare a `FindingRule` constant in `FindingRules`.
2. Add detection logic in the appropriate sub-analyzer or in `StaticPracticeFindingAnalyzer`.
3. Add a test in `StaticPracticeFindingAnalyzerTest` or the relevant analyzer test using `@TempDir` + `Files.writeString` to write synthetic source files.

### Frontend

TypeScript + Vite, no framework. State is managed via plain store modules (`analysisSessionStore.ts`, `repositoryStore.ts`, `tokenStore.ts`). The build output lands in `frontend/dist/` and is served by Spring Boot as static resources. Token profiles and saved repository profiles live in browser `localStorage` only — never persisted server-side.

### Gradle EXTENDED mode

The Gradle sub-system is intentionally isolated and defensive. `GradleSafetyPolicy` enforces what is allowed. `GradlePluginResolutionBridge` pre-fetches declared plugins into a local Maven cache before Gradle runs, which avoids network issues in restricted environments. The init script injected by `GradleCommandBuilder` controls plugin repositories and never executes user build logic.

## Project rules

### No hardcoded secrets
Never hardcode credentials, tokens, API keys, passwords, or any secrets — not in source files, not in tests, not in comments.
Use environment variables or a secrets manager. If a secret appears in git history, consider it compromised.

### Dependency discipline
Do not add a new package/library unless the standard library or existing dependencies genuinely cannot cover the need.
Each new dependency carries supply-chain risk, version conflicts, and maintenance burden. Prefer solving the problem with what is already present.

### Testing
Write tests for non-trivial logic. Prefer integration tests over unit tests when the code touches the database, filesystem, or external APIs — mocks that diverge from reality cause false confidence.
Tests must be idempotent: safe to run multiple times and in any order.
Migrations and setup scripts must also be idempotent.

Tests for analyzer logic follow a consistent pattern: instantiate the analyzer directly (no Spring context), write synthetic Java/config files into a `@TempDir`, call `analyze()`, assert on findings. Follow this pattern rather than introducing Spring Boot test slices for unit-level analyzer tests.
