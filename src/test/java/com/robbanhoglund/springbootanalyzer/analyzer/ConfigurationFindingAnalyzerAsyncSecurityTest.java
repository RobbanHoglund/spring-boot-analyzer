package com.robbanhoglund.springbootanalyzer.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import com.robbanhoglund.springbootanalyzer.analyzer.configuration.SensitivePropertyValueRedactor;
import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildInfo;
import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildTool;
import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.ConfigurationAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.ConfigurationSummary;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleAnalysisStatus;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleModelAnalysis;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationFindingAnalyzerAsyncSecurityTest {

    @TempDir Path repositoryRoot;

    private final ConfigurationFindingAnalyzer analyzer =
            new ConfigurationFindingAnalyzer(new SensitivePropertyValueRedactor());

    @Test
    void asyncMentionedOnlyInCommentDoesNotCountAsUsage() throws Exception {
        writeSource(
                """
                package com.example;
                class Notes {
                    // @Async is intentionally documented here, not used.
                }
                """);

        assertThat(findings())
                .noneMatch(
                        finding -> "SPRING_ASYNC_SECURITY_CONTEXT_LOST".equals(finding.ruleId()));
    }

    @Test
    void delegatingExecutorMentionedOnlyInCommentDoesNotHideRealAsyncUsage() throws Exception {
        writeSource(
                """
                package com.example;
                import org.springframework.scheduling.annotation.Async;
                class Worker {
                    // Consider DelegatingSecurityContextAsyncTaskExecutor in the future.
                    @Async
                    void run() {}
                }
                """);

        assertThat(findings())
                .anyMatch(finding -> "SPRING_ASYNC_SECURITY_CONTEXT_LOST".equals(finding.ruleId()));
    }

    @Test
    void strategyNameSetAsStringLiteralCountsAsPropagation() throws Exception {
        // SecurityContextHolder.setStrategyName takes a String, so the literal form is the
        // documented usage — matching only SimpleName nodes would report a propagation problem
        // in a project that has already solved it.
        writeSource(
                """
                package com.example;
                import org.springframework.scheduling.annotation.Async;
                import org.springframework.security.core.context.SecurityContextHolder;
                class Worker {
                    Worker() {
                        SecurityContextHolder.setStrategyName("MODE_INHERITABLETHREADLOCAL");
                    }

                    @Async
                    void run() {}
                }
                """);

        assertThat(findings())
                .noneMatch(
                        finding -> "SPRING_ASYNC_SECURITY_CONTEXT_LOST".equals(finding.ruleId()));
    }

    @Test
    void strategySetThroughSystemPropertyLiteralCountsAsPropagation() throws Exception {
        writeSource(
                """
                package com.example;
                import org.springframework.scheduling.annotation.Async;
                class Worker {
                    static {
                        System.setProperty(
                                "spring.security.strategy", "MODE_INHERITABLETHREADLOCAL");
                    }

                    @Async
                    void run() {}
                }
                """);

        assertThat(findings())
                .noneMatch(
                        finding -> "SPRING_ASYNC_SECURITY_CONTEXT_LOST".equals(finding.ruleId()));
    }

    private void writeSource(String source) throws Exception {
        Path directory =
                Files.createDirectories(repositoryRoot.resolve("src/main/java/com/example"));
        Files.writeString(directory.resolve("Worker.java"), source);
    }

    private List<Finding> findings() {
        BuildInfo buildInfo =
                new BuildInfo(
                        BuildTool.GRADLE,
                        true,
                        "25",
                        List.of("org.springframework.boot:spring-boot-starter-security"),
                        "3.5.0",
                        null,
                        "HIGH");
        ConfigurationAnalysis configurationAnalysis =
                new ConfigurationAnalysis(
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        new ConfigurationSummary(0, 0, 0, 0, 0, 0, List.of()));
        return analyzer.analyze(
                repositoryRoot,
                buildInfo,
                configurationAnalysis,
                GradleModelAnalysis.empty(
                        GradleAnalysisStatus.NOT_REQUESTED, "SYSTEM_GRADLE", List.of()));
    }
}
