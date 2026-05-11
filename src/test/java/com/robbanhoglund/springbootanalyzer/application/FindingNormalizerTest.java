package com.robbanhoglund.springbootanalyzer.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingCategory;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingConfidence;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingFactory;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingRuntimeDetection;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingSeverity;
import com.robbanhoglund.springbootanalyzer.analyzer.model.SourceLocation;
import java.util.List;
import org.junit.jupiter.api.Test;

class FindingNormalizerTest {

    private final FindingNormalizer normalizer = new FindingNormalizer();

    // ----- helpers ---------------------------------------------------------

    private static Finding catchFinding(String ruleId, String sourceFile, int line, String target) {
        return FindingFactory.builder(
                        ruleId,
                        ruleId + " title",
                        FindingSeverity.WARNING,
                        FindingCategory.EXCEPTION_HANDLING,
                        FindingRuntimeDetection.NOT_NORMALLY_DETECTED,
                        FindingConfidence.HIGH)
                .sourceLocation(
                        new SourceLocation(
                                sourceFile, line, line, null, null, target, "java", null))
                .target(target)
                .evidence("Evidence for " + ruleId)
                .build();
    }

    private static Finding targetFinding(
            String ruleId, FindingCategory category, String sourceFile, int line, String target) {
        return FindingFactory.builder(
                        ruleId,
                        ruleId + " title",
                        FindingSeverity.WARNING,
                        category,
                        FindingRuntimeDetection.NOT_NORMALLY_DETECTED,
                        FindingConfidence.HIGH)
                .sourceLocation(
                        new SourceLocation(
                                sourceFile, line, line, null, null, target, "java", null))
                .target(target)
                .evidence("Evidence for " + ruleId)
                .build();
    }

    // ----- tests -----------------------------------------------------------

