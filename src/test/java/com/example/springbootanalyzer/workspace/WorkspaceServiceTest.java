package com.example.springbootanalyzer.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.springbootanalyzer.config.AnalyzerProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void deletesWorkspaceRecursively() throws IOException {
        WorkspaceService workspaceService = new WorkspaceService(new AnalyzerProperties(tempDir, true));
        Path workspacePath = tempDir.resolve("workspace-1");
        Files.createDirectories(workspacePath.resolve("repository/.git/objects"));
        Files.writeString(workspacePath.resolve("repository/.git/objects/pack.idx"), "data");

        workspaceService.deleteWorkspace(new WorkspaceService.Workspace("workspace-1", workspacePath));

        assertThat(Files.exists(workspacePath)).isFalse();
    }
}
