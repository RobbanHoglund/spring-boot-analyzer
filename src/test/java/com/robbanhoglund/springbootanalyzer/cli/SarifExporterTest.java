package com.robbanhoglund.springbootanalyzer.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingCategory;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingConfidence;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingOccurrence;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingRuntimeDetection;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingSeverity;
import com.robbanhoglund.springbootanalyzer.analyzer.model.SourceLocation;
import com.robbanhoglund.springbootanalyzer.api.dto.AnalyzeRepositoryResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class SarifExporterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private static Finding baseFinding() {
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
                "Catch block at line 3 contains no statements.",
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

    private static AnalyzeRepositoryResponse baseResponse(List<Finding> findings) {
        return new AnalyzeRepositoryResponse(
                "https://github.com/example/demo.git",
                "main",
                "ws-001",
                "ws-001",
                "abc1234567890",
                null,
                null,
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

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void producesValidSarif210Envelope() throws Exception {
        String json = SarifExporter.toJson(baseResponse(List.of(baseFinding())));
        JsonNode doc = MAPPER.readTree(json);

        assertThat(doc.get("version").asText()).isEqualTo("2.1.0");
        assertThat(doc.get("$schema").asText()).contains("sarif-schema-2.1.0.json");
        assertThat(doc.get("runs").isArray()).isTrue();
        assertThat(doc.get("runs").size()).isEqualTo(1);
    }

    @Test
    void setsToolDriverNameAndUri() throws Exception {
        JsonNode driver =
                MAPPER.readTree(SarifExporter.toJson(baseResponse(List.of(baseFinding()))))
                        .get("runs")
                        .get(0)
                        .get("tool")
                        .get("driver");

        assertThat(driver.get("name").asText()).isEqualTo("Spring Boot Analyzer");
        assertThat(driver.get("informationUri").asText()).contains("github.com");
    }

    @Test
    void emitsVersionControlProvenanceFromRepositoryUrlAndCommitSha() throws Exception {
        JsonNode vcp =
                MAPPER.readTree(SarifExporter.toJson(baseResponse(List.of(baseFinding()))))
                        .get("runs")
                        .get(0)
                        .get("versionControlProvenance")
                        .get(0);

        assertThat(vcp.get("repositoryUri").asText())
                .isEqualTo("https://github.com/example/demo.git");
        assertThat(vcp.get("revisionId").asText()).isEqualTo("abc1234567890");
        assertThat(vcp.get("branch").asText()).isEqualTo("main");
    }

    @Test
    void mapsWarningSeverityToSarifLevelWarning() throws Exception {
        JsonNode result =
                MAPPER.readTree(SarifExporter.toJson(baseResponse(List.of(baseFinding()))))
                        .get("runs")
                        .get(0)
                        .get("results")
                        .get(0);

        assertThat(result.get("level").asText()).isEqualTo("warning");
    }

    @Test
    void mapsErrorSeverityToSarifLevelError() throws Exception {
        Finding errorFinding =
                new Finding(
                        FindingSeverity.ERROR,
                        "Destructive DDL auto",
                        null,
                        "SPRING_DDL_AUTO_DESTRUCTIVE_PROD",
                        "Destructive DDL auto in production",
                        FindingCategory.PERSISTENCE,
                        FindingRuntimeDetection.NOT_NORMALLY_DETECTED,
                        FindingConfidence.HIGH,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of());

        JsonNode result =
                MAPPER.readTree(SarifExporter.toJson(baseResponse(List.of(errorFinding))))
                        .get("runs")
                        .get(0)
                        .get("results")
                        .get(0);

        assertThat(result.get("level").asText()).isEqualTo("error");
    }

    @Test
    void mapsInfoSeverityToSarifLevelNote() throws Exception {
        Finding infoFinding =
                new Finding(
                        FindingSeverity.INFO,
                        "Field injection",
                        null,
                        "SPRING_FIELD_INJECTION",
                        "Field injection detected",
                        FindingCategory.MAINTAINABILITY,
                        FindingRuntimeDetection.NOT_NORMALLY_DETECTED,
                        FindingConfidence.HIGH,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of());

        JsonNode result =
                MAPPER.readTree(SarifExporter.toJson(baseResponse(List.of(infoFinding))))
                        .get("runs")
                        .get(0)
                        .get("results")
                        .get(0);

        assertThat(result.get("level").asText()).isEqualTo("note");
    }

    @Test
    void deduplicatesRulesOneEntryPerRuleId() throws Exception {
        List<Finding> twoOccurrences =
                List.of(
                        baseFinding(),
                        new Finding(
                                FindingSeverity.WARNING,
                                "Another empty catch block.",
                                "src/main/java/com/example/Other.java",
                                "JAVA_EMPTY_CATCH_BLOCK",
                                "Empty catch block",
                                FindingCategory.EXCEPTION_HANDLING,
                                FindingRuntimeDetection.NOT_NORMALLY_DETECTED,
                                FindingConfidence.HIGH,
                                null,
                                null,
                                null,
                                null,
                                null,
                                "src/main/java/com/example/Other.java",
                                10,
                                "Other#run",
                                null,
                                List.of(),
                                List.of(),
                                List.of()));

        JsonNode rules =
                MAPPER.readTree(SarifExporter.toJson(baseResponse(twoOccurrences)))
                        .get("runs")
                        .get(0)
                        .get("tool")
                        .get("driver")
                        .get("rules");

        assertThat(rules.size()).isEqualTo(1);
        assertThat(rules.get(0).get("id").asText()).isEqualTo("JAVA_EMPTY_CATCH_BLOCK");
    }

    @Test
    void emitsPhysicalLocationWithSrcRootUriBaseId() throws Exception {
        JsonNode physLoc =
                MAPPER.readTree(SarifExporter.toJson(baseResponse(List.of(baseFinding()))))
                        .get("runs")
                        .get(0)
                        .get("results")
                        .get(0)
                        .get("locations")
                        .get(0)
                        .get("physicalLocation");

        assertThat(physLoc.get("artifactLocation").get("uri").asText())
                .isEqualTo("src/main/java/com/example/Demo.java");
        assertThat(physLoc.get("artifactLocation").get("uriBaseId").asText())
                .isEqualTo("%SRCROOT%");
        assertThat(physLoc.get("region").get("startLine").asInt()).isEqualTo(3);
        assertThat(physLoc.get("region").get("endLine").asInt()).isEqualTo(5);
    }

    @Test
    void normalisesBackslashPathsToForwardSlashes() throws Exception {
        Finding windowsPathFinding =
                new Finding(
                        FindingSeverity.WARNING,
                        "msg",
                        null,
                        "SOME_RULE",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new SourceLocation(
                                "src\\main\\java\\com\\example\\Demo.java",
                                1,
                                1,
                                null,
                                null,
                                null,
                                "java",
                                null),
                        List.of(),
                        List.of(),
                        List.of());

        JsonNode uri =
                MAPPER.readTree(SarifExporter.toJson(baseResponse(List.of(windowsPathFinding))))
                        .get("runs")
                        .get(0)
                        .get("results")
                        .get(0)
                        .get("locations")
                        .get(0)
                        .get("physicalLocation")
                        .get("artifactLocation")
                        .get("uri");

        assertThat(uri.asText()).doesNotContain("\\");
        assertThat(uri.asText()).isEqualTo("src/main/java/com/example/Demo.java");
    }

    @Test
    void includesOccurrencesAsRelatedLocations() throws Exception {
        FindingOccurrence occ =
                new FindingOccurrence(
                        "Occurrence at B",
                        new SourceLocation(
                                "src/main/java/com/example/B.java",
                                20,
                                22,
                                null,
                                null,
                                null,
                                "java",
                                null),
                        List.of());
        Finding findingWithOccurrence =
                new Finding(
                        FindingSeverity.WARNING,
                        "msg",
                        null,
                        "JAVA_EMPTY_CATCH_BLOCK",
                        "Empty catch block",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(occ),
                        List.of());

        JsonNode relatedLocations =
                MAPPER.readTree(SarifExporter.toJson(baseResponse(List.of(findingWithOccurrence))))
                        .get("runs")
                        .get(0)
                        .get("results")
                        .get(0)
                        .get("relatedLocations");

        assertThat(relatedLocations).isNotNull();
        assertThat(relatedLocations.size()).isEqualTo(1);
        assertThat(
                        relatedLocations
                                .get(0)
                                .get("physicalLocation")
                                .get("artifactLocation")
                                .get("uri")
                                .asText())
                .isEqualTo("src/main/java/com/example/B.java");
    }

    @Test
    void handlesEmptyFindingsListGracefully() throws Exception {
        JsonNode doc = MAPPER.readTree(SarifExporter.toJson(baseResponse(List.of())));
        assertThat(doc.get("runs").get(0).get("results").size()).isEqualTo(0);
        assertThat(doc.get("runs").get(0).get("tool").get("driver").get("rules").size())
                .isEqualTo(0);
    }
}
