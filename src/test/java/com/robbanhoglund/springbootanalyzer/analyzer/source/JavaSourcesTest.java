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

    @Test
    void readsSourcesThatAreNotValidUtf8LikeJavaParserDoes() throws IOException {
        Path file = repoRoot.resolve("src/main/java/com/example/Legacy.java");
        Files.createDirectories(file.getParent());
        // Saved as ISO-8859-1: the byte for "\u00e4" is not valid UTF-8 on its own.
        Files.write(
                file,
                "package com.example;\n// H\u00e4mtar data\nclass Legacy {}\n"
                        .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));

        JavaSources sources = JavaSources.from(repoRoot);

        assertThat(sources.files())
                .singleElement()
                .satisfies(
                        source -> {
                            assertThat(source.compilationUnit()).isNotNull();
                            assertThat(source.content())
                                    .contains("class Legacy")
                                    .contains("\uFFFD");
                        });
    }

    @Test
    void keepsPositionsAndCommentsAfterReleasingTheTokenChain() throws IOException {
        writeSource(
                "src/main/java/com/example/Service.java",
                """
                package com.example;

                /** Handles orders. */
                class Service {
                    // the main entry point
                    void handle() {
                        // nothing yet
                        int count = 1;
                    }
                }
                """);

        var unit = JavaSources.from(repoRoot).files().get(0).compilationUnit();
        var method =
                unit.findFirst(com.github.javaparser.ast.body.MethodDeclaration.class)
                        .orElseThrow();
        var type =
                unit.findFirst(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                        .orElseThrow();

        assertThat(method.getBegin()).map(position -> position.line).contains(6);
        assertThat(method.getEnd()).map(position -> position.line).contains(9);
        assertThat(method.getName().getBegin()).map(position -> position.column).contains(10);
        assertThat(method.getComment())
                .map(comment -> comment.getContent().strip())
                .contains("the main entry point");
        assertThat(method.getBody().orElseThrow().getAllContainedComments()).hasSize(1);
        assertThat(type.getJavadocComment()).isPresent();
        assertThat(unit.getAllComments()).allMatch(comment -> comment.getRange().isPresent());
        // The token chain itself is gone: nothing may rely on it.
        assertThat(method.getTokenRange()).isEmpty();
    }
}
