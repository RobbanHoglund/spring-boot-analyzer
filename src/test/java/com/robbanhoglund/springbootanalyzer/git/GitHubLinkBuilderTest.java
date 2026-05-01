package com.robbanhoglund.springbootanalyzer.git;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GitHubLinkBuilderTest {

    private final GitHubLinkBuilder builder = new GitHubLinkBuilder();

    @Test
    void buildsBlobUrlForHttpsRepositoryWithGitSuffix() {
        assertThat(builder.buildBlobUrl(
                "https://github.com/owner/repo.git",
                "abc123",
                "src/main/java/com/example/Demo.java",
                10,
                12
        )).isEqualTo("https://github.com/owner/repo/blob/abc123/src/main/java/com/example/Demo.java#L10-L12");
    }

    @Test
    void buildsBlobUrlForHttpsRepositoryWithoutGitSuffix() {
        assertThat(builder.buildBlobUrl(
                "https://github.com/owner/repo",
                "abc123",
                "src/main/java/com/example/Demo.java",
                10,
                10
        )).isEqualTo("https://github.com/owner/repo/blob/abc123/src/main/java/com/example/Demo.java#L10");
    }

    @Test
    void buildsBlobUrlForSshRepository() {
        assertThat(builder.buildBlobUrl(
                "git@github.com:owner/repo.git",
                "abc123",
                "src/main/java/com/example/Demo.java",
                10,
                12
        )).isEqualTo("https://github.com/owner/repo/blob/abc123/src/main/java/com/example/Demo.java#L10-L12");
    }

    @Test
    void returnsNullForUnsupportedUrl() {
        assertThat(builder.buildBlobUrl(
                "https://gitlab.com/owner/repo.git",
                "abc123",
                "src/main/java/com/example/Demo.java",
                10,
                12
        )).isNull();
    }
}
