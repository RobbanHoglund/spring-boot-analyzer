package com.example.springbootanalyzer.git;

public record GitRepositoryReference(
        String repositoryUrl,
        String branch,
        GitRepositoryCredentials credentials
) {

    public GitRepositoryReference(String repositoryUrl, String branch) {
        this(repositoryUrl, branch, null);
    }

    public GitRepositoryReference {
        repositoryUrl = repositoryUrl == null ? null : repositoryUrl.trim();
        branch = normalizeBranch(branch);
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
