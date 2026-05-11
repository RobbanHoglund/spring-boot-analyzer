package com.robbanhoglund.springbootanalyzer.git;

import jakarta.annotation.PreDestroy;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.sshd.JGitKeyCache;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;
import org.eclipse.jgit.util.FS;
import org.springframework.stereotype.Service;

@Service
public class GitCloneService {

    private final SshdSessionFactory sshSessionFactory;
    private final TransportConfigCallback sshTransportConfigCallback;

    public GitCloneService() {
        this.sshSessionFactory = createSshSessionFactory();
        this.sshTransportConfigCallback = this::configureTransport;
    }

    public Path cloneRepository(
            GitRepositoryReference repositoryReference, Path destinationDirectory) {
        RepositoryEndpoint repositoryEndpoint = resolveRepositoryEndpoint(repositoryReference);

        try {
            Files.createDirectories(destinationDirectory.getParent());

            CloneCommand cloneCommand =
                    Git.cloneRepository()
                            .setURI(repositoryEndpoint.uri().toString())
                            .setDirectory(destinationDirectory.toFile())
                            .setCloneSubmodules(false)
                            .setDepth(1);

            if (repositoryEndpoint.protocol() == RepositoryProtocol.SSH) {
                cloneCommand.setTransportConfigCallback(sshTransportConfigCallback);
            }
            if (repositoryEndpoint.protocol() == RepositoryProtocol.HTTPS
                    && repositoryReference.hasCredentials()) {
                cloneCommand.setCredentialsProvider(
                        new UsernamePasswordCredentialsProvider(
                                repositoryReference.credentials().safeUsername(),
                                repositoryReference.credentials().token()));
            }

            if (repositoryReference.branch() != null) {
                String branchRef =
                        repositoryReference.branch().startsWith("refs/heads/")
                                ? repositoryReference.branch()
                                : "refs/heads/" + repositoryReference.branch();
                cloneCommand.setBranch(branchRef);
                cloneCommand.setBranchesToClone(List.of(branchRef));
                cloneCommand.setCloneAllBranches(false);
            }

            try (Git ignored = cloneCommand.call()) {
                return destinationDirectory;
            }
        } catch (GitAPIException exception) {
            throw new GitCloneException(
                    buildCloneFailureMessage(repositoryReference, exception), exception);
        } catch (Exception exception) {
            throw new GitCloneException(
                    "Failed to prepare workspace for repository clone.", exception);
        }
    }

