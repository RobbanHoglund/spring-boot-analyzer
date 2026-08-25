package com.robbanhoglund.springbootanalyzer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robbanhoglund.springbootanalyzer.api.dto.SourceSnippetResponse;
import com.robbanhoglund.springbootanalyzer.git.GitHubLinkBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceSnippetServiceTest {

    @TempDir Path tempDir;

    private AnalysisSessionRegistry registry;
    private SourceSnippetService service;
    private Path repositoryRoot;

    @BeforeEach
    void setUp() throws IOException {
        registry = new AnalysisSessionRegistry();
        service = new SourceSnippetService(registry, new GitHubLinkBuilder());
        repositoryRoot = Files.createDirectories(tempDir.resolve("repository"));
        registry.register(
                new AnalysisSessionRegistry.AnalysisSession(
                        "analysis-1",
                        repositoryRoot,
                        "https://github.com/example/demo.git",
                        "main",
                        "abc123"));
    }

    @Test
    void loadsJavaSnippetAndBuildsGitHubUrl() throws IOException {
        Path file =
                Files.createDirectories(repositoryRoot.resolve("src/main/java/com/example/demo"))
                        .resolve("Demo.java");
        Files.writeString(
                file,
                """
                package com.example.demo;

                public class Demo {
                    void run() {
                        try {
                            work();
                        } catch (Exception e) {
                        }
                    }
                }
                """);

        SourceSnippetResponse response =
                service.loadSnippet(
                        "analysis-1", "src/main/java/com/example/demo/Demo.java", 5, 7, 1);

        assertThat(response.language()).isEqualTo("java");
        assertThat(response.githubUrl())
                .isEqualTo(
                        "https://github.com/example/demo/blob/abc123/src/main/java/com/example/demo/Demo.java#L5-L7");
        assertThat(response.lines()).anyMatch(line -> line.lineNumber() == 5 && line.highlight());
    }

    @Test
    void redactsSensitivePropertyValues() throws IOException {
        Path file =
                Files.createDirectories(repositoryRoot.resolve("src/main/resources"))
                        .resolve("application.properties");
        Files.writeString(
                file,
                """
                spring.datasource.password=mysecret
                openai.api-key=sk-test
                harmless.value=visible
                """);

        SourceSnippetResponse response =
                service.loadSnippet(
                        "analysis-1", "src/main/resources/application.properties", 1, 3, 0);

        assertThat(response.lines())
                .extracting(line -> line.text())
                .contains(
                        "spring.datasource.password=[redacted]",
                        "openai.api-key=[redacted]",
                        "harmless.value=visible");
    }

    @Test
    void loadsFileWithoutFakeHighlightWhenExactLineIsUnknown() throws IOException {
        Path file =
                Files.createDirectories(repositoryRoot.resolve("src/main/java/com/example/demo"))
                        .resolve("Demo.java");
        Files.writeString(
                file,
                """
                package com.example.demo;

                class Demo {
                    void run() {
                        System.out.println("hello");
                    }
                }
                """);

        SourceSnippetResponse response =
                service.loadSnippet(
                        "analysis-1", "src/main/java/com/example/demo/Demo.java", null, null, 6);

        assertThat(response.lines()).isNotEmpty();
        assertThat(response.highlightRanges()).isEmpty();
        assertThat(response.githubUrl())
                .isEqualTo(
                        "https://github.com/example/demo/blob/abc123/src/main/java/com/example/demo/Demo.java");
        assertThat(response.lines()).allMatch(line -> !line.highlight());
    }

    @Test
    void rejectsPathTraversal() {
        assertThatThrownBy(() -> service.loadSnippet("analysis-1", "../secrets.txt", 1, 1, 0))
                .isInstanceOf(InvalidSourceSnippetRequestException.class);
    }

    @Test
    void rejectsAbsolutePaths() {
        assertThatThrownBy(
                        () ->
                                service.loadSnippet(
                                        "analysis-1",
                                        repositoryRoot.resolve("secret.txt").toString(),
                                        1,
                                        1,
                                        0))
                .isInstanceOf(InvalidSourceSnippetRequestException.class);
    }

    @Test
    void rejectsGitInternalAccess() {
        assertThatThrownBy(() -> service.loadSnippet("analysis-1", ".git/config", 1, 1, 0))
                .isInstanceOf(InvalidSourceSnippetRequestException.class);
    }

    @Test
    void rejectsSymlinkThatResolvesOutsideRepository() throws IOException {
        Path outsideDirectory = Files.createDirectories(tempDir.resolve("outside-host"));
        Path outsideFile = outsideDirectory.resolve("host-secret.txt");
        Files.writeString(outsideFile, "host-secret-value");
        Path link = repositoryRoot.resolve("linked-host-directory");
        createDirectoryLinkOrSkip(link, outsideDirectory);

        assertThatThrownBy(
                        () ->
                                service.loadSnippet(
                                        "analysis-1",
                                        "linked-host-directory/host-secret.txt",
                                        null,
                                        null,
                                        0))
                .isInstanceOf(InvalidSourceSnippetRequestException.class);
    }

    @Test
    void internalSymlinkCannotBypassSensitiveFileRedactionWithDifferentExtension()
            throws IOException {
        Path target = repositoryRoot.resolve("application.properties");
        Files.writeString(target, "spring.datasource.password=repository-secret\n");
        createSymbolicLinkOrSkip(repositoryRoot.resolve("notes.txt"), target);

        SourceSnippetResponse response =
                service.loadSnippet("analysis-1", "notes.txt", null, null, 0);

        assertThat(response.lines())
                .extracting(line -> line.text())
                .containsExactly("spring.datasource.password=[redacted]");
    }

    @Test
    void rejectsUnknownAnalysisId() {
        assertThatThrownBy(
                        () ->
                                service.loadSnippet(
                                        "no-such-id", "src/main/java/Demo.java", null, null, 0))
                .isInstanceOf(SourceSnippetNotFoundException.class);
    }

    @Test
    void rejectsNonexistentFile() {
        assertThatThrownBy(
                        () ->
                                service.loadSnippet(
                                        "analysis-1", "src/main/java/Missing.java", null, null, 0))
                .isInstanceOf(SourceSnippetNotFoundException.class);
    }

    @Test
    void rejectsPartialLineRange() {
        assertThatThrownBy(
                        () ->
                                service.loadSnippet(
                                        "analysis-1", "src/main/java/Demo.java", 5, null, 0))
                .isInstanceOf(InvalidSourceSnippetRequestException.class);
    }

    @Test
    void rejectsInvalidLineRange() throws IOException {
        Path file =
                Files.createDirectories(repositoryRoot.resolve("src/main/java/com/example"))
                        .resolve("Demo.java");
        Files.writeString(file, "class Demo {}\n");

        assertThatThrownBy(
                        () ->
                                service.loadSnippet(
                                        "analysis-1",
                                        "src/main/java/com/example/Demo.java",
                                        10,
                                        5,
                                        0))
                .isInstanceOf(InvalidSourceSnippetRequestException.class);
    }

    @Test
    void rejectsStartLineBeyondFileLength() throws IOException {
        Path file =
                Files.createDirectories(repositoryRoot.resolve("src/main/java/com/example"))
                        .resolve("Short.java");
        Files.writeString(file, "class Short {}\n");

        assertThatThrownBy(
                        () ->
                                service.loadSnippet(
                                        "analysis-1",
                                        "src/main/java/com/example/Short.java",
                                        999,
                                        1000,
                                        0))
                .isInstanceOf(InvalidSourceSnippetRequestException.class);
    }

    @Test
    void rejectsExcessiveContext() {
        assertThatThrownBy(
                        () ->
                                service.loadSnippet(
                                        "analysis-1", "src/main/java/Demo.java", null, null, 999))
                .isInstanceOf(InvalidSourceSnippetRequestException.class);
    }

    @Test
    void detectsYamlLanguage() throws IOException {
        Path file =
                Files.createDirectories(repositoryRoot.resolve("src/main/resources"))
                        .resolve("application.yml");
        Files.writeString(file, "server:\n  port: 8080\n");

        SourceSnippetResponse response =
                service.loadSnippet(
                        "analysis-1", "src/main/resources/application.yml", null, null, 0);

        assertThat(response.language()).isEqualTo("yaml");
    }

    @Test
    void detectsPropertiesLanguage() throws IOException {
        Path file =
                Files.createDirectories(repositoryRoot.resolve("src/main/resources"))
                        .resolve("custom.properties");
        Files.writeString(file, "app.name=demo\napp.version=1.0\n");

        SourceSnippetResponse response =
                service.loadSnippet(
                        "analysis-1", "src/main/resources/custom.properties", null, null, 0);

        assertThat(response.language()).isEqualTo("properties");
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.assumeTrue(
                    false, "Symbolic links are unavailable in this test environment: " + exception);
        }
    }

    private static void createDirectoryLinkOrSkip(Path link, Path target) {
        if (!System.getProperty("os.name").toLowerCase().contains("windows")) {
            createSymbolicLinkOrSkip(link, target);
            return;
        }
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
