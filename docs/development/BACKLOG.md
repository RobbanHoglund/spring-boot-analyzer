# Spring Boot Analyzer - Open Source Backlog

> Purpose: make the project feel trustworthy, clean and ready for public open source usage without breaking the existing analyzer behavior.

This backlog is written for AI-assisted development with Codex. Work through it one task at a time. Prefer small, reviewable commits over broad rewrites.

---

## Global rules for Codex

These rules apply to every task in this backlog.

### Non-negotiables

- Do not rewrite the core analyzer unless the task explicitly asks for it.
- Do not remove existing analyzer rules.
- Do not rename public API fields unless the task explicitly asks for it.
- Do not change the HTTP API contract unless the task explicitly asks for it.
- Do not change UI fundamentals or visual identity unless the task explicitly asks for it.
- Preserve current behavior first; improve clarity, safety and maintainability second.
- Prefer additive changes: docs, tests, warnings, config, small refactors.
- If behavior must change, explain the reason in the commit message and add tests.
- Run backend tests after backend changes.
- Run frontend build/tests after frontend changes.
- Keep changes focused. One task should not silently complete unrelated tasks.

### Definition of Done for every task

A task is done only when all of this is true:

- The code compiles.
- Relevant tests pass.
- No unrelated formatting churn outside the task scope, except the dedicated formatting task.
- README or docs are updated when user-facing behavior changes.
- Security-sensitive wording is precise and does not overpromise.
- The change can be reviewed as a small pull request.

---

## Priority order

1. P0 - Trust and safety model
2. P0 - Repo hygiene and public polish
3. P1 - Documentation and onboarding
4. P1 - CI and quality gates
5. P2 - Product features for real adoption
6. P2 - Release packaging

---

# EPIC A - Trust and safety model

## A-001 - Fix the analysis-mode security wording

Priority: P0  
Area: Documentation, security, product trust

### Problem

The public project description says the analyzer does not execute code from the analyzed repository. At the same time, extended Gradle model analysis may evaluate repository-controlled Gradle build configuration logic. This can create a trust gap for open source users.

### Goal

Make the execution model completely clear and technically accurate.

### Required changes

Update README and API documentation so they clearly distinguish:

- STATIC_ONLY mode
- EXTENDED mode

STATIC_ONLY should be described as the safe default mode that does not run Gradle tasks, Maven goals, tests or the analyzed Spring Boot application.

EXTENDED should be described as an opt-in mode that may evaluate repository-controlled Gradle build configuration through the Gradle Tooling API and should only be used for repositories the user trusts or inside a sandbox.

### Suggested wording

Default mode is STATIC_ONLY. It clones the repository into a temporary workspace and performs static source, build-file and configuration analysis. It does not run Gradle tasks, Maven goals, tests or the analyzed Spring Boot application.

EXTENDED mode is opt-in. It includes all STATIC_ONLY analysis and may use the Gradle Tooling API to resolve richer Gradle model information. This can evaluate repository-controlled Gradle build configuration logic. Use EXTENDED mode only for repositories you trust or inside an isolated sandbox.

### Acceptance criteria

- README no longer contains an absolute statement that is contradicted by EXTENDED mode.
- README clearly says STATIC_ONLY is the default.
- README clearly says EXTENDED is opt-in and may evaluate Gradle build configuration logic.
- API section explains the security difference between STATIC_ONLY and EXTENDED.
- No analyzer behavior is changed in this task.

### Verification

- Read README top-to-bottom and confirm the security model is consistent.
- Run backend tests if documentation generation or code comments were touched.

---

## A-002 - Add visible EXTENDED mode warning in the UI

Priority: P0  
Area: Frontend, UX, security

### Problem

Users should not accidentally run EXTENDED mode without understanding the trust implications.

### Goal

Make EXTENDED mode visually and textually clear before analysis starts.

### Required changes

When the user selects EXTENDED analysis mode, show a calm but clear warning in the UI.

The warning should say that EXTENDED mode may evaluate repository-controlled Gradle build configuration logic and should only be used for trusted repositories or inside a sandbox.

