package com.robbanhoglund.springbootanalyzer.cli;

import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingSeverity;
import com.robbanhoglund.springbootanalyzer.api.dto.AnalysisMode;
import com.robbanhoglund.springbootanalyzer.api.dto.AnalyzeRepositoryResponse;
import com.robbanhoglund.springbootanalyzer.application.RepositoryAnalysisService;
import com.robbanhoglund.springbootanalyzer.cli.CliOutputFormatter.Format;
import com.robbanhoglund.springbootanalyzer.git.GitRepositoryCredentials;
import com.robbanhoglund.springbootanalyzer.git.GitRepositoryReference;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * CLI entry point for Spring Boot Analyzer.
 *
 * <p>Activated when the application starts in CLI mode (the {@code cli} Spring profile, which
 * is automatically set when {@code --repo} appears in the command-line arguments — see
 * {@link com.robbanhoglund.springbootanalyzer.SpringBootAnalyzerApplication}).
 *
 * <p>Exit codes:
 *
 * <ul>
 *   <li>0 — analysis completed; no findings at or above the {@code --fail-on} threshold
 *   <li>1 — analysis completed; at least one finding at or above the threshold was found
 *   <li>2 — analysis failed (clone error, authentication failure, etc.)
 *   <li>4 — invalid arguments (printed to stderr with usage help)
 * </ul>
 */
@Component
@Profile("cli")
@Command(
        name = "spring-boot-analyzer",
        mixinStandardHelpOptions = true,
        versionProvider = CliRunner.VersionProvider.class,
        description =
                "Analyze a Spring Boot repository and report findings, component inventory,"
                        + " HTTP surface, and configuration risks without running the application.",
        exitCodeOnInvalidInput = 4,
        exitCodeOnExecutionException = 2)
public class CliRunner implements ApplicationRunner, Callable<Integer> {

    private static final Logger LOGGER = LoggerFactory.getLogger(CliRunner.class);

    // ── Spring-injected dependencies ──────────────────────────────────────────

    private final RepositoryAnalysisService analysisService;