    @Test
    void returnsSingleFindingUnchanged() {
        Finding finding = catchFinding("JAVA_EMPTY_CATCH_BLOCK", "src/Foo.java", 10, "Foo#run");

        List<Finding> result = normalizer.normalize(List.of(finding));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).ruleId()).isEqualTo("JAVA_EMPTY_CATCH_BLOCK");
        assertThat(result.get(0).relatedSignals()).isEmpty();
    }

    @Test
    void mergesBroadFatalCatchIntoSwallowedFallbackForSameCatchBlock() {
        Finding swallowed =
                catchFinding("SPRING_SWALLOWED_EXCEPTION_FALLBACK", "src/Foo.java", 20, "Foo#run");
        Finding broadFatal =
                catchFinding("SPRING_BROAD_FATAL_ERROR_CATCH", "src/Foo.java", 20, "Foo#run");

        List<Finding> result = normalizer.normalize(List.of(swallowed, broadFatal));

        assertThat(result).hasSize(1);
        Finding primary = result.get(0);
        assertThat(primary.ruleId()).isEqualTo("SPRING_SWALLOWED_EXCEPTION_FALLBACK");
        assertThat(primary.relatedSignals()).hasSize(1);
        assertThat(primary.relatedSignals().get(0).ruleId())
                .isEqualTo("SPRING_BROAD_FATAL_ERROR_CATCH");
    }

    @Test
    void mergesInterruptedExceptionAndPrintStackTraceForSameTarget() {
        Finding interrupted =
                catchFinding(
                        "SPRING_INTERRUPTED_EXCEPTION_SWALLOWED",
                        "src/Bar.java",
                        15,
                        "Bar#process");
        Finding printStack =
                targetFinding(
                        "SPRING_PRINT_STACK_TRACE",
                        FindingCategory.EXCEPTION_HANDLING,
                        "src/Bar.java",
                        17,
                        "Bar#process");

        List<Finding> result = normalizer.normalize(List.of(interrupted, printStack));

        assertThat(result).hasSize(1);
        Finding primary = result.get(0);
        assertThat(primary.ruleId()).isEqualTo("SPRING_INTERRUPTED_EXCEPTION_SWALLOWED");
        assertThat(primary.relatedSignals()).hasSize(1);
        assertThat(primary.relatedSignals().get(0).ruleId()).isEqualTo("SPRING_PRINT_STACK_TRACE");
    }

    @Test
    void mergesRawExceptionHttpAndBroadExceptionHandlerForSameTarget() {
        Finding rawHttp =
                targetFinding(
                        "SPRING_RAW_EXCEPTION_MESSAGE_HTTP",
                        FindingCategory.SECURITY,
                        "src/Handler.java",
                        30,
                        "Handler#handle");
        Finding broadHandler =
                targetFinding(
                        "SPRING_BROAD_EXCEPTION_HANDLER",
                        FindingCategory.EXCEPTION_HANDLING,
                        "src/Handler.java",
                        30,
                        "Handler#handle");

        List<Finding> result = normalizer.normalize(List.of(rawHttp, broadHandler));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).ruleId()).isEqualTo("SPRING_RAW_EXCEPTION_MESSAGE_HTTP");
        assertThat(result.get(0).relatedSignals().get(0).ruleId())
                .isEqualTo("SPRING_BROAD_EXCEPTION_HANDLER");
    }

    @Test
    void doesNotMergeFindingsFromDifferentSourceFiles() {
        Finding a =
                catchFinding("SPRING_SWALLOWED_EXCEPTION_FALLBACK", "src/Foo.java", 10, "Foo#run");
        Finding b = catchFinding("SPRING_BROAD_FATAL_ERROR_CATCH", "src/Bar.java", 10, "Bar#run");

        List<Finding> result = normalizer.normalize(List.of(a, b));

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(f -> f.relatedSignals().isEmpty());
    }

    @Test
    void doesNotMergeSameRuleAtDifferentLines() {
        Finding a = catchFinding("SPRING_BROAD_FATAL_ERROR_CATCH", "src/Foo.java", 10, "Foo#run");
        Finding b =
                catchFinding("SPRING_SWALLOWED_EXCEPTION_FALLBACK", "src/Foo.java", 40, "Foo#run");

        List<Finding> result = normalizer.normalize(List.of(a, b));

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(f -> f.relatedSignals().isEmpty());
    }

    @Test
    void doesNotMergeOverlappingPairFromDifferentTargets() {
        Finding interrupted =
                catchFinding(
                        "SPRING_INTERRUPTED_EXCEPTION_SWALLOWED",
                        "src/Bar.java",
                        15,
                        "Bar#methodA");
        Finding printStack =
                targetFinding(
                        "SPRING_PRINT_STACK_TRACE",
                        FindingCategory.EXCEPTION_HANDLING,
                        "src/Bar.java",
                        17,
                        "Bar#methodB");

        List<Finding> result = normalizer.normalize(List.of(interrupted, printStack));

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(f -> f.relatedSignals().isEmpty());
    }

    @Test
    void preservesUnrelatedFindingsAlongside() {
        Finding emptyCatch = catchFinding("JAVA_EMPTY_CATCH_BLOCK", "src/Foo.java", 10, "Foo#run");
        Finding broadFatal =
                catchFinding("SPRING_BROAD_FATAL_ERROR_CATCH", "src/Foo.java", 10, "Foo#run");
        Finding unrelated =
                targetFinding(
                        "SPRING_FIELD_INJECTION",
                        FindingCategory.MAINTAINABILITY,
                        "src/Bar.java",
                        5,
                        "Bar");

        List<Finding> result = normalizer.normalize(List.of(emptyCatch, broadFatal, unrelated));

        assertThat(result).hasSize(2);
        Finding merged =
                result.stream()
                        .filter(f -> "JAVA_EMPTY_CATCH_BLOCK".equals(f.ruleId()))
                        .findFirst()
                        .orElseThrow();
        assertThat(merged.relatedSignals()).hasSize(1);
        assertThat(result.stream().filter(f -> "SPRING_FIELD_INJECTION".equals(f.ruleId())))
                .hasSize(1);
    }

    @Test
    void relatedSignalsPreserveEvidenceText() {
        Finding swallowed =
                catchFinding("SPRING_SWALLOWED_EXCEPTION_FALLBACK", "src/Foo.java", 20, "Foo#run");
        Finding broadFatal =
                catchFinding("SPRING_BROAD_FATAL_ERROR_CATCH", "src/Foo.java", 20, "Foo#run");

        List<Finding> result = normalizer.normalize(List.of(swallowed, broadFatal));

        String evidence = result.get(0).relatedSignals().get(0).evidence();
        assertThat(evidence).isEqualTo("Evidence for SPRING_BROAD_FATAL_ERROR_CATCH");
    }
}