Do not use scary or dramatic copy. Keep it professional.

### Acceptance criteria

- STATIC_ONLY remains the default mode.
- EXTENDED mode has a visible warning near the mode selector or analyze button.
- The warning is only shown when EXTENDED is selected.
- The UI remains clean and premium; no intrusive modal unless one already exists naturally in the flow.
- No API behavior changes.

### Verification

- Start frontend and backend locally.
- Confirm STATIC_ONLY is selected by default.
- Select EXTENDED and verify the warning appears.
- Switch back to STATIC_ONLY and verify the warning disappears.
- Run frontend build.

---

## A-003 - Enforce STATIC_ONLY as the safe default

Priority: P0  
Area: Backend, API, safety

### Problem

The default analysis mode should never accidentally become EXTENDED because of a missing, empty or unknown request value.

### Goal

Make the backend default explicit and covered by tests.

### Required changes

Review the API request handling for analysisMode.

Ensure:

- Missing analysisMode becomes STATIC_ONLY.
- Null analysisMode becomes STATIC_ONLY.
- Empty analysisMode becomes STATIC_ONLY or returns a validation error. Choose the behavior that best fits the existing API style.
- Unknown analysisMode returns a clear validation error and does not run analysis.
- EXTENDED must only be used when explicitly requested.

Add tests for the selected behavior.

### Acceptance criteria

- STATIC_ONLY is the backend default.
- EXTENDED cannot be reached accidentally from missing/null data.
- Invalid mode values are handled predictably.
- Tests cover missing, null and invalid analysisMode cases.
- Existing valid API requests still work.

### Verification

- Run backend tests.
- Manually test analyze request without analysisMode.
- Manually test analyze request with EXTENDED.
- Manually test analyze request with an invalid mode.

---

## A-004 - Add SECURITY.md with a lightweight threat model

Priority: P0  
Area: Documentation, security, open source readiness

### Problem

The project analyzes external repositories and can accept credentials for private repositories. Open source users need clear security expectations.

### Goal

Add a SECURITY.md file that explains supported security practices and how to report vulnerabilities.

### Required content

Create SECURITY.md with these sections:

- Supported versions
- Reporting a vulnerability
- What the analyzer does by default
- STATIC_ONLY mode security model
- EXTENDED mode security model
- Private repository credentials and token handling
- Recommended usage for untrusted repositories
- Known limitations

### Acceptance criteria

- SECURITY.md exists in repository root.
- It explains that STATIC_ONLY is the safe default.
- It explains that EXTENDED should only be used for trusted repositories or sandboxed execution.
- It tells users not to submit production tokens unless they understand the risk.
- It gives a clear vulnerability reporting route. If no private security email exists, say to open a GitHub security advisory or issue without posting secrets.

### Verification

- Read SECURITY.md as a new user and confirm it is clear.
- No code behavior changes.

---

## A-005 - Audit credential redaction in logs and UI

Priority: P0  
Area: Backend, frontend, security

### Problem

The API supports private repositories with credentials. Tokens must never appear in logs, errors, frontend state dumps or report output.

### Goal

Ensure credentials are redacted everywhere outside the immediate clone/authentication flow.

### Required changes

Search for all usages of credentials, username, token, repositoryUrl and exception handling around clone failures.

Ensure:

- Tokens are never logged.
- Tokens are never included in error responses.
- Tokens are never included in report output.
- Tokens are never displayed in the UI.
- Repository URLs with embedded credentials are redacted before logging or display.
- Tests cover redaction for common token patterns.

### Acceptance criteria

- There is a central redaction helper or equivalent consistent approach.
- Clone/authentication errors are useful but do not leak secrets.
- Unit tests cover token redaction.
- Existing clone behavior still works.

### Verification

- Run backend tests.
- Trigger a failed clone with a fake token and verify logs/responses do not contain the token.

---

# EPIC B - Repo hygiene and public polish

## B-001 - Format source and config files into normal readable style

Priority: P0  
Area: Maintainability, open source polish

### Problem

Several files appear compressed or difficult to read in raw form. This hurts trust and makes contribution harder.

