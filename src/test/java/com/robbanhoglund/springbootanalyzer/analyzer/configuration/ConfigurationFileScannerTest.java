package com.robbanhoglund.springbootanalyzer.analyzer.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationFileScannerTest {

    @TempDir Path tempDir;

    @Test
    void skipsOutsideSymlinkWithoutBlockingSafeConfigurationFiles() throws IOException {
        Path repositoryRoot = Files.createDirectories(tempDir.resolve("repository"));
        Files.writeString(repositoryRoot.resolve("application.yml"), "server:\n  port: 8080\n");
        Path outsideDirectory = Files.createDirectories(tempDir.resolve("outside-host"));
        Path outsideFile = outsideDirectory.resolve("application.properties");
        Files.writeString(outsideFile, "spring.datasource.password=host-secret\n");
        createDirectoryLinkOrSkip(repositoryRoot.resolve("config"), outsideDirectory);

        var candidates = new ConfigurationFileScanner().scan(repositoryRoot);

        assertThat(candidates)
                .extracting(ConfigurationFileScanner.ConfigurationCandidate::relativePath)
                .containsExactly("application.yml");
    }

    private static void createDirectoryLinkOrSkip(Path link, Path target) {
        if (System.getProperty("os.name").toLowerCase().contains("windows")) {
            createWindowsJunctionOrSkip(link, target);
            return;
        }
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.assumeTrue(
                    false, "Symbolic links are unavailable in this test environment: " + exception);
        }
    }

    private static void createWindowsJunctionOrSkip(Path link, Path target) {
        try {
            Process process =
                    new ProcessBuilder(
                                    "cmd.exe",
                                    "/c",
                                    "mklink",
                                    "/J",
                                    link.toString(),
                                    target.toString())
                            .redirectErrorStream(true)
                            .start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                Assumptions.assumeTrue(
                        false,
                        "Directory junctions are unavailable in this test environment: "
                                + new String(process.getInputStream().readAllBytes()));
            }
        } catch (IOException exception) {
            Assumptions.assumeTrue(
                    false,
                    "Directory junctions are unavailable in this test environment: " + exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
