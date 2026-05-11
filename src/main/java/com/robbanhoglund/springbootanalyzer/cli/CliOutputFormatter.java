package com.robbanhoglund.springbootanalyzer.cli;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingSeverity;
import com.robbanhoglund.springbootanalyzer.api.dto.AnalyzeRepositoryResponse;
import java.util.List;

/**
 * Formats an {@link AnalyzeRepositoryResponse} for CLI output in one of three modes:
 * human-readable text, raw JSON, or SARIF 2.1.0.
 */
public final class CliOutputFormatter {

    private static final ObjectMapper JSON_MAPPER =
            new ObjectMapper()
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private CliOutputFormatter() {}

    /** Output format identifiers accepted by {@code --format}. */
    public enum Format {
        text,
        json,
        sarif
    }

    /**
     * Formats the analysis result in the chosen format.
     *
     * @param response the analysis result
     * @param format the desired output format
     * @return formatted string ready to be written to stdout or a file
     */
    public static String format(AnalyzeRepositoryResponse response, Format format) {
        return switch (format) {
            case text -> formatText(response);
            case json -> formatJson(response);
            case sarif -> SarifExporter.toJson(response);
        };
    }

    // ---------------------------------------------------------------------------
    // Text format
    // ---------------------------------------------------------------------------

    private static String formatText(AnalyzeRepositoryResponse response) {
        List<Finding> findings = response.findings() == null ? List.of() : response.findings();
        long errors = findings.stream().filter(f -> f.severity() == FindingSeverity.ERROR).count();
        long warnings =
                findings.stream().filter(f -> f.severity() == FindingSeverity.WARNING).count();
        long infos = findings.stream().filter(f -> f.severity() == FindingSeverity.INFO).count();

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("╔══════════════════════════════════════════════════════════╗\n");
        sb.append("║        Spring Boot Analyzer — Analysis Report            ║\n");
        sb.append("╚══════════════════════════════════════════════════════════╝\n");
        sb.append("\n");

        appendKv(sb, "Repository", str(response.repositoryUrl(), "unknown"));
        appendKv(sb, "Branch", str(response.branch(), "default branch"));
        if (response.commitSha() != null && !response.commitSha().isBlank()) {
            appendKv(sb, "Commit", response.commitSha());
        }
        appendKv(
                sb,
                "Build tool",
                response.buildTool() != null ? response.buildTool().name() : "unknown");
        if (response.runtimeStackAnalysis() != null
                && response.runtimeStackAnalysis().springBootVersion() != null) {
            appendKv(sb, "Spring Boot", response.runtimeStackAnalysis().springBootVersion());
        }
        sb.append("\n");

        if (findings.isEmpty()) {
            sb.append("Findings  : none — no issues detected by the current checks.\n");
            sb.append("\n");
            sb.append("Static analysis has limits. Complement with code review and testing.\n");
        } else {
            sb.append(
                    String.format(
                            "Findings  : %d total  (%d error%s  |  %d warning%s  |  %d info)%n",
                            findings.size(),
                            errors,
                            errors != 1 ? "s" : "",
                            warnings,
                            warnings != 1 ? "s" : "",
                            infos));
            sb.append("           ─────────────────────────────────────────────\n");
            sb.append("\n");

            for (Finding f : findings) {
                appendFinding(sb, f);
            }
        }

        return sb.toString();
    }

    private static void appendFinding(StringBuilder sb, Finding f) {
        String severityLabel = f.severity() != null ? f.severity().name() : "INFO";
        String ruleId = f.ruleId() != null ? f.ruleId() : "(no rule)";
        String title = f.title() != null ? f.title() : (f.message() != null ? f.message() : ruleId);

        sb.append(String.format("[%s] %s%n", severityLabel, ruleId));
        sb.append(String.format("  %s%n", title));

        if (f.sourceFile() != null) {
            String loc = f.line() != null ? f.sourceFile() + ":" + f.line() : f.sourceFile();
            sb.append(String.format("  Location : %s%n", loc));
        }
        if (f.possibleImpact() != null) {
            sb.append(String.format("  Impact   : %s%n", f.possibleImpact()));
        }
        if (f.recommendation() != null) {
            sb.append(String.format("  Fix      : %s%n", f.recommendation()));
        }
        sb.append("\n");
    }

    // ---------------------------------------------------------------------------
    // JSON format
    // ---------------------------------------------------------------------------

    private static String formatJson(AnalyzeRepositoryResponse response) {
        try {
            return JSON_MAPPER.writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise JSON output", e);
        }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private static void appendKv(StringBuilder sb, String key, String value) {
        sb.append(String.format("%-10s: %s%n", key, value));
    }

    private static String str(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }
}
