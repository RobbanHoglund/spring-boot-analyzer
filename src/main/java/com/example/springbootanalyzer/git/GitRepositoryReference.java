package com.example.springbootanalyzer.git;

import com.example.springbootanalyzer.api.dto.AnalysisMode;

public record GitRepositoryReference(
        String repositoryUrl,
        String branch,
        GitRepositoryCredentials credentials,
        AnalysisMode analysisMode
) {

    public GitRepositoryReference(String repositoryUrl, String branch) {
        this(repositoryUrl, branch, null, AnalysisMode.STATIC_ONLY);
    }

    public GitRepositoryReference(String repositoryUrl, String branch, GitRepositoryCredentials credentials) {
        this(repositoryUrl, branch, credentials, AnalysisMode.STATIC_ONLY);
    }

    public GitRepositoryReference {
        repositoryUrl = repositoryUrl == null ? null : repositoryUrl.trim();
        branch = normalizeBranch(branch);
        analysisMode = analysisMode == null ? AnalysisMode.STATIC_ONLY : analysisMode;
    }

    public boolean hasCredentials() {
        return credentials != null && credentials.hasToken();
    }

    private static String normalizeBranch(String branch) {
        if (branch == null) {
            return null;
        }
        String trimmed = branch.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
