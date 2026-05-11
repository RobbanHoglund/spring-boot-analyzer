# Contributing to spring-boot-analyzer

## Prerequisites

| Tool | Minimum version |
|------|----------------|
| Java | 25 |
| Node | 22 |
| Git  | any recent     |

## Building and running locally

**Backend**

```bash
./gradlew bootRun
```

The backend starts on `http://localhost:8080` by default. Pass a project path via the UI or the API.

**Frontend** (separate dev server with HMR)

```bash
cd frontend
npm install
npm run dev
```

The frontend dev server proxies API requests to the backend. Run both together during development.

## Running tests

```bash
# Backend (JUnit 5)
./gradlew test

# Frontend (Vitest)
cd frontend && npm test
```

All tests must pass before a PR is merged. Do not submit a PR that breaks an existing test without an explicit justification.

## Project structure

```
src/main/java/com/robbanhoglund/springbootanalyzer/
  analyzer/
    model/          – Finding, FindingRule, FindingRules, FindingFactory, FindingOccurrence,
                      SourceLocation — the core domain types
    BuildFileAnalyzer.java              – Gradle build file inspection
    JavaSourceAnalyzer.java             – JavaParser-based AST analysis
    SpringBootProjectAnalyzer.java      – Orchestrates all analyzers for a project
    StaticPracticeFindingAnalyzer.java  – Static best-practice rule evaluation
    ConfigurationFindingAnalyzer.java   – application.yml / .properties analysis
    ObservabilityFindingAnalyzer.java   – Observability gap detection
  application/
    FindingNormalizer.java          – Post-processing and deduplication
  config/
    AnalyzerProperties.java         – Configuration properties (analysis mode, etc.)

frontend/src/                       – Vanilla TypeScript, DOM manipulation, no framework
```

## Analysis modes

- `STATIC_ONLY` (default) — file-based analysis only; safe and fast, no build execution.
- `EXTENDED` — opt-in; uses the Gradle Tooling API to resolve the dependency graph. Requires a working Gradle wrapper in the target project.

## How to add a new finding rule

1. **Declare the rule** in `FindingRules.java`:

   ```java
   public static final FindingRule MY_NEW_RULE = rule(
       "MY_NEW_RULE",                        // stable ID — never rename after release
       "Short human-readable title",
       FindingSeverity.WARNING,              // ERROR / WARNING / INFO
       FindingCategory.SECURITY,            // pick the most specific category
       FindingRuntimeDetection.NOT_NORMALLY_DETECTED
   );
   ```

   Rule IDs are stable identifiers. Clients may suppress rules by ID, so renaming a rule ID after it has shipped is a breaking change.

2. **Implement detection** in the appropriate analyzer class. Use JavaParser AST visitors for source-level rules, or property map inspection for configuration rules. Create a new analyzer class if the finding does not fit any existing one.

3. **Wire it up** — if you added a new analyzer class, register it in `SpringBootProjectAnalyzer`.

4. **Write a test** in the corresponding `*Test` class (or create a new one following the existing pattern). Cover at least one positive case (rule fires) and one negative case (rule does not fire on clean code).

## Code style

- Follow the style of the surrounding code. No mass reformats.
- No wildcard imports.
- Prefer `final` fields and constructor injection.
- Frontend TypeScript: strict mode is on; avoid `any` unless there is no practical alternative.
- Do not introduce new external runtime dependencies without discussion.

## Pull request guidelines

- One logical change per PR.
- Reference a backlog task or issue in the PR description if one exists.
- Every new finding rule must be accompanied by a test.
- Every bug fix should include a regression test where practical.
- Keep commits focused; squash fixup commits before requesting review.

## Ground rules

- Do not change existing finding rule IDs, severities, or categories without a documented reason — downstream consumers may depend on them.
- Do not alter the behaviour of existing analyzer rules without updating the corresponding tests and calling out the change explicitly in the PR description.
- Preserve the existing REST API contract. If a breaking change is necessary, mark it clearly and version accordingly.
- The `STATIC_ONLY` mode must never require Gradle execution or network access.
