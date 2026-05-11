# Open Source Checklist

Tasks and GitHub repository settings that require manual action and cannot be automated in code.

---

## 1. GitHub Repository Settings

- [ ] Add a short repository **description** (e.g. "Static analyzer for Spring Boot projects — detects security issues, misconfigurations, and bad practices")
- [ ] Add **topics** to improve discoverability: `spring-boot`, `static-analysis`, `java`, `security`, `code-quality`, `gradle`
- [ ] Upload a **social preview image** (Settings → General → Social preview) — 1280x640 px recommended

---

## 2. Branch Protection (Settings → Branches)

- [ ] Add a branch protection rule for `main`
  - [ ] Require status checks to pass before merging — select the CI jobs from `.github/workflows/ci.yml`
  - [ ] Require branches to be up to date before merging
  - [ ] Do not allow force pushes to `main`
  - [ ] Do not allow deletions of `main`

---

## 3. Security Features (Settings → Security)

- [x] Dependabot version updates configured (`.github/dependabot.yml` present)
- [ ] Enable **Dependabot alerts** (Settings → Security → Dependabot alerts → Enable)
- [ ] Enable **Dependabot security updates** (auto-opens PRs for vulnerable dependencies)
- [ ] Enable **Secret scanning** (Settings → Security → Secret scanning → Enable)
- [ ] Enable **Push protection** for secret scanning (blocks pushes containing secrets)
- [ ] Enable **Security advisories** (Settings → Security → Security advisories — allows private vulnerability reporting)

---

## 4. Community Health Files

- [x] `LICENSE` — Apache 2.0 added
- [ ] `CONTRIBUTING.md` — explain how to file issues, open PRs, run tests locally, and the code style expectations
- [ ] `CODE_OF_CONDUCT.md` — Contributor Covenant is a common choice; GitHub can generate one via Insights → Community Standards
- [ ] `SECURITY.md` — document the supported versions and how to report a vulnerability privately (reference GitHub Security Advisories)
- [ ] Issue templates — add `.github/ISSUE_TEMPLATE/` with at minimum:
  - [ ] `bug_report.yml` (or `.md`) — steps to reproduce, expected vs. actual behaviour, version info
  - [ ] `feature_request.yml` (or `.md`) — problem statement, proposed solution
- [ ] Pull request template — add `.github/pull_request_template.md` with a short checklist (description, tests added/updated, docs updated)

---

## 5. Discussions and Issues Setup

- [ ] Enable **GitHub Discussions** (Settings → General → Features → Discussions) if you want a Q&A / ideas forum separate from bug tracker issues
- [ ] Review the default **issue labels** and add any project-specific ones (e.g. `analyzer`, `false-positive`, `rule-request`)
- [ ] Pin any orientation issues or discussions (e.g. a "Welcome / Getting started" discussion)

---

## 6. First Release Checklist

- [x] CI workflow passing on `main` (`.github/workflows/ci.yml`)
- [ ] `README.md` reviewed — ensure it covers installation, quickstart, configuration reference, and a screenshot or demo output
- [ ] `SECURITY.md` present and references the private vulnerability reporting channel
- [ ] `CHANGELOG.md` (or `CHANGELOG`) created and documents changes for `v1.0.0`
- [ ] Git tag created for the first release: `git tag v1.0.0 && git push origin v1.0.0`
- [ ] GitHub Release published (from the tag) with release notes and any binary/JAR artifacts attached
- [ ] Repository visibility confirmed as **Public** before publishing the release
