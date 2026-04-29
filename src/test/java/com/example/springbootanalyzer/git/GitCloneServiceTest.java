package com.example.springbootanalyzer.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GitCloneServiceTest {

    private final GitCloneService gitCloneService = new GitCloneService();

    @AfterEach
    void tearDown() {
        gitCloneService.close();
    }

    @Test
    void acceptsHttpsRepositoryUrls() {
        var endpoint = gitCloneService.parseAndValidate("https://github.com/owner/repo.git");

        assertThat(endpoint.protocol()).isEqualTo(GitCloneService.RepositoryProtocol.HTTPS);
        assertThat(endpoint.uri().toString()).isEqualTo("https://github.com/owner/repo.git");
    }

    @Test
    void acceptsSshSchemeRepositoryUrls() {
        var endpoint = gitCloneService.parseAndValidate("ssh://git@github.com/owner/repo.git");

        assertThat(endpoint.protocol()).isEqualTo(GitCloneService.RepositoryProtocol.SSH);
        assertThat(endpoint.uri().toString()).isEqualTo("ssh://git@github.com/owner/repo.git");
    }

    @Test
    void acceptsScpStyleSshRepositoryUrls() {
        var endpoint = gitCloneService.parseAndValidate("git@github.com:owner/repo.git");

        assertThat(endpoint.protocol()).isEqualTo(GitCloneService.RepositoryProtocol.SSH);
        assertThat(endpoint.uri().toString()).isEqualTo("git@github.com:owner/repo.git");
    }

    @Test
    void rejectsHttpRepositoryUrls() {
        assertThatThrownBy(() -> gitCloneService.parseAndValidate("http://github.com/owner/repo.git"))
                .isInstanceOf(UnsupportedRepositoryProtocolException.class)
                .hasMessage("Only https and ssh repository URLs are supported.");
    }

    @Test
    void rejectsGitProtocolRepositoryUrls() {
        assertThatThrownBy(() -> gitCloneService.parseAndValidate("git://github.com/owner/repo.git"))
                .isInstanceOf(UnsupportedRepositoryProtocolException.class)
                .hasMessage("Only https and ssh repository URLs are supported.");
    }

    @Test
    void rejectsFileRepositoryUrls() {
        assertThatThrownBy(() -> gitCloneService.parseAndValidate("file:///tmp/repo.git"))
                .isInstanceOf(UnsupportedRepositoryProtocolException.class)
                .hasMessage("Only https and ssh repository URLs are supported.");
    }

    @Test
    void rejectsEmbeddedHttpsCredentials() {
        assertThatThrownBy(() -> gitCloneService.parseAndValidate("https://user:secret@github.com/owner/repo.git"))
                .isInstanceOf(InvalidRepositoryReferenceException.class)
                .hasMessage("HTTPS repository URLs with embedded credentials are not supported.");
    }

    @Test
    void rejectsEmbeddedSshPasswords() {
        assertThatThrownBy(() -> gitCloneService.parseAndValidate("ssh://git:secret@github.com/owner/repo.git"))
                .isInstanceOf(InvalidRepositoryReferenceException.class)
                .hasMessage("SSH repository URLs with embedded passwords are not supported.");
    }

    @Test
    void rejectsBrowserCredentialsForSshRepositories() {
        GitRepositoryReference repositoryReference = new GitRepositoryReference(
                "git@github.com:owner/repo.git",
                "main",
                new GitRepositoryCredentials("octocat", "ghp_example")
        );

        assertThatThrownBy(() -> gitCloneService.resolveRepositoryEndpoint(repositoryReference))
                .isInstanceOf(InvalidRepositoryReferenceException.class)
                .hasMessage("Browser-stored HTTPS tokens cannot be used for SSH repository URLs. Configure SSH keys on the server instead.");
    }
}
