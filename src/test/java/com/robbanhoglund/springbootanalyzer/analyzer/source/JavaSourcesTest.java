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

    @Test
    void pathologicallyNestedExpressionDoesNotAbortTheScan() throws IOException {
        // JavaParser uses recursive descent, so a deeply nested expression throws
        // StackOverflowError rather than returning a failed ParseResult. It must be contained
        // to the offending file: the rest of the source tree still has to be parsed.
        writeSource(
                "src/main/java/com/example/Deep.java",
                "package com.example;\nclass Deep {\n  int v = "
                        + "(".repeat(2000)
                        + "1"
                        + ")".repeat(2000)
                        + ";\n}\n");
        writeSource(
                "src/main/java/com/example/Healthy.java",
                """
                package com.example;
                class Healthy {}
                """);

        JavaSources sources = JavaSources.from(repoRoot);

        assertThat(sources.files())
                .extracting(JavaSources.JavaFile::relativePath)
                .contains("src/main/java/com/example/Healthy.java");
        assertThat(sources.files())
                .filteredOn(file -> file.relativePath().endsWith("Healthy.java"))
                .singleElement()
                .satisfies(file -> assertThat(file.compilationUnit()).isNotNull());
    }
}