    public Optional<String> resolveHeadCommit(Path repositoryDirectory) {
        try (Git git = Git.open(repositoryDirectory.toFile())) {
            return Optional.ofNullable(git.getRepository().resolve("HEAD"))
                    .map(objectId -> objectId.name());
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    RepositoryEndpoint resolveRepositoryEndpoint(GitRepositoryReference repositoryReference) {
        RepositoryEndpoint repositoryEndpoint =
                parseAndValidate(repositoryReference.repositoryUrl());
        if (repositoryEndpoint.protocol() == RepositoryProtocol.SSH
                && repositoryReference.hasCredentials()) {
            throw new InvalidRepositoryReferenceException(
                    "Browser-stored HTTPS tokens cannot be used for SSH repository URLs. Configure"
                            + " SSH keys on the server instead.");
        }
        return repositoryEndpoint;
    }

    RepositoryEndpoint parseAndValidate(String repositoryUrl) {
        if (repositoryUrl == null || repositoryUrl.isBlank()) {
            throw new InvalidRepositoryReferenceException("Repository URL must not be blank.");
        }

        try {
            URIish uri = new URIish(repositoryUrl);
            String scheme = uri.getScheme();

            if ("https".equalsIgnoreCase(scheme)) {
                return validateHttps(uri);
            }
            if ("ssh".equalsIgnoreCase(scheme)) {
                return validateSsh(uri);
            }
            if (scheme == null && uri.getHost() != null) {
                return validateScpStyleSsh(uri);
            }

            throw new UnsupportedRepositoryProtocolException(
                    "Only https and ssh repository URLs are supported.");
        } catch (java.net.URISyntaxException exception) {
            throw new InvalidRepositoryReferenceException("Repository URL is not a valid URI.");
        }
    }

    @PreDestroy
    void close() {
        sshSessionFactory.close();
    }

    private RepositoryEndpoint validateHttps(URIish uri) {
        if (isBlank(uri.getHost())) {
            throw new InvalidRepositoryReferenceException(
                    "Repository URL must include a valid host.");
        }
        if (!isBlank(uri.getUser()) || !isBlank(uri.getPass())) {
            throw new InvalidRepositoryReferenceException(
                    "HTTPS repository URLs with embedded credentials are not supported.");
        }
        if (isBlank(uri.getPath())) {
            throw new InvalidRepositoryReferenceException(
                    "Repository URL must include a repository path.");
        }
        return new RepositoryEndpoint(uri, RepositoryProtocol.HTTPS);
    }

    private RepositoryEndpoint validateSsh(URIish uri) {
        if (isBlank(uri.getHost())) {
            throw new InvalidRepositoryReferenceException(
                    "Repository URL must include a valid host.");
        }
        if (isBlank(uri.getUser())) {
            throw new InvalidRepositoryReferenceException(
                    "SSH repository URLs must include a user.");
        }
        if (!isBlank(uri.getPass())) {
            throw new InvalidRepositoryReferenceException(
                    "SSH repository URLs with embedded passwords are not supported.");
        }
        if (isBlank(uri.getPath())) {
            throw new InvalidRepositoryReferenceException(
                    "Repository URL must include a repository path.");
        }
        return new RepositoryEndpoint(uri, RepositoryProtocol.SSH);
    }

    private RepositoryEndpoint validateScpStyleSsh(URIish uri) {
        if (isBlank(uri.getUser()) || isBlank(uri.getHost()) || isBlank(uri.getPath())) {
            throw new UnsupportedRepositoryProtocolException(
                    "Only https and ssh repository URLs are supported.");
        }
        if (!isBlank(uri.getPass())) {
            throw new InvalidRepositoryReferenceException(
                    "SSH repository URLs with embedded passwords are not supported.");
        }
        return new RepositoryEndpoint(uri, RepositoryProtocol.SSH);
    }

    private void configureTransport(Transport transport) {
        if (transport instanceof SshTransport sshTransport) {
            sshTransport.setSshSessionFactory(sshSessionFactory);
        }
    }

    private SshdSessionFactory createSshSessionFactory() {
        File homeDirectory = FS.DETECTED.userHome();
        SshdSessionFactoryBuilder builder =
                new SshdSessionFactoryBuilder().withDefaultConnectorFactory();

        if (homeDirectory != null) {
            builder.setHomeDirectory(homeDirectory);
            builder.setSshDirectory(new File(homeDirectory, ".ssh"));
        }

        return builder.build(new JGitKeyCache());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String buildCloneFailureMessage(
            GitRepositoryReference repositoryReference, GitAPIException exception) {
        String baseMessage = "Failed to clone repository: " + repositoryReference.repositoryUrl();
        String causeMessage = sanitizeMessage(extractCauseMessage(exception));

        if (causeMessage == null || causeMessage.isBlank()) {
            return baseMessage;
        }

        String normalized = causeMessage.toLowerCase(Locale.ROOT);
        if (normalized.contains("repository not found")
                || normalized.contains("not authorized")
                || normalized.contains("authentication")
                || normalized.contains("access denied")) {
            return baseMessage
                    + ". Repository was not found or access was denied. "
                    + "For a private HTTPS repository, select a token profile. "
                    + "For SSH, configure server-side SSH access.";
        }

        return baseMessage + ". " + causeMessage;
    }

    private String extractCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                return current.getMessage();
            }
            current = current.getCause();
        }
        return null;
    }

    private String sanitizeMessage(String message) {
        if (message == null) {
            return null;
        }
        return message.replaceAll("://[^\\s/@:]+:[^\\s/@]+@", "://***:***@");
    }

    enum RepositoryProtocol {
        HTTPS,
        SSH
    }

    record RepositoryEndpoint(URIish uri, RepositoryProtocol protocol) {}
}
