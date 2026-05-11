package com.robbanhoglund.springbootanalyzer.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildTool;
import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingCategory;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingConfidence;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingRuntimeDetection;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingSeverity;
import com.robbanhoglund.springbootanalyzer.analyzer.model.SourceLocation;
import com.robbanhoglund.springbootanalyzer.api.dto.AnalyzeRepositoryResponse;
import com.robbanhoglund.springbootanalyzer.cli.CliOutputFormatter.Format;
import java.util.List;
import org.junit.jupiter.api.Test;

class CliOutputFormatterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private static Finding warningFinding() {
        return new Finding(
                FindingSeverity.WARNING,
                "Exception is caught but the catch block is empty.",
                "src/main/java/com/example/Demo.java",
                "JAVA_EMPTY_CATCH_BLOCK",
                "Empty catch block",
                FindingCategory.EXCEPTION_HANDLING,
                FindingRuntimeDetection.NOT_NORMALLY_DETECTED,
                FindingConfidence.HIGH,
                "Silently swallowed exceptions hide failures.",
                "Hidden bugs and hard-to-diagnose failures.",
                "Log the exception or rethrow it.",
                "Catch block at line 3 is empty.",
                null,
                "src/main/java/com/example/Demo.java",
                3,
                "Demo#run",
                new SourceLocation(
                        "src/main/java/com/example/Demo.java",
                        3,
                        5,
                        null,
                        null,
                        null,
                        "java",
                        null),
                List.of(),
                List.of(),
                List.of());
    }

    private static Finding errorFinding() {
        return new Finding(
                FindingSeverity.ERROR,
                "@Modifying query without @Transactional.",
                "src/main/java/com/example/UserRepo.java",
                "SPRING_MODIFYING_NO_TRANSACTION",
                "@Modifying without @Transactional",
                FindingCategory.PERSISTENCE,
                FindingRuntimeDetection.NOT_NORMALLY_DETECTED,
                FindingConfidence.HIGH,
                null,
                "TransactionRequiredException at runtime.",
                "Add @Transactional to the repository method.",
                null,
                null,
                "src/main/java/com/example/UserRepo.java",
                42,
                "UserRepo#deleteById",
                null,
                List.of(),
                List.of(),
                List.of());
    }

    private static AnalyzeRepositoryResponse baseResponse(List<Finding> findings) {
        return new AnalyzeRepositoryResponse(
                "https://github.com/example/demo.git",
                "main",
                "ws-001",
                "ws-001",
                "abc1234567890",
                BuildTool.GRADLE,
                "25",
                true,
                List.of(),
                List.of(),
                List.of(),
                findings,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    // ── Text format ───────────────────────────────────────────────────────────

    @Test
    void textFormatContainsRepositoryAndBranchInfo() {
        String output =
                CliOutputFormatter.format(baseResponse(List.of(warningFinding())), Format.text);

        assertThat(output).contains("https://github.com/example/demo.git");
        assertThat(output).contains("main");
        assertThat(output).contains("GRADLE");
    }

    @Test
    void textFormatShowsCommitShaWhenPresent() {
        String output =
                CliOutputFormatter.format(baseResponse(List.of(warningFinding())), Format.text);
        assertThat(output).contains("abc1234567890");
    }

    @Test
    void textFormatListsEachFindingWithSeverityRuleAndLocation() {
        String output =
                CliOutputFormatter.format(
                        baseResponse(List.of(warningFinding(), errorFinding())), Format.text);

        assertThat(output).contains("[WARNING] JAVA_EMPTY_CATCH_BLOCK");
        assertThat(output).contains("[ERROR] SPRING_MODIFYING_NO_TRANSACTION");
        assertThat(output).contains("src/main/java/com/example/Demo.java:3");
        assertThat(output).contains("src/main/java/com/example/UserRepo.java:42");
    }

    @Test
    void textFormatShowsCountBreakdownInHeader() {
        String output =
                CliOutputFormatter.format(
                        baseResponse(List.of(warningFinding(), errorFinding())), Format.text);

        assertThat(output).contains("2 total");
        assertThat(output).containsPattern("1 error");
        assertThat(output).containsPattern("1 warning");
    }

    @Test
    void textFormatIncludesImpactAndFixWhenPresent() {
        String output =
                CliOutputFormatter.format(baseResponse(List.of(errorFinding())), Format.text);

        assertThat(output).contains("TransactionRequiredException at runtime.");
        assertThat(output).contains("Add @Transactional to the repository method.");
    }

    @Test
    void textFormatShowsNoFindingsMessageForEmptyResult() {
        String output = CliOutputFormatter.format(baseResponse(List.of()), Format.text);
        assertThat(output).contains("none");
        assertThat(output).containsIgnoringCase("no issues detected");
    }

    // ── JSON format ───────────────────────────────────────────────────────────

    @Test
    void jsonFormatProducesValidJsonWithFindings() throws Exception {
        String json =
                CliOutputFormatter.format(baseResponse(List.of(warningFinding())), Format.json);
        JsonNode root = MAPPER.readTree(json);

        assertThat(root.get("repositoryUrl").asText())
                .isEqualTo("https://github.com/example/demo.git");
        assertThat(root.get("findings").isArray()).isTrue();
        assertThat(root.get("findings").size()).isEqualTo(1);
        assertThat(root.get("findings").get(0).get("ruleId").asText())
                .isEqualTo("JAVA_EMPTY_CATCH_BLOCK");
    }

    // ── SARIF format ──────────────────────────────────────────────────────────

    @Test
    void sarifFormatProducesValidSarif210() throws Exception {
        String sarif =
                CliOutputFormatter.format(baseResponse(List.of(warningFinding())), Format.sarif);
        JsonNode doc = MAPPER.readTree(sarif);

        assertThat(doc.get("version").asText()).isEqualTo("2.1.0");
        assertThat(doc.get("runs").get(0).get("results").size()).isEqualTo(1);
    }
}
