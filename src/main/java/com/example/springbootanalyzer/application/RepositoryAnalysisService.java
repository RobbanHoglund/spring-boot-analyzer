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
        try {
            Path clonedRepository = gitCloneService.cloneRepository(repositoryReference, workspace.path().resolve("repository"));
            return staticAnalyzer.analyze(repositoryReference, clonedRepository, workspace.id());
        } finally {
            cleanupWorkspace(workspace);
        }
    }

    private void cleanupWorkspace(Workspace workspace) {
        if (!analyzerProperties.cleanupAfterAnalysis()) {
            return;
        }
        try {
            workspaceService.deleteWorkspace(workspace);
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to delete workspace {}", workspace.path(), exception);
        }
    }
}
