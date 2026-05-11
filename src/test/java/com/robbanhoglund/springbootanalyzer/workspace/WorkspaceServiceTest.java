package com.robbanhoglund.springbootanalyzer.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleExecutionMode;
import com.robbanhoglund.springbootanalyzer.config.AnalyzerProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceServiceTest {

    @TempDir Path tempDir;

    @Test
    void deletesWorkspaceRecursively() throws IOException {
        WorkspaceService workspaceService =
                new WorkspaceService(
                        new AnalyzerProperties(
                                tempDir,
                                true,
                                false,
                                new AnalyzerProperties.ScheduledWorkspaceCleanupProperties(
                                        true, Duration.ofDays(7), 4),
                                new AnalyzerProperties.GradleProperties(
                                        false,
                                        null,
                                        GradleExecutionMode.TOOLING_API,
                                        "9.5.0",
                                        tempDir.resolve("gradle-cache"),
                                        java.util.List.of(),
                                        null,
                                        null,
                                        true,
                                        java.util.List.of("https://plugins.gradle.org/m2/"),
                                        true,
                                        false,
                                        true,
                                        false,
                                        true,
                                        false,
                                        false,
                                        new AnalyzerProperties.SettingsPluginWorkaroundProperties(
                                                false, false, java.util.List.of(), 1),
                                        new AnalyzerProperties.PluginResolutionBridgeProperties(
                                                true,
                                                true,
                                                true,
                                                "Spring Boot Analyzer plugin cache",
                                                java.util.List.of(
                                                        "https://plugins.gradle.org/m2/",
                                                        "https://repo.maven.apache.org/maven2/"),
                                                Duration.ofSeconds(30),
                                                50,
                                                500,
                                                false,
                                                2),
                                        false,
                                        false,
                                        true,
                                        null,
                                        null,
                                        0,
                                        0)));
        Path workspacePath = tempDir.resolve("workspace-1");
        Files.createDirectories(workspacePath.resolve("repository/.git/objects"));
        Files.writeString(workspacePath.resolve("repository/.git/objects/pack.idx"), "data");

        workspaceService.deleteWorkspace(
                new WorkspaceService.Workspace("workspace-1", workspacePath));

        assertThat(Files.exists(workspacePath)).isFalse();
    }

    @Test
    void deletesOnlyWorkspacesOlderThanConfiguredAge() throws IOException {
        WorkspaceService workspaceService =
                new WorkspaceService(
                        new AnalyzerProperties(
                                tempDir,
                                true,
                                false,
                                new AnalyzerProperties.ScheduledWorkspaceCleanupProperties(
                                        true, Duration.ofDays(7), 4),
                                new AnalyzerProperties.GradleProperties(
                                        false,
                                        null,
                                        GradleExecutionMode.TOOLING_API,
                                        "9.5.0",
                                        tempDir.resolve("gradle-cache"),
                                        java.util.List.of(),
                                        null,
                                        null,
                                        true,
                                        java.util.List.of("https://plugins.gradle.org/m2/"),
                                        true,
                                        false,
                                        true,
                                        false,
                                        true,
                                        false,
                                        false,
                                        new AnalyzerProperties.SettingsPluginWorkaroundProperties(
                                                false, false, java.util.List.of(), 1),
                                        new AnalyzerProperties.PluginResolutionBridgeProperties(
                                                true,
                                                true,
                                                true,
                                                "Spring Boot Analyzer plugin cache",
                                                java.util.List.of(
                                                        "https://plugins.gradle.org/m2/",
                                                        "https://repo.maven.apache.org/maven2/"),
                                                Duration.ofSeconds(30),
                                                50,
                                                500,
                                                false,
                                                2),
                                        false,
                                        false,
                                        true,
                                        null,
                                        null,
                                        0,
                                        0)));
        Path staleWorkspace = Files.createDirectories(tempDir.resolve("workspace-old"));
        Files.writeString(staleWorkspace.resolve("repo.txt"), "old");
        Files.setLastModifiedTime(
                staleWorkspace,
                java.nio.file.attribute.FileTime.from(Instant.parse("2026-04-20T00:00:00Z")));

        Path freshWorkspace = Files.createDirectories(tempDir.resolve("workspace-new"));
        Files.writeString(freshWorkspace.resolve("repo.txt"), "new");
        Files.setLastModifiedTime(
                freshWorkspace,
                java.nio.file.attribute.FileTime.from(Instant.parse("2026-04-29T00:00:00Z")));

        WorkspaceService.WorkspaceCleanupResult result =
                workspaceService.deleteWorkspacesOlderThan(
                        Duration.ofDays(7), Instant.parse("2026-04-30T00:00:00Z"));

        assertThat(result.scannedCount()).isEqualTo(2);
        assertThat(result.deletedCount()).isEqualTo(1);
        assertThat(result.failedCount()).isZero();
        assertThat(Files.exists(staleWorkspace)).isFalse();
        assertThat(Files.exists(freshWorkspace)).isTrue();
    }
}
