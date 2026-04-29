package com.robbanhoglund.springbootanalyzer.workspace;

import com.robbanhoglund.springbootanalyzer.config.AnalyzerProperties;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceService {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceService.class);
    private static final int DELETE_RETRY_ATTEMPTS = 5;
    private static final long DELETE_RETRY_DELAY_MILLIS = 200L;
    private static final int DEFERRED_DELETE_RETRY_ATTEMPTS = 30;
    private static final long DEFERRED_DELETE_DELAY_MILLIS = 1_000L;

    private final AnalyzerProperties analyzerProperties;

    public WorkspaceService(AnalyzerProperties analyzerProperties) {
        this.analyzerProperties = analyzerProperties;
    }

    public Workspace createWorkspace() {
        try {
            Files.createDirectories(analyzerProperties.workspaceRoot());
            String workspaceId = UUID.randomUUID().toString();
            Path workspacePath = analyzerProperties.workspaceRoot().resolve(workspaceId);
            Files.createDirectories(workspacePath);
            return new Workspace(workspaceId, workspacePath);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create analyzer workspace.", exception);
        }
    }

    public WorkspaceCleanupResult deleteWorkspacesOlderThan(Duration maxAge) {
        return deleteWorkspacesOlderThan(maxAge, Instant.now());
    }

    WorkspaceCleanupResult deleteWorkspacesOlderThan(Duration maxAge, Instant now) {
        Path workspaceRoot = analyzerProperties.workspaceRoot();
        if (maxAge == null || maxAge.isNegative() || maxAge.isZero()) {
            return new WorkspaceCleanupResult(0, 0, 0);
        }
        if (Files.notExists(workspaceRoot)) {
            return new WorkspaceCleanupResult(0, 0, 0);
        }

        Instant cutoff = now.minus(maxAge);
        int scannedCount = 0;
        int deletedCount = 0;
        int failedCount = 0;

        try (Stream<Path> workspacePaths = Files.list(workspaceRoot).sorted(Comparator.naturalOrder())) {
            for (Path workspacePath : workspacePaths.toList()) {
                if (!Files.isDirectory(workspacePath)) {
                    continue;
                }

                scannedCount++;
                if (!isOlderThan(workspacePath, cutoff)) {
                    continue;
                }

                try {
                    deleteWorkspace(new Workspace(workspacePath.getFileName().toString(), workspacePath));
                    deletedCount++;
                } catch (IllegalStateException exception) {
                    failedCount++;
                    LOGGER.warn("Failed to delete stale workspace {}", workspacePath, exception);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to scan analyzer workspace root: " + workspaceRoot, exception);
        }

        return new WorkspaceCleanupResult(scannedCount, deletedCount, failedCount);
    }

    public void deleteWorkspace(Workspace workspace) {
        Path workspacePath = workspace.path();
        if (Files.notExists(workspacePath)) {
            return;
        }

        IOException lastException = null;
        for (int attempt = 1; attempt <= DELETE_RETRY_ATTEMPTS; attempt++) {
            try {
                deleteWorkspacePath(workspacePath);
                return;
            } catch (AccessDeniedException exception) {
                lastException = exception;
                if (attempt == DELETE_RETRY_ATTEMPTS) {
                    break;
                }
                pauseBeforeRetry(workspacePath, attempt, exception);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to delete analyzer workspace: " + workspacePath, exception);
            }
        }

        scheduleDeferredDeletion(workspacePath, lastException);
    }

    void deleteWorkspacePath(Path workspacePath) throws IOException {
        Files.walkFileTree(workspacePath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void pauseBeforeRetry(Path workspacePath, int attempt, AccessDeniedException exception) {
        try {
            Thread.sleep(DELETE_RETRY_DELAY_MILLIS * attempt);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying workspace cleanup: " + workspacePath, exception);
        }
    }

    private void scheduleDeferredDeletion(Path workspacePath, IOException exception) {
        LOGGER.debug("Scheduling deferred deletion for workspace {}", workspacePath, exception);

        Thread.ofPlatform()
                .daemon()
                .name("workspace-cleanup-" + workspacePath.getFileName())
                .start(() -> deleteWorkspaceDeferred(workspacePath));
    }

    private void deleteWorkspaceDeferred(Path workspacePath) {
        IOException lastException = null;

        for (int attempt = 1; attempt <= DEFERRED_DELETE_RETRY_ATTEMPTS; attempt++) {
            if (Files.notExists(workspacePath)) {
                return;
            }

            try {
                deleteWorkspacePath(workspacePath);
                return;
            } catch (AccessDeniedException exception) {
                lastException = exception;
                pauseBeforeDeferredRetry(workspacePath, attempt, exception);
            } catch (IOException exception) {
                LOGGER.warn("Deferred workspace cleanup failed for {}", workspacePath, exception);
                return;
            }
        }

        LOGGER.warn("Deferred workspace cleanup failed for {}", workspacePath, lastException);
    }

    private void pauseBeforeDeferredRetry(Path workspacePath, int attempt, AccessDeniedException exception) {
        try {
            Thread.sleep(DEFERRED_DELETE_DELAY_MILLIS * Math.min(attempt, 5));
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted while waiting to retry deferred cleanup for {}", workspacePath, exception);
        }
    }

    private boolean isOlderThan(Path workspacePath, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(workspacePath).toInstant().isBefore(cutoff);
        } catch (IOException exception) {
            LOGGER.warn("Failed to read last modified time for workspace {}", workspacePath, exception);
            return false;
        }
    }

    public record Workspace(
            String id,
            Path path
    ) {
    }

    public record WorkspaceCleanupResult(
            int scannedCount,
            int deletedCount,
            int failedCount
    ) {
    }
}
