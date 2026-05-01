package com.robbanhoglund.springbootanalyzer.analyzer;

import com.robbanhoglund.springbootanalyzer.analyzer.model.AnalysisResult;
import com.robbanhoglund.springbootanalyzer.git.GitRepositoryReference;
import java.nio.file.Path;

public interface StaticAnalyzer {

    AnalysisResult analyze(GitRepositoryReference repositoryReference, Path repositoryRoot, String workspaceId);
}
