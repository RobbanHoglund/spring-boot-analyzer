package com.robbanhoglund.springbootanalyzer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.robbanhoglund.springbootanalyzer.analyzer.StaticAnalyzer;
import com.robbanhoglund.springbootanalyzer.analyzer.model.AnalysisResult;
import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildInfo;
import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildTool;
import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingConfidence;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingFactory;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingRules;
import com.robbanhoglund.springbootanalyzer.config.AnalyzerProperties;
import com.robbanhoglund.springbootanalyzer.git.GitCloneService;
import com.robbanhoglund.springbootanalyzer.git.GitHubLinkBuilder;
import com.robbanhoglund.springbootanalyzer.git.GitRepositoryReference;
import com.robbanhoglund.springbootanalyzer.suppression.SuppressionService;
import com.robbanhoglund.springbootanalyzer.workspace.WorkspaceService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.Environment;

class RepositoryAnalysisServiceSuppressionTest {

    @TempDir Path tempDir;

    @Test
    void threadsRepositorySuppressionSummaryIntoAnalysisResult() throws Exception {
        Path workspacePath = Files.createDirectories(tempDir.resolve("workspace"));
        Path repositoryRoot = Files.createDirectories(workspacePath.resolve("repository"));
        Files.writeString(
                repositoryRoot.resolve(".analyzer-suppress.yml"),
                """
                suppress:
                  - ruleId: SPRING_FIELD_INJECTION
                  - ruleId: SPRING_TYPO
                """);
        Finding finding =
                FindingFactory.builder(FindingRules.SPRING_FIELD_INJECTION, FindingConfidence.HIGH)
                        .build();
        GitRepositoryReference reference =
                new GitRepositoryReference("https://github.com/example/demo.git", "main");

        WorkspaceService workspaceService = mock(WorkspaceService.class);
        when(workspaceService.createWorkspace())
                .thenReturn(new WorkspaceService.Workspace("ws-1", workspacePath));
        GitCloneService gitCloneService = mock(GitCloneService.class);
        when(gitCloneService.cloneRepository(eq(reference), any())).thenReturn(repositoryRoot);
        when(gitCloneService.resolveHeadCommit(repositoryRoot)).thenReturn(Optional.of("abc123"));
        StaticAnalyzer staticAnalyzer = mock(StaticAnalyzer.class);
        when(staticAnalyzer.analyze(reference, repositoryRoot, "ws-1"))
                .thenReturn(baseResult(finding));
        AnalyzerProperties analyzerProperties = mock(AnalyzerProperties.class);
        when(analyzerProperties.cleanupAfterAnalysis()).thenReturn(false);
        UserRuleConfigService userRuleConfigService = mock(UserRuleConfigService.class);
        when(userRuleConfigService.knownRuleIds())
                .thenReturn(Set.of(FindingRules.SPRING_FIELD_INJECTION.ruleId()));
        when(userRuleConfigService.getDisabledRuleIds()).thenReturn(Set.of());
        when(userRuleConfigService.fullyDisabledSeverities(Set.of())).thenReturn(Set.of());
        Environment environment = mock(Environment.class);

        RepositoryAnalysisService service =
                new RepositoryAnalysisService(
                        workspaceService,
                        gitCloneService,
                        staticAnalyzer,
                        analyzerProperties,
                        new GitHubLinkBuilder(),
                        new AnalysisSessionRegistry(),
                        new FindingNormalizer(),
                        new SuppressionService(userRuleConfigService),
                        userRuleConfigService,
                        environment);

        AnalysisResult result = service.analyze(reference);

        assertThat(result.findings()).isEmpty();
        assertThat(result.suppressedRuleIds()).containsExactly("SPRING_FIELD_INJECTION");
        assertThat(result.suppressedFindingCount()).isEqualTo(1);
        assertThat(result.unknownSuppressedRuleIds()).containsExactly("SPRING_TYPO");
    }

    private static AnalysisResult baseResult(Finding finding) {
        return new AnalysisResult(
                "https://github.com/example/demo.git",
                "main",
                "ws-1",
                "ws-1",
                null,
                new BuildInfo(BuildTool.GRADLE, true, "25", List.of(), "3.5.0", null, "HIGH"),
                List.of(),
                List.of(),
                List.of(finding),
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
