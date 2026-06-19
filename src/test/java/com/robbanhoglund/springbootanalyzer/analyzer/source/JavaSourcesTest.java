package com.robbanhoglund.springbootanalyzer.analyzer.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaSourcesTest {

    @TempDir Path repoRoot;

    private void writeSource(String relativePath, String content) throws IOException {
        Path file = repoRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void isEmptyWhenNoSourceRoot() {
        JavaSources sources = JavaSources.from(repoRoot);
        assertThat(sources.isEmpty()).isTrue();
        assertThat(sources.files()).isEmpty();
        assertThat(sources.repositoryRoot()).isEqualTo(repoRoot);
    }

    @Test
    void parsesEachFileOnceExposingCompilationUnitAndContent() throws IOException {
        writeSource(
                "src/main/java/com/example/Foo.java",
                """
                package com.example;
                class Foo {}
                """);
        writeSource(
                "src/main/java/com/example/Bar.java",
                """
                package com.example;
                class Bar {}
                """);

        JavaSources sources = JavaSources.from(repoRoot);

        assertThat(sources.files()).hasSize(2);
        // Stable, path-sorted order: Bar before Foo.
        assertThat(sources.files())
                .extracting(JavaSources.JavaFile::relativePath)
                .containsExactly(
                        "src/main/java/com/example/Bar.java", "src/main/java/com/example/Foo.java");
        JavaSources.JavaFile bar = sources.files().get(0);
        assertThat(bar.compilationUnit()).isNotNull();
        assertThat(bar.compilationUnit().getType(0).getNameAsString()).isEqualTo("Bar");
        assertThat(bar.content()).contains("class Bar");
    }

    @Test
    void retainsUnparseableFilesWithNullCompilationUnitButKeepsContent() throws IOException {
        writeSource("src/main/java/com/example/Broken.java", "this is not valid java @@@");

        JavaSources sources = JavaSources.from(repoRoot);

        assertThat(sources.files()).hasSize(1);
        JavaSources.JavaFile broken = sources.files().get(0);
        assertThat(broken.compilationUnit()).isNull();
        assertThat(broken.content()).contains("not valid java");
    }
}
