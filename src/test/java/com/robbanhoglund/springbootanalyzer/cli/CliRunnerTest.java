package com.robbanhoglund.springbootanalyzer.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.robbanhoglund.springbootanalyzer.analyzer.model.AnalysisResult;
import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildInfo;
import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildTool;
import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingSeverity;
import com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.ConfigurationAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleAnalysisStatus;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleModelAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.http.HttpSurfaceAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.messaging.MessagingAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.scheduling.SchedulingAnalysis;
import com.robbanhoglund.springbootanalyzer.application.RepositoryAnalysisService;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class CliRunnerTest {

    @TempDir Path tempDir;

    @Test
    void returnsOneWhenErrorFindingMeetsErrorThreshold() {
        assertThat(executeWith(FindingSeverity.ERROR, "error.json")).isEqualTo(1);
    }

    @Test
    void returnsZeroWhenWarningFindingIsBelowErrorThreshold() {
        assertThat(executeWith(FindingSeverity.WARNING, "warning.json")).isZero();
    }

    private int executeWith(FindingSeverity severity, String outputName) {
        RepositoryAnalysisService analysisService = mock(RepositoryAnalysisService.class);
        given(analysisService.analyze(any())).willReturn(analysisResultWith(severity));
        CliRunner runner = new CliRunner(analysisService);

        return new CommandLine(runner)
                .execute(
                        "--repo",
                        "https://github.com/example/demo.git",
                        "--fail-on=error",
                        "--format=json",
                        "--output",
                        tempDir.resolve(outputName).toString(),
                        "--quiet");
    }

    private static AnalysisResult analysisResultWith(FindingSeverity severity) {
        BuildInfo buildInfo =
                new BuildInfo(BuildTool.GRADLE, true, "25", List.of(), "3.5.13", "test", "HIGH");
        return new AnalysisResult(
                "https://github.com/example/demo.git",
                "main",
                "workspace-1",
                "analysis-1",
                "abc123",
                buildInfo,
                List.of(),
                List.of(),
                List.of(new Finding(severity, "Threshold test finding", null)),
                ConfigurationAnalysis.empty(),
                null,
                HttpSurfaceAnalysis.empty(),
                GradleModelAnalysis.empty(
                        GradleAnalysisStatus.NOT_REQUESTED, "TOOLING_API", List.of()),
                SchedulingAnalysis.empty(),
                MessagingAnalysis.empty());
    }
}
