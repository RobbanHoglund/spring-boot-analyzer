package com.example.springbootanalyzer.analyzer;

import com.example.springbootanalyzer.analyzer.model.AnalysisResult;
import com.example.springbootanalyzer.git.GitRepositoryReference;
import java.nio.file.Path;

public interface StaticAnalyzer {

    AnalysisResult analyze(GitRepositoryReference repositoryReference, Path repositoryRoot, String workspaceId);
}
