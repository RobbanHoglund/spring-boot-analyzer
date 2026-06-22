package com.robbanhoglund.springbootanalyzer.git;

import com.robbanhoglund.springbootanalyzer.api.dto.AnalysisMode;
import java.util.Locale;
import org.eclipse.jgit.transport.URIish;

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

    public String logLabel() {
        return safeRepositoryLabel(repositoryUrl);
    }

    /**
     * Returns a redacted representation that is safe to include in log messages.
     * Credentials are summarised as a boolean flag and repository paths are omitted.
     */
    @Override
    public String toString() {
        return "GitRepositoryReference[repository="
                + logLabel()
                + ", branch="
                + branch
                + ", credentialsPresent="
                + hasCredentials()
                + ", analysisMode="
                + analysisMode
                + "]";
    }

    private static String safeRepositoryLabel(String repositoryUrl) {
        if (repositoryUrl == null || repositoryUrl.isBlank()) {
            return "blank";
        }
        try {
            URIish uri = new URIish(repositoryUrl);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return "unparseable";
            }
            String scheme = uri.getScheme();
            if (scheme == null || scheme.isBlank()) {
                return "ssh://" + host;
            }
            return scheme.toLowerCase(Locale.ROOT) + "://" + host;
        } catch (java.net.URISyntaxException exception) {
            return "unparseable";
        }
    }

    private static String normalizeBranch(String branch) {
        if (branch == null) {
            return null;
        }
        String trimmed = branch.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