### Goal

Make the repository readable and contributor-friendly.

### Required changes

Format these file types consistently:

- Java
- Gradle
- properties
- TypeScript
- JavaScript
- CSS
- JSON
- Markdown

Prefer adding or using standard formatting tools where practical.

Possible tools:

- Spotless or Gradle format plugin for Java and Gradle
- Prettier for frontend files
- Existing IDE formatting if no formatter is currently configured

### Acceptance criteria

- build.gradle is readable and multi-line.
- application.properties is readable and multi-line.
- frontend config files are readable and multi-line.
- Large Java classes are formatted normally.
- No behavior changes.
- Formatting is done in one dedicated commit or pull request.

### Verification

- Run backend tests.
- Run frontend build.
- Review diff and confirm it is formatting-only.

---

## B-002 - Remove committed dev log files and ignore them

Priority: P0  
Area: Repo hygiene

### Problem

Development log files should not be committed to an open source repository.

### Goal

Remove generated/dev-only logs from version control and prevent re-adding them.

### Required changes

Remove committed dev log files such as:

- frontend/frontend-dev.out.log

Update .gitignore to ignore common local/generated logs:

- *.log
- *.out.log
- frontend/*.log
- frontend/*.out.log

Be careful not to ignore intentionally committed build artifacts if the project currently relies on them.

### Acceptance criteria

- Dev log files are removed from git.
- .gitignore prevents common log files from being re-added.
- No application behavior changes.

### Verification

- Run git status after local dev commands and confirm log files do not appear.
- Run tests if any build files were touched.

---

## B-003 - Replace Gradle group com.example

Priority: P0  
Area: Build metadata, public identity

### Problem

The project currently looks less production-ready if build metadata uses com.example.

### Goal

Use a real project identity in Gradle metadata.

### Required changes

Change Gradle group from com.example to a real project group.

Recommended options:

- com.robbanhoglund
- io.github.robbanhoglund

Do not rename Java packages in this task unless it is absolutely required. Package renaming is larger and should be separate.

### Acceptance criteria

- Gradle group no longer uses com.example.
- Build still passes.
- Generated artifact coordinates look intentional.
- No Java package rename unless explicitly justified.

### Verification

- Run ./gradlew clean test.
- Confirm Gradle project metadata uses the new group.

---

## B-004 - Move intentional example fixtures out of main production source

Priority: P0  
Area: Project structure, maintainability

### Problem

Example classes under src/main/java/com/example can look like accidental production code in an open source analyzer project.

### Goal

Make intentional fixtures clearly separate from production code.

### Required changes

Review the classes under src/main/java/com/example.

If they are analyzer fixtures or sample code, move them to one of these locations:

- src/test/resources/fixtures/sample-spring-app
- src/test/java fixtures
- samples/sample-spring-app

Choose the location that best matches how they are used today.

Update any tests or documentation that refer to the old location.

### Acceptance criteria

- No intentional sample app classes remain under src/main/java/com/example.
- The sample/fixture purpose is obvious from the folder path.
- Tests still pass.
- README or fixture README explains why the sample app exists.
- No analyzer rule behavior is changed.

### Verification

- Run backend tests.
- Run a local analysis of the moved fixture if tests support it.

---

## B-005 - Add repository-level open source checklist

Priority: P1  
Area: Manual GitHub setup, project polish

### Problem

Some important open source settings live in GitHub repository settings and cannot be changed from code.

### Goal

Create a checklist for manual repo settings so the project looks complete on GitHub.

### Required changes

Add docs/OPEN_SOURCE_CHECKLIST.md with manual items:

- Add repository description.
- Add topics such as spring-boot, static-analysis, java, security, code-quality, gradle.
- Enable issues.
- Enable discussions if desired.
- Enable Dependabot alerts.
- Enable secret scanning if available.
- Enable branch protection for main.
- Require CI before merge.
- Add a social preview image if desired.

### Acceptance criteria

- docs/OPEN_SOURCE_CHECKLIST.md exists.
- The checklist separates code tasks from manual GitHub settings.
- It is clear enough to follow later.

### Verification

- Read the checklist and confirm each item is actionable.

---

# EPIC C - Documentation and onboarding

## C-001 - Improve README first screen

Priority: P1  
Area: README, adoption

### Problem

New users decide quickly whether an open source project is serious. The first screen should explain what the tool does, who it is for and why it is safe by default.

### Goal

Make the README opening more polished and open source friendly.

### Required changes

At the top of README, add or improve:

- One-line product description.
- Short value proposition.
- Screenshot or existing image.
- Badges for CI, license, Java version and Spring Boot version if practical.
- Safe-by-default statement for STATIC_ONLY mode.
- Link to SECURITY.md.
- Link to rule catalog.

### Acceptance criteria

- A new visitor understands the project within 30 seconds.
- Security claims are precise and not overbroad.
- README links are valid.
- Existing useful README content is preserved.

### Verification

- Review README in GitHub preview.
- Check all links.

---

## C-002 - Add architecture document

Priority: P1  
Area: Documentation, contributor onboarding

### Problem

Contributors need to understand how clone, parse, analyze and report generation fit together.

### Goal

Add a concise architecture document.

### Required content

Create docs/ARCHITECTURE.md with:

- High-level flow from API request to report.
- Repository clone/temp workspace lifecycle.
- Static Java parsing flow.
- Configuration parsing flow.
- Build file analysis flow.
- Extended Gradle model flow.
- Finding/rule model overview.
- Frontend/backend boundary.
- Known trade-offs and limitations.

### Acceptance criteria

- docs/ARCHITECTURE.md exists.
- It helps a new contributor find the right classes quickly.
- It clearly marks where untrusted repository data enters the system.
- It does not expose secrets or internal assumptions.

### Verification

- Read the document as a new contributor.
- Confirm class/package references are correct.

---

## C-003 - Generate or maintain a rule catalog

Priority: P1  
Area: Documentation, product quality

### Problem

The README contains many rules, but a dedicated rule catalog is easier to maintain and link to.

### Goal

Create docs/RULES.md as the canonical rule catalog.

### Required content

For each rule, include:

- Rule id
- Category
- Severity
- Confidence, if available
- What it detects
- Why it matters
- Example bad pattern, if easy
- Recommendation
- Known false positives, if known

If generating this file from FindingRules is practical, do that. If not, maintain it manually for now.

### Acceptance criteria

- docs/RULES.md exists.
- README links to docs/RULES.md instead of carrying too much rule detail inline.
- Rule IDs in docs match code.
- No rule behavior changes.

### Verification

- Compare docs/RULES.md against FindingRules.
- Run tests if generation code was added.

---

## C-004 - Add limitations and false-positive guidance

Priority: P1  
Area: Documentation, trust

### Problem

Static analysis tools have false positives and blind spots. Stating this clearly increases trust.

### Goal

Explain limitations honestly.

### Required changes

Add a README or docs section covering:

- Static analysis is conservative.
- Some findings require human review.
- Dynamic runtime behavior may not be visible.
- Reflection, generated code and framework magic can hide behavior.
- Multi-module and unusual build setups may have partial support.
- EXTENDED mode gives richer dependency information but has additional trust considerations.

### Acceptance criteria

- Users understand findings are advisory, not absolute truth.
- The docs explain when to verify manually.
- No behavior changes.

### Verification

- Read the docs and confirm the tone is honest and professional.

---

## C-005 - Add Windows-friendly quick start

Priority: P1  
Area: Documentation, developer experience

### Problem

Many Java developers run on Windows. Quick start should not assume only Unix shell usage.

### Goal

Make local development easy on Windows and Unix-like systems.

### Required changes

Update README with separate commands or notes for:

- Windows PowerShell
- macOS/Linux shell
- Backend startup
- Frontend dev startup
- Full build
- Test command

### Acceptance criteria

- A Windows user can follow the README without translating every command.
- Unix commands remain available.
- No behavior changes.

### Verification

- Review commands for correctness.
- Run at least the commands available on the current development machine.

---

# EPIC D - CI and quality gates

## D-001 - Make CI verify backend and frontend

Priority: P1  
Area: CI, quality

### Problem

Open source users expect CI to prove that the project builds.

### Goal

Ensure GitHub Actions verifies both backend and frontend.

### Required changes

Review existing workflow under .github/workflows.

CI should include:

- Java setup using the required Java version.
- Gradle cache if practical.
- Backend compile/test.
- Node setup using required Node version.
- Frontend install and build.

Use npm ci if package-lock.json exists. Use npm install only if there is no lockfile.

### Acceptance criteria

- CI runs on pull requests and pushes to main.
- Backend tests are executed.
- Frontend build is executed.
- Workflow is not overly slow or complex.
- README badge points to the workflow.

### Verification

- Run workflow locally if possible or verify on GitHub Actions.
- Confirm failing backend or frontend build would fail CI.

---

## D-002 - Add focused tests for highest-value rules

Priority: P1  
Area: Testing, analyzer quality

### Problem

The value of the analyzer depends on rule accuracy. High-value rules need regression tests.

### Goal

Add fixture-based tests for important rules without overbuilding the test framework.

### Suggested first test targets

- SPRING_SECRET_LITERAL
- SPRING_SECRET_WEAK_PLACEHOLDER_DEFAULT
- SPRING_CSRF_DISABLED
- SPRING_CORS_ALLOW_ALL
- SPRING_DDL_AUTO_DESTRUCTIVE_PROD
- SPRING_JPA_OPEN_IN_VIEW
- SPRING_MODIFYING_NO_TRANSACTION
- SPRING_TRANSACTION_SELF_INVOCATION
- SPRING_VALUE_NO_DEFAULT
- SPRING_HTTP_CLIENT_NO_TIMEOUT

### Acceptance criteria

- Each selected rule has at least one positive test.
- Important false-positive cases get tests where practical.
- Test fixtures are readable and small.
- Tests do not require network access.
- Tests do not require analyzing a real external repository.

### Verification

- Run backend tests.
- Confirm tests fail if the rule is disabled or broken.

---

## D-003 - Add report contract tests

Priority: P1  
Area: API stability

### Problem

Frontend and future integrations rely on report structure. Accidental response changes can break users.

### Goal

Lock down the main report DTO shape with contract-style tests.

### Required changes

Add tests that verify a representative analysis response includes stable fields such as:

- findings
- severity
- confidence
- evidence
- recommendation
- source location
- component inventory
- HTTP surface
- configuration risks

Do not make tests too brittle around ordering unless ordering is part of the API contract.

### Acceptance criteria

- Main report response shape is tested.
- Existing frontend expectations are protected.
- Tests are readable.

### Verification

- Run backend tests.
- Make a harmless internal change and confirm tests are not too brittle.

---

## D-004 - Add dependency update automation

Priority: P2  
Area: Maintenance

### Problem

Open source projects need dependency maintenance.

### Goal

Add Dependabot configuration for Gradle, npm and GitHub Actions.

### Required changes

Create .github/dependabot.yml with update groups for:

- Gradle
- npm in frontend
- GitHub Actions

Keep schedule reasonable, for example weekly.

### Acceptance criteria

- Dependabot config exists.
- It covers backend, frontend and actions.
- It avoids daily PR noise.

### Verification

- Validate YAML format.
- Confirm GitHub recognizes the config after merge.

---

# EPIC E - Product features for adoption

## E-001 - Add SARIF export

Priority: P2  
Area: Product, integrations

### Problem

Static analysis tools are more useful when results can be consumed by GitHub code scanning and other security tooling.

### Goal

Add SARIF export for findings.

### Required changes

Add a way to export analysis findings as SARIF.

Possible API design:

- Keep existing JSON report unchanged.
- Add a new endpoint for SARIF export, or add an explicit export format parameter if it does not break existing clients.

SARIF should include:

- Rule id
- Rule name or description
- Severity mapping
- Message
- File path
- Line number when available
- Help/recommendation text

### Acceptance criteria

- Existing JSON API remains compatible.
- SARIF output validates with a SARIF validator if practical.
- At least one test covers SARIF generation.
- README documents how to use it.

### Verification

- Run backend tests.
- Generate SARIF from a small fixture repository.
- Validate file structure manually or with tooling.

---

## E-002 - Add baseline mode

Priority: P2  
Area: Product, developer workflow

### Problem

Large existing projects may have many findings. Users need a way to focus on new issues.

### Goal

Support comparing current findings against a saved baseline.

### Required behavior

Allow users to upload or provide a previous report as a baseline.

The analyzer should mark findings as:

- new
- existing
- resolved

Avoid changing core finding detection. This is a post-processing/report comparison feature.

### Acceptance criteria

- Baseline comparison does not alter analyzer rules.
- Finding identity is stable enough for comparison.
- Existing API remains compatible.
- Tests cover new, existing and resolved findings.
- Documentation explains the workflow.

### Verification

- Run backend tests.
- Compare two small fixture reports manually.

---

## E-003 - Add suppression support

Priority: P2  
Area: Product, developer workflow

### Problem

Users need a controlled way to suppress accepted findings.

### Goal

Support project-level suppressions without hiding problems silently.

### Suggested design

Support a config file in the analyzed repository, for example:

- .spring-boot-analyzer.yml

Suppression entries should include:

- rule id
- optional file path
- optional reason
- optional expiry date

### Acceptance criteria

- Suppressed findings are still available in report metadata or a separate suppressed section.
- Suppressions require a reason if practical.
- Expired suppressions are reported clearly.
- Suppression does not delete or mutate the underlying finding.
- Tests cover rule-level and file-level suppression.

### Verification

- Run backend tests.
- Analyze a fixture with suppressions and confirm output is clear.

---

## E-004 - Add CLI mode

Priority: P2  
Area: Product, developer workflow

### Problem

A web UI is useful, but CI and local automation often need CLI execution.

### Goal

Add a CLI entry point without removing the existing web app.

### Suggested behavior

CLI should support:

- repository URL or local path
- branch
- analysis mode
- output file
- output format JSON first, SARIF later if E-001 is done
- exit code behavior for severity threshold

### Acceptance criteria

- Existing web app still works.
- CLI can analyze a public repository or local fixture.
- CLI can write report output to a file.
- CLI has help text.
- Tests cover argument parsing if practical.

### Verification

- Run CLI against a small fixture.
- Run backend tests.

---

## E-005 - Add Docker image support

Priority: P2  
Area: Packaging, adoption

### Problem

Users may want to run the analyzer without installing Java, Node or Gradle locally.

### Goal

Add a Dockerfile and documented run command.

### Required changes

Create a production-oriented Dockerfile.

Document:

- build command
- run command
- exposed port
- volume/temp workspace considerations
- security recommendation for analyzing untrusted repositories

### Acceptance criteria

- Docker image builds locally.
- App starts from the image.
- README includes Docker usage.
- No local dev workflow is broken.

### Verification

- docker build succeeds.
- docker run starts the app.
- UI is reachable on the documented port.

---

# EPIC F - UI/UX improvements

## F-001 - Show analysis context clearly in the report

Priority: P1  
Area: Frontend, UX, trust

### Problem

A report should make it obvious what was analyzed and how.

### Goal

Add a compact report context area.

### Required content

Show:

- Repository URL or owner/repo
- Branch
- Commit SHA if available
- Analysis mode
- Analysis timestamp
- Number of findings
- Highest severity
- Whether EXTENDED analysis was used

### Acceptance criteria

- Report context is visible without clutter.
- EXTENDED mode is clearly marked when used.
- No backend contract break unless required and tested.
- UI remains responsive on laptop screens.

### Verification

- Run UI locally.
- Analyze a sample repo.
- Confirm context is clear.
- Run frontend build.

---

## F-002 - Improve empty, loading and error states

Priority: P1  
Area: Frontend, UX

### Problem

A polished open source tool should guide users when nothing has happened, analysis is running or errors occur.

### Goal

Make states calm, clear and useful.

### Required changes

Review and improve:

- Initial empty state
- Loading/analyzing state
- Clone/authentication error state
- Invalid repository URL state
- No findings state
- Partial analysis state, if supported

### Acceptance criteria

- Error messages are useful but do not leak secrets.
- Empty state explains what to do next.
- No findings state feels positive but not misleading.
- Visual style stays consistent.
- No core logic changes.

### Verification

- Manually trigger each state where practical.
- Run frontend build.

---

## F-003 - Add export actions to report UI

Priority: P2  
Area: Frontend, product

### Problem

Users need to save or share analysis results.

### Goal

Make export actions easy to find.

### Required changes

Add export buttons where appropriate:

- Download JSON report
- Download SARIF report if E-001 is complete
- Copy summary to clipboard

### Acceptance criteria

- Existing report view remains unchanged in fundamentals.
- Exported JSON matches current report.
- Copy summary excludes secrets and credentials.
- UI is clean on laptop screens.

### Verification

- Run frontend build.
- Download report and inspect content.
- Test copy action.

---

# EPIC G - Release packaging

## G-001 - Prepare v0.1.0 release checklist

Priority: P1  
Area: Release management

### Problem

A public project needs a clear first release target.

### Goal

Create a release checklist for v0.1.0.

### Required changes

Create docs/RELEASE_CHECKLIST.md with:

- Required P0 tasks complete
- CI passing
- README reviewed
- SECURITY.md present
- LICENSE present
- CHANGELOG updated
- Version number decided
- Release notes drafted
- Known limitations listed
- Screenshot updated

### Acceptance criteria

- Release checklist exists.
- It is realistic and not overcomplicated.
- It clearly separates must-have from nice-to-have.

### Verification

- Read checklist and confirm v0.1.0 could be shipped from it.

---

## G-002 - Add CHANGELOG.md

Priority: P1  
Area: Release management

### Problem

Users and contributors need to understand what changed between releases.

### Goal

Add a simple changelog.

### Required changes

Create CHANGELOG.md using a simple Keep-a-Changelog-inspired format.

Sections:

- Unreleased
- Added
- Changed
- Fixed
- Security

### Acceptance criteria

- CHANGELOG.md exists.
- Current unreleased state is documented briefly.
- Future entries are easy to add.

### Verification

- Read changelog and confirm it is useful without being verbose.

---

## G-003 - Add release artifact workflow

Priority: P2  
Area: CI/CD, packaging

### Problem

Users may want downloadable release artifacts.

### Goal

Create a GitHub Actions workflow that builds release artifacts on tags.

### Required behavior

On version tags, build:

- backend jar
- frontend bundle if needed
- packaged application artifact

Attach artifacts to the GitHub release if practical.

### Acceptance criteria

- Normal CI remains separate and fast.
- Release workflow runs only on tags or manual dispatch.
- Artifacts are named clearly.
- README or release checklist mentions how to cut a release.

### Verification

- Test workflow with workflow_dispatch if practical.
- Confirm artifacts are produced.

---

# Suggested first Codex execution order

Use this order for the first cleanup pass:

1. A-001 - Fix the analysis-mode security wording
2. A-003 - Enforce STATIC_ONLY as the safe default
3. A-004 - Add SECURITY.md
4. A-005 - Audit credential redaction in logs and UI
5. B-002 - Remove committed dev log files and ignore them
6. B-003 - Replace Gradle group com.example
7. B-001 - Format source and config files into normal readable style
8. B-004 - Move intentional example fixtures out of main production source
9. C-001 - Improve README first screen
10. D-001 - Make CI verify backend and frontend

---

# Suggested Codex prompt

Use this when asking Codex to work from this backlog:

You are working in the spring-boot-analyzer repository. Read BACKLOG.md first. Pick only the specific backlog task I name. Implement that task carefully and do not make unrelated changes. Preserve existing analyzer behavior, API contracts and UI fundamentals unless the selected task explicitly requires a change. Add or update tests where the task requires it. Run the relevant verification commands and summarize exactly what changed, what was tested and any follow-up risks.

