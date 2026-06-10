package com.robbanhoglund.springbootanalyzer.analyzer.source;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An immutable snapshot of a repository's {@code src/main/java} source tree, parsed exactly once.
 *
 * <p>Historically every finding analyzer walked {@code src/main/java} and re-parsed every file with
 * its own {@link JavaParser}, so a single analysis parsed each {@code .java} file many times over.
 * The orchestrator now builds one {@code JavaSources} via {@link #from(Path)} and hands the same
 * instance to each analyzer, which iterates {@link #files()} instead of walking and parsing again.
 *
 * <p>All analyzers use the same parser configuration (Java 25 language level, UTF-8), so a single
 * shared parse is equivalent to the per-analyzer parses it replaces. Files that cannot be read are
 * skipped; files that fail to parse are retained with a {@code null} {@link JavaFile#compilationUnit()}
 * so that content-based heuristics still see them — mirroring the per-analyzer behaviour, which kept
 * raw-text checks working even when JavaParser could not produce an AST.
 */
public final class JavaSources {

    private static final Logger LOGGER = LoggerFactory.getLogger(JavaSources.class);

    /**
     * A single parsed Java source file.
     *
     * @param path the absolute path on disk
     * @param relativePath the repository-relative path with forward slashes, as used in findings
     * @param compilationUnit the parsed AST, or {@code null} if the file could not be parsed
     * @param content the raw file text (never null)
     */
    public record JavaFile(
            Path path, String relativePath, CompilationUnit compilationUnit, String content) {}

    private final Path repositoryRoot;
    private final List<JavaFile> files;

    private JavaSources(Path repositoryRoot, List<JavaFile> files) {
        this.repositoryRoot = repositoryRoot;
        this.files = files;
    }

    /** Returns the repository root the sources were read from. */
    public Path repositoryRoot() {
        return repositoryRoot;
    }

    /** Returns the parsed files in stable (path-sorted) order; never null. */
    public List<JavaFile> files() {
        return files;
    }

    /** Returns {@code true} when there are no Java source files (e.g. no {@code src/main/java}). */
    public boolean isEmpty() {
        return files.isEmpty();
    }

    /**
     * Walks {@code <repositoryRoot>/src/main/java}, reading and parsing every {@code .java} file
     * exactly once. Returns an empty instance when the source root does not exist.
     */
    public static JavaSources from(Path repositoryRoot) {
        Path sourceRoot = repositoryRoot.resolve("src/main/java");
        if (Files.notExists(sourceRoot)) {
            return new JavaSources(repositoryRoot, List.of());
        }
        JavaParser parser =
                new JavaParser(
                        new ParserConfiguration()
                                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25)
                                .setCharacterEncoding(StandardCharsets.UTF_8));
        List<JavaFile> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            for (Path path :
                    stream.filter(Files::isRegularFile)
                            .filter(candidate -> candidate.toString().endsWith(".java"))
                            .sorted(Comparator.naturalOrder())
                            .toList()) {
                try {
                    String content = Files.readString(path, StandardCharsets.UTF_8);
                    // Only expose a CompilationUnit when the parse fully succeeded. JavaParser's
                    // error recovery can return a partial AST for invalid input, but the
                    // per-analyzer code this replaces skipped files whose parse was not successful,
                    // so mirror that to keep findings identical.
                    var parseResult = parser.parse(content);
                    CompilationUnit compilationUnit =
                            parseResult.isSuccessful()
                                    ? parseResult.getResult().orElse(null)
                                    : null;
                    String relativePath =
                            repositoryRoot.relativize(path).toString().replace('\\', '/');
                    files.add(new JavaFile(path, relativePath, compilationUnit, content));
                } catch (IOException exception) {
                    // Skip an individual unreadable file rather than aborting the whole analysis.
                    LOGGER.debug("Failed to read Java source {}; skipping", path, exception);
                }
            }
        } catch (IOException exception) {
            LOGGER.warn(
                    "Failed to fully walk Java sources under {}; using partial results",
                    sourceRoot,
                    exception);
        }
        return new JavaSources(repositoryRoot, List.copyOf(files));
    }
}
