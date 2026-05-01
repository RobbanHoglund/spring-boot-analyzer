package com.robbanhoglund.springbootanalyzer.git;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class GitHubLinkBuilder {

    private static final Pattern HTTPS_PATTERN = Pattern.compile("^https://github\\.com/([^/]+)/([^/]+?)(?:\\.git)?/?$");
    private static final Pattern SSH_PATTERN = Pattern.compile("^git@github\\.com:([^/]+)/([^/]+?)(?:\\.git)?$");

    public String buildBlobUrl(String repositoryUrl, String commitSha, String relativePath, Integer startLine, Integer endLine) {
        if (repositoryUrl == null || commitSha == null || relativePath == null || relativePath.isBlank()) {
            return null;
        }
        String normalizedPath = relativePath.replace('\\', '/');
        if (normalizedPath.startsWith("/") || normalizedPath.contains("..")) {
            return null;
        }
        RepoCoordinates coordinates = parse(repositoryUrl);
        if (coordinates == null) {
            return null;
        }
        String fragment = buildLineFragment(startLine, endLine);
        return "https://github.com/" + coordinates.owner() + "/" + coordinates.repository()
                + "/blob/" + commitSha + "/" + normalizedPath + fragment;
    }

    private RepoCoordinates parse(String repositoryUrl) {
        String trimmed = repositoryUrl.trim();
        Matcher httpsMatcher = HTTPS_PATTERN.matcher(trimmed);
        if (httpsMatcher.matches()) {
            return new RepoCoordinates(httpsMatcher.group(1), stripGitSuffix(httpsMatcher.group(2)));
        }
        Matcher sshMatcher = SSH_PATTERN.matcher(trimmed);
        if (sshMatcher.matches()) {
            return new RepoCoordinates(sshMatcher.group(1), stripGitSuffix(sshMatcher.group(2)));
        }
        return null;
    }

    private String stripGitSuffix(String repository) {
        String normalized = repository.toLowerCase(Locale.ROOT).endsWith(".git")
                ? repository.substring(0, repository.length() - 4)
                : repository;
        return normalized;
    }

    private String buildLineFragment(Integer startLine, Integer endLine) {
        if (startLine == null || startLine <= 0) {
            return "";
        }
        if (endLine == null || endLine <= startLine) {
            return "#L" + startLine;
        }
        return "#L" + startLine + "-L" + endLine;
    }

    private record RepoCoordinates(String owner, String repository) {
    }
}
