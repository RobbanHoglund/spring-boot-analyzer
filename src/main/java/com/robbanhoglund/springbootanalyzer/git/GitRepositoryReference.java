package com.robbanhoglund.springbootanalyzer.git;

import com.robbanhoglund.springbootanalyzer.api.dto.AnalysisMode;

public record GitRepositoryReference(
        String repositoryUrl,
        String branch,
        GitRepositoryCredentials credentials,
        AnalysisMode analysisMode) {

    public GitRepositoryReference(String repositoryUrl, String branch) {
        this(repositoryUrl, branch, null, AnalysisMode.STATIC_ONLY);
    }

    public GitRepositoryReference(
            String repositoryUrl, String branch, GitRepositoryCredentials credentials) {
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

    /**
     * Returns a redacted representation that is safe to include in log messages.
     * Credentials are summarised as a boolean flag so tokens are never exposed.
     */
    @Override
    public String toString() {
        return "GitRepositoryReference[repositoryUrl="
                + repositoryUrl
                + ", branch="
                + branch
                + ", credentialsPresent="
                + hasCredentials()
                + ", analysisMode="
                + analysisMode
                + "]";
    }

    private static String normalizeBranch(String branch) {
        if (branch == null) {
            return null;
        }
        String trimmed = branch.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
