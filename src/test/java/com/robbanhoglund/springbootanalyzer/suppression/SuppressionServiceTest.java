package com.robbanhoglund.springbootanalyzer.suppression;

import static org.assertj.core.api.Assertions.assertThat;

import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingSeverity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SuppressionServiceTest {

    private final SuppressionService service = new SuppressionService();

    @TempDir Path repoRoot;

    // ── No suppression file ───────────────────────────────────────────────────

    @Test
    void returnsAllFindingsWhenNoSuppressionFileExists() {
        List<Finding> findings =
                List.of(finding("SPRING_FIELD_INJECTION"), finding("SPRING_JPA_OPEN_IN_VIEW"));
        assertThat(service.apply(findings, repoRoot)).hasSize(2);
    }

    // ── Suppression by ruleId ─────────────────────────────────────────────────

    @Test
    void suppressesFindingByRuleId() throws IOException {
        writeSuppressionFile(
                """
                suppress:
                  - ruleId: SPRING_FIELD_INJECTION
                    reason: "Legacy code"
                """);

        List<Finding> findings =
                List.of(finding("SPRING_FIELD_INJECTION"), finding("SPRING_JPA_OPEN_IN_VIEW"));
        List<Finding> result = service.apply(findings, repoRoot);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).ruleId()).isEqualTo("SPRING_JPA_OPEN_IN_VIEW");
    }

    @Test
    void suppressesMultipleRuleIds() throws IOException {
        writeSuppressionFile(
                """
                suppress:
                  - ruleId: SPRING_FIELD_INJECTION
                  - ruleId: SPRING_JPA_OPEN_IN_VIEW
                """);

        List<Finding> findings =
                List.of(
                        finding("SPRING_FIELD_INJECTION"),
                        finding("SPRING_JPA_OPEN_IN_VIEW"),
                        finding("SPRING_CSRF_DISABLED"));
        List<Finding> result = service.apply(findings, repoRoot);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).ruleId()).isEqualTo("SPRING_CSRF_DISABLED");
    }

    @Test
    void suppressesAllMatchingOccurrencesOfSameRule() throws IOException {
        writeSuppressionFile(
                """
                suppress:
                  - ruleId: SPRING_FIELD_INJECTION
                """);

        List<Finding> findings =
                List.of(
                        finding("SPRING_FIELD_INJECTION"),
                        finding("SPRING_FIELD_INJECTION"),
                        finding("SPRING_FIELD_INJECTION"));
        assertThat(service.apply(findings, repoRoot)).isEmpty();
    }

    @Test
    void doesNotSuppressFindingsWithDifferentRuleId() throws IOException {
        writeSuppressionFile(
                """
                suppress:
                  - ruleId: SPRING_FIELD_INJECTION
                """);

        List<Finding> findings = List.of(finding("SPRING_JPA_OPEN_IN_VIEW"));
        assertThat(service.apply(findings, repoRoot)).hasSize(1);
    }

    @Test
    void doesNotSuppressFindingsWithNullRuleId() throws IOException {
        writeSuppressionFile(
                """
                suppress:
                  - ruleId: SPRING_FIELD_INJECTION
                """);

        List<Finding> findings = List.of(findingWithoutRuleId());
        assertThat(service.apply(findings, repoRoot)).hasSize(1);
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    void handlesEmptySuppressListGracefully() throws IOException {
        writeSuppressionFile("suppress: []\n");

        List<Finding> findings = List.of(finding("SPRING_FIELD_INJECTION"));
        assertThat(service.apply(findings, repoRoot)).hasSize(1);
    }

    @Test
    void handlesEntryWithNullRuleIdGracefully() throws IOException {
        writeSuppressionFile(
                """
                suppress:
                  - reason: "No ruleId here — should be ignored"
                """);

        List<Finding> findings = List.of(finding("SPRING_FIELD_INJECTION"));
        assertThat(service.apply(findings, repoRoot)).hasSize(1);
    }

    @Test
    void handlesInvalidYamlGracefully() throws IOException {
        Files.writeString(repoRoot.resolve(".analyzer-suppress.yml"), "suppress: [[[invalid");

        List<Finding> findings = List.of(finding("SPRING_FIELD_INJECTION"));
        // Should not throw; returns all findings unchanged
        assertThat(service.apply(findings, repoRoot)).hasSize(1);
    }

    @Test
    void returnsEmptyListWhenAllFindingsSuppressed() throws IOException {
        writeSuppressionFile(
                """
                suppress:
                  - ruleId: SPRING_FIELD_INJECTION
                  - ruleId: SPRING_JPA_OPEN_IN_VIEW
                """);

        List<Finding> findings =
                List.of(finding("SPRING_FIELD_INJECTION"), finding("SPRING_JPA_OPEN_IN_VIEW"));
        assertThat(service.apply(findings, repoRoot)).isEmpty();
    }

    @Test
    void returnsOriginalListWhenFindingsEmpty() throws IOException {
        writeSuppressionFile(
                """
                suppress:
                  - ruleId: SPRING_FIELD_INJECTION
                """);

        assertThat(service.apply(List.of(), repoRoot)).isEmpty();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void writeSuppressionFile(String content) throws IOException {
        Files.writeString(repoRoot.resolve(".analyzer-suppress.yml"), content);
    }

    private static Finding finding(String ruleId) {
        return new Finding(
                FindingSeverity.INFO,
                "Test finding for " + ruleId,
                null,
                ruleId,
                ruleId,
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
                List.of(),
                List.of());
    }

    private static Finding findingWithoutRuleId() {
        return new Finding(
                FindingSeverity.INFO,
                "Inline finding without rule ID",
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
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of());
    }
}
