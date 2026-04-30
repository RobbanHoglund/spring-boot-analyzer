package com.example.springbootanalyzer.application;

import com.example.springbootanalyzer.analyzer.StaticAnalyzer;
import com.example.springbootanalyzer.analyzer.model.AnalysisResult;
import com.example.springbootanalyzer.config.AnalyzerProperties;
import com.example.springbootanalyzer.git.GitCloneService;
import com.example.springbootanalyzer.git.GitRepositoryReference;
import com.example.springbootanalyzer.workspace.WorkspaceService;
import com.example.springbootanalyzer.workspace.WorkspaceService.Workspace;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RepositoryAnalysisService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RepositoryAnalysisService.class);

    private final WorkspaceService workspaceService;
    private final GitCloneService gitCloneService;
    private final StaticAnalyzer staticAnalyzer;
    private final AnalyzerProperties analyzerProperties;

    public RepositoryAnalysisService(
            WorkspaceService workspaceService,
            GitCloneService gitCloneService,
            StaticAnalyzer staticAnalyzer,
            AnalyzerProperties analyzerProperties
    ) {
        this.workspaceService = workspaceService;
        this.gitCloneService = gitCloneService;
        this.staticAnalyzer = staticAnalyzer;
        this.analyzerProperties = analyzerProperties;
    }

    public AnalysisResult analyze(GitRepositoryReference repositoryReference) {
        Workspace workspace = workspaceService.createWorkspace();
        AnalysisResult result = null;
        LOGGER.info(
                "Starting repository analysis: workspaceId={}, repositoryUrl={}, branch={}, analysisMode={}",
                workspace.id(),
                repositoryReference.repositoryUrl(),
                repositoryReference.branch(),
                repositoryReference.analysisMode()
        );
        try {
            Path clonedRepository = gitCloneService.cloneRepository(repositoryReference, workspace.path().resolve("repository"));
            LOGGER.info("Repository cloned successfully: workspaceId={}, path={}", workspace.id(), clonedRepository);
            result = staticAnalyzer.analyze(repositoryReference, clonedRepository, workspace.id());
            LOGGER.info(
                    "Repository analysis completed: workspaceId={}, findings={}, components={}, gradleStatus={}",
                    workspace.id(),
                    result.findings().size(),
                    result.detectedComponents().size(),
                    result.gradleModelAnalysis() == null ? "none" : result.gradleModelAnalysis().status()
            );
            return result;
        } finally {
            cleanupWorkspace(workspace, result);
        }
    }

    private void cleanupWorkspace(Workspace workspace, AnalysisResult result) {
        if (!analyzerProperties.cleanupAfterAnalysis()) {
            LOGGER.info("Workspace cleanup skipped by configuration: workspaceId={}, path={}", workspace.id(), workspace.path());
            return;
        }
        if (analyzerProperties.workspaceKeepOnGradleFailure()
                && result != null
                && result.gradleModelAnalysis() != null
                && result.gradleModelAnalysis().status() != null
                && !"SUCCESS".equals(result.gradleModelAnalysis().status().name())
                && !"NOT_REQUESTED".equals(result.gradleModelAnalysis().status().name())) {
            LOGGER.info(
                    "Workspace retained because Gradle model analysis failed: workspaceId={}, path={}",
                    workspace.id(),
                    workspace.path()
            );
            return;
        }
        try {
            workspaceService.deleteWorkspace(workspace);
            LOGGER.info("Workspace deleted: workspaceId={}, path={}", workspace.id(), workspace.path());
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to delete workspace {}", workspace.path(), exception);
        }
    }
}