    public CliRunner(RepositoryAnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    // ── Picocli options ───────────────────────────────────────────────────────

    @Option(
            names = "--repo",
            required = true,
            description = "Repository URL to analyze (HTTPS or SSH).")
    private String repositoryUrl;

    @Option(
            names = "--branch",
            description = "Branch to analyze (defaults to the repository's default branch).")
    private String branch;

    @Option(names = "--username", description = "Username for HTTPS authentication.")
    private String username;

    @Option(
            names = "--token",
            description =
                    "Personal access token for HTTPS authentication."
                            + " Also readable from the ANALYZER_TOKEN environment variable.",
            defaultValue = "${ANALYZER_TOKEN:-}")
    private String token;

    @Option(
            names = "--mode",
            description = "Analysis mode: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).",
            defaultValue = "STATIC_ONLY")
    private AnalysisMode analysisMode;

    @Option(
            names = "--format",
            description = "Output format: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).",
            defaultValue = "text")
    private Format format;

    @Option(
            names = {"--output", "-o"},
            description = "Write output to FILE instead of stdout.",
            paramLabel = "FILE")
    private Path outputFile;

    @Option(
            names = "--fail-on",
            description =
                    "Exit with code 1 when any finding at this severity or above is present:"
                            + " ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).",
            defaultValue = "error")
    private FailOn failOn;

    @Option(
            names = {"--quiet", "-q"},
            description = "Suppress progress messages written to stderr.")
    private boolean quiet;

    // ── ApplicationRunner — called by Spring after context startup ────────────

    @Override
    public void run(ApplicationArguments args) {
        CommandLine cmd = new CommandLine(this);
        cmd.setOut(new PrintWriter(System.out, true));
        cmd.setErr(new PrintWriter(System.err, true));
        int code = cmd.execute(args.getSourceArgs());
        System.exit(code);
    }

    // ── Callable — invoked by Picocli after parsing ───────────────────────────

    @Override
    public Integer call() {
        progress("Analyzing " + repositoryUrl + (branch != null ? " @ " + branch : "") + " …");

        GitRepositoryCredentials credentials = buildCredentials();
        GitRepositoryReference reference =
                new GitRepositoryReference(repositoryUrl, branch, credentials, analysisMode);

        AnalyzeRepositoryResponse response;
        try {
            var result = analysisService.analyze(reference);
            response =
                    new AnalyzeRepositoryResponse(
                            result.repositoryUrl(),
                            result.branch(),
                            result.workspaceId(),
                            result.analysisId(),
                            result.commitSha(),
                            result.buildInfo().buildTool(),
                            result.buildInfo().javaVersionHint(),
                            result.buildInfo().springBootDetected(),
                            result.mainApplicationClasses(),
                            result.detectedComponents(),
                            result.buildInfo().dependencies(),
                            result.findings(),
                            result.configurationAnalysis(),
                            result.runtimeStackAnalysis(),
                            result.httpSurfaceAnalysis(),
                            result.gradleModelAnalysis(),
                            result.schedulingAnalysis(),
                            result.messagingAnalysis());
        } catch (Exception e) {
            System.err.println("Analysis failed: " + e.getMessage());
            LOGGER.error("CLI analysis failed for {}", repositoryUrl, e);
            return 2;
        }

        String output = CliOutputFormatter.format(response, format);

        try {
            writeOutput(output);
        } catch (IOException e) {
            System.err.println("Failed to write output: " + e.getMessage());
            return 2;
        }

        int code = computeExitCode(response);
        if (code == 1) {
            int count = response.findings() == null ? 0 : response.findings().size();
            progress(
                    count
                            + " finding"
                            + (count != 1 ? "s" : "")
                            + " at or above --fail-on="
                            + failOn.name()
                            + " threshold.");
        }
        return code;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private GitRepositoryCredentials buildCredentials() {
        if (token != null && !token.isBlank()) {
            return new GitRepositoryCredentials(username, token);
        }
        return null;
    }

    private void writeOutput(String output) throws IOException {
        if (outputFile != null) {
            Path parent = outputFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(outputFile, output);
            progress("Output written to " + outputFile.toAbsolutePath());
        } else {
            System.out.print(output);
        }
    }

    private int computeExitCode(AnalyzeRepositoryResponse response) {
        if (failOn == FailOn.never) return 0;
        List<com.robbanhoglund.springbootanalyzer.analyzer.model.Finding> findings =
                response.findings() == null ? List.of() : response.findings();
        boolean hasThresholdFinding =
                findings.stream()
                        .anyMatch(
                                f ->
                                        f.severity() != null
                                                && rank(f.severity()) >= rank(failOn.severity));
        return hasThresholdFinding ? 1 : 0;
    }

    private static int rank(FindingSeverity severity) {
        return switch (severity) {
            case ERROR -> 3;
            case WARNING -> 2;
            case INFO -> 1;
        };
    }

    private void progress(String message) {
        if (!quiet) {
            System.err.println("[analyzer] " + message);
        }
    }

    // ── Nested types ──────────────────────────────────────────────────────────

    /** Severity threshold for the {@code --fail-on} option. */
    enum FailOn {
        never(null),
        error(FindingSeverity.ERROR),
        warning(FindingSeverity.WARNING),
        info(FindingSeverity.INFO);

        final FindingSeverity severity;

        FailOn(FindingSeverity severity) {
            this.severity = severity;
        }
    }

    /** Provides the version string shown by {@code --version}. */
    static class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            String v =
                    CliRunner.class.getPackage() != null
                            ? CliRunner.class.getPackage().getImplementationVersion()
                            : null;
            return new String[] {"Spring Boot Analyzer " + (v != null ? v : "(dev)")};
        }
    }
}
