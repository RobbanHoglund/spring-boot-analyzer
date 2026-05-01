package com.example.springbootanalyzer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.springbootanalyzer.api.dto.SourceSnippetResponse;
import com.example.springbootanalyzer.git.GitHubLinkBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceSnippetServiceTest {

    @TempDir
    Path tempDir;

    private AnalysisSessionRegistry registry;
    private SourceSnippetService service;
    private Path repositoryRoot;

    @BeforeEach
    void setUp() throws IOException {
        registry = new AnalysisSessionRegistry();
        service = new SourceSnippetService(registry, new GitHubLinkBuilder());
        repositoryRoot = Files.createDirectories(tempDir.resolve("repository"));
        registry.register(new AnalysisSessionRegistry.AnalysisSession(
                "analysis-1",
                repositoryRoot,
                "https://github.com/example/demo.git",
                "main",
                "abc123"
        ));
    }

    @Test
    void loadsJavaSnippetAndBuildsGitHubUrl() throws IOException {
        Path file = Files.createDirectories(repositoryRoot.resolve("src/main/java/com/example/demo"))
                .resolve("Demo.java");
        Files.writeString(file, """
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

        SourceSnippetResponse response = service.loadSnippet(
                "analysis-1",
                "src/main/java/com/example/demo/Demo.java",
                5,
                7,
                1
        );

        assertThat(response.language()).isEqualTo("java");
        assertThat(response.githubUrl()).isEqualTo("https://github.com/example/demo/blob/abc123/src/main/java/com/example/demo/Demo.java#L5-L7");
        assertThat(response.lines()).anyMatch(line -> line.lineNumber() == 5 && line.highlight());
    }

    @Test
    void redactsSensitivePropertyValues() throws IOException {
        Path file = Files.createDirectories(repositoryRoot.resolve("src/main/resources"))
                .resolve("application.properties");
        Files.writeString(file, """
                spring.datasource.password=mysecret
                openai.api-key=sk-test
                harmless.value=visible
                """);

        SourceSnippetResponse response = service.loadSnippet(
                "analysis-1",
                "src/main/resources/application.properties",
                1,
                3,
                0
        );

        assertThat(response.lines()).extracting(line -> line.text())
                .contains("spring.datasource.password=[redacted]", "openai.api-key=[redacted]", "harmless.value=visible");
    }

    @Test
    void loadsFileWithoutFakeHighlightWhenExactLineIsUnknown() throws IOException {
        Path file = Files.createDirectories(repositoryRoot.resolve("src/main/java/com/example/demo"))
                .resolve("Demo.java");
        Files.writeString(file, """
                package com.example.demo;

                class Demo {
                    void run() {
                        System.out.println("hello");
                    }
                }
                """);

        SourceSnippetResponse response = service.loadSnippet(
                "analysis-1",
                "src/main/java/com/example/demo/Demo.java",
                null,
                null,
                6
        );

        assertThat(response.lines()).isNotEmpty();
        assertThat(response.highlightRanges()).isEmpty();
        assertThat(response.githubUrl()).isEqualTo("https://github.com/example/demo/blob/abc123/src/main/java/com/example/demo/Demo.java");
        assertThat(response.lines()).allMatch(line -> !line.highlight());
    }

    @Test
    void rejectsPathTraversal() {
        assertThatThrownBy(() -> service.loadSnippet("analysis-1", "../secrets.txt", 1, 1, 0))
                .isInstanceOf(InvalidSourceSnippetRequestException.class);
    }

    @Test
    void rejectsAbsolutePaths() {
        assertThatThrownBy(() -> service.loadSnippet("analysis-1", repositoryRoot.resolve("secret.txt").toString(), 1, 1, 0))
                .isInstanceOf(InvalidSourceSnippetRequestException.class);
    }

    @Test
    void rejectsGitInternalAccess() {
        assertThatThrownBy(() -> service.loadSnippet("analysis-1", ".git/config", 1, 1, 0))
                .isInstanceOf(InvalidSourceSnippetRequestException.class);
    }
}
