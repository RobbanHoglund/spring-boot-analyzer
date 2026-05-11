# Security Policy

## Supported Versions

Spring Boot Analyzer is currently in active development. Security fixes are applied to the latest version on the `main` branch. No long-term support branches exist at this time.

| Version | Supported |
|---------|-----------|
| Latest (`main`) | Yes |
| Older releases | No |

## Reporting a Vulnerability

Please report vulnerabilities using [GitHub Security Advisories](https://github.com/robbanhoglund/spring-boot-analyzer/security/advisories/new). Do not open a public issue for security-related matters.

There is no dedicated security email address. GitHub Security Advisories are the preferred and only supported channel for responsible disclosure. You can expect an initial response within a few business days.

## What the Analyzer Does

Spring Boot Analyzer accepts a Git repository URL, clones it into a temporary workspace, performs analysis, and returns findings. The repository is never executed as an application. The workspace is deleted after the analysis completes.

Two analysis modes are available, with different security properties described below.

## STATIC_ONLY Mode (Default)

In `STATIC_ONLY` mode the analyzer reads and parses source files, configuration files, and build descriptors. No build tool is invoked, no code from the target repository is executed, and no subprocess is spawned on behalf of the repository.

This is the default mode. It is suitable for analyzing repositories from untrusted sources in a controlled environment, subject to the limitations listed at the end of this document.

## EXTENDED Mode

`EXTENDED` mode is opt-in. In this mode the analyzer uses the Gradle Tooling API to resolve build metadata. This causes Gradle to evaluate build scripts and configuration logic that originates from the target repository, including `build.gradle`, `settings.gradle`, and any included build logic.

This means that repository-controlled code runs on the host system during analysis. The same risks apply as running `./gradlew` against an untrusted repository: a malicious build script could perform arbitrary actions permitted by the OS user running the service.

Use `EXTENDED` mode only for repositories you trust, or run the service in an isolated environment (container, VM, restricted OS user) when analyzing third-party code.

## Private Repository Credentials and Token Handling

The API accepts an optional username and token to clone private repositories. These values are:

- Used only for the duration of the clone operation (passed to the Git credential helper in-process).
- Not written to disk, not logged, and not stored server-side in any form.
- Discarded once the clone completes.

Treat tokens with the minimum permissions required for read access to the target repository. Because the service is self-hosted, credential exposure risk is limited to the host environment and any transport-layer configuration you control.

## Recommended Usage for Untrusted Repositories

- Use `STATIC_ONLY` mode (the default). Do not enable `EXTENDED` mode for repositories you do not control.
- Run the service as a dedicated OS user with no access to sensitive files or credentials on the host.
- Consider running the service inside a container or VM so that the analysis workspace is isolated from the rest of the host.
- Restrict network access from the service process if the repositories being analyzed should not be able to trigger outbound connections (relevant primarily to `EXTENDED` mode).
- Do not expose port 8085 to untrusted networks. The service is designed for local or self-hosted use.

## Known Limitations

- **STATIC_ONLY does not sandbox the JVM parser.** Pathologically constructed source files (for example, deeply nested structures intended to exhaust memory or CPU) could affect availability. No parse output is executed, but resource consumption is not bounded by a strict limit beyond JVM heap settings.
- **Symlink and path traversal handling.** The analyzer processes files within the cloned workspace. Repositories containing symlinks that point outside the workspace root are not explicitly blocked in all cases. Run with filesystem-level controls if this is a concern.
- **No authentication on the API itself.** The service has no built-in authentication. If you expose it beyond localhost, place it behind a reverse proxy with appropriate access controls.
- **Temporary workspace cleanup.** Cleanup occurs after each analysis. In the event of a crash or hard failure, leftover workspaces may remain on disk and should be cleared manually.
- **`EXTENDED` mode Gradle version compatibility.** The Tooling API executes the Gradle wrapper version specified by the repository. Older or unsupported Gradle versions may behave unexpectedly.
