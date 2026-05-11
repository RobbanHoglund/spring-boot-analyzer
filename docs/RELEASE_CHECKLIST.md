# Release Checklist — v0.1.0

## Must-have before release

### Security & compliance
- [ ] Security wording in README accurately reflects the tool's threat model (static analysis only, no runtime access)
- [ ] `SECURITY.md` is present, reviewed, and contains a responsible disclosure contact
- [ ] Credential redaction is implemented and tested (analyzer output must not echo secrets from scanned projects)
- [ ] `LICENSE` is present (Apache 2.0)

### Repository hygiene
- [ ] Gradle `group` is not `com.example` — update to a real reverse-domain identifier
- [ ] No leftover debug output, TODOs marked for v0.1.0, or placeholder content in source
- [ ] `.github/` workflows, issue templates, and PR templates are clean and functional
- [ ] CI is passing on `main` (all tests green, no lint/build failures)
- [ ] No known blocking bugs

### Documentation
- [ ] `README.md` is reviewed and accurate: security model, quick-start instructions, API/endpoint docs
- [ ] `CHANGELOG.md` exists and contains release notes for v0.1.0 under an `[Unreleased]` section (ready to promote)
- [ ] Screenshot in `docs/` reflects the current UI (no stale UI shown)

### Functionality
- [ ] Default analysis mode (`STATIC_ONLY`) is tested end-to-end and behaves correctly
- [ ] All P0 backlog tasks are complete (security wording, SECURITY.md, credential redaction, repo hygiene)

---

## Release steps

- [ ] Decide the version number — **v0.1.0** for the first public release
- [ ] Update `CHANGELOG.md`: promote the `[Unreleased]` section to `[0.1.0] — YYYY-MM-DD` with today's date
- [ ] Commit the changelog update: `git commit -m "chore: release v0.1.0"`
- [ ] Create the annotated git tag: `git tag -a v0.1.0 -m "Release v0.1.0"`
- [ ] Push the tag to remote: `git push origin v0.1.0` (triggers release workflow if configured)
- [ ] Create a GitHub release from the tag, paste in the v0.1.0 changelog section as release notes
- [ ] Announce the release (blog post, social, mailing list — if applicable)

---

## Nice-to-have (can follow in patch/minor releases)

These items are valuable but are not blockers for v0.1.0.

- `CONTRIBUTING.md` — contribution guidelines, local dev setup, coding standards
- Docker image — published to a container registry for zero-install usage
- SARIF export — industry-standard format for importing findings into GitHub Advanced Security and other tools
- Rule suppression — allow projects to suppress specific findings via annotation or config file
- CLI mode — headless/scriptable invocation without a running Spring Boot process
