package com.robbanhoglund.springbootanalyzer.analyzer;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingConfidence;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingFactory;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingRules;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Detects transaction-related anti-patterns in {@code src/main/java} source files.
 *
 * <p>Rules covered:
 *
 * <ul>
 *   <li>{@link FindingRules#SPRING_ASYNC_TRANSACTIONAL} — a method annotated with both
 *       {@code @Async} and {@code @Transactional}; the transaction context is not propagated to
 *       the async thread.
 * </ul>
 *
 * <p>{@code @Transactional} on private methods and self-invocation are detected by {@link
 * StaticPracticeFindingAnalyzer} as {@link FindingRules#SPRING_TRANSACTIONAL_ON_PRIVATE_METHOD}
 * and {@link FindingRules#SPRING_TRANSACTIONAL_SELF_INVOCATION} respectively.
 */
@Component
public class TransactionPracticeFindingAnalyzer {

    private final JavaParser javaParser;

    public TransactionPracticeFindingAnalyzer() {
        this.javaParser =
                new JavaParser(
                        new ParserConfiguration()
                                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25)
                                .setCharacterEncoding(StandardCharsets.UTF_8));
    }

    /**
     * Analyzes all Java source files under {@code src/main/java} within the given repository root.
     *
     * @param repositoryRoot root directory of the locally checked-out repository
     * @return list of findings; never null
     */
    public List<Finding> analyze(Path repositoryRoot) {
        List<Finding> findings = new ArrayList<>();
        Path sourceRoot = repositoryRoot.resolve("src/main/java");
        if (Files.notExists(sourceRoot)) {
            return findings;
        }
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path sourceFile :
                    files.filter(Files::isRegularFile)
                            .filter(p -> p.toString().endsWith(".java"))
                            .sorted(Comparator.naturalOrder())
                            .toList()) {
                analyzeSourceFile(repositoryRoot, sourceFile, findings);
            }
        } catch (IOException e) {
            // Best-effort — skip unreadable files
        }
        return findings;
    }

    // ---------------------------------------------------------------------------
    // Per-file analysis
    // ---------------------------------------------------------------------------

    private void analyzeSourceFile(Path repositoryRoot, Path sourceFile, List<Finding> findings)
            throws IOException {
        var parseResult = javaParser.parse(sourceFile);
        if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
            return;
        }
        CompilationUnit cu = parseResult.getResult().orElseThrow();
        String relativePath = repositoryRoot.relativize(sourceFile).toString().replace('\\', '/');

        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            for (MethodDeclaration method : cls.getMethods()) {
                detectAsyncTransactional(cls, method, relativePath, findings);
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_ASYNC_TRANSACTIONAL
    // ---------------------------------------------------------------------------

    /**
     * Flags methods that are annotated with both {@code @Async} and {@code @Transactional}.
     * Spring's transaction context is bound to the calling thread via a {@code ThreadLocal} and is
     * not propagated to the new thread started by {@code @Async}. The {@code @Transactional}
     * annotation on the async method starts an entirely new, unrelated transaction on the worker
     * thread — completely separate from any outer transaction the caller may hold.
     */
    private void detectAsyncTransactional(
            ClassOrInterfaceDeclaration cls,
            MethodDeclaration method,
            String relativePath,
            List<Finding> findings) {
        if (!hasAnnotation(method, "Async")) {
            return;
        }
        if (!hasTransactionalAnnotation(method)) {
            return;
        }
        Integer line = method.getBegin().map(p -> p.line).orElse(null);
        String target = cls.getNameAsString() + "#" + method.getNameAsString();
        findings.add(
                FindingFactory.builder(
                                FindingRules.SPRING_ASYNC_TRANSACTIONAL, FindingConfidence.HIGH)
                        .shortMessage(
                                "Method "
                                        + target
                                        + " is annotated with both @Async and @Transactional —"
                                        + " the transaction context is not propagated to the new"
                                        + " thread.")
                        .whyBadPractice(
                                "@Async dispatches the method to a thread pool. Spring's"
                                    + " transaction context is bound to the calling thread via a"
                                    + " ThreadLocal and is not propagated to the new thread. The"
                                    + " @Transactional annotation on the async method starts a new,"
                                    + " unrelated transaction on the worker thread — completely"
                                    + " separate from any outer transaction the caller may have.")
                        .possibleImpact(
                                "Database operations inside the async method run in a separate"
                                    + " transaction that the caller cannot roll back. This silently"
                                    + " breaks transactional guarantees and can leave data in an"
                                    + " inconsistent state.")
                        .recommendation(
                                "Remove @Transactional from the @Async method and manage"
                                        + " transactionality inside the async method explicitly, or"
                                        + " delegate to a synchronous @Transactional helper method"
                                        + " within the async body.")
                        .evidence(
                                "Method "
                                        + target
                                        + " has both @Async and @Transactional annotations in "
                                        + relativePath
                                        + ".")
                        .source(relativePath, line)
                        .target(target)
                        .build());
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private static boolean hasTransactionalAnnotation(MethodDeclaration method) {
        return method.getAnnotations().stream()
                .anyMatch(
                        a -> {
                            String name = a.getNameAsString();
                            return "Transactional".equals(name) || name.endsWith(".Transactional");
                        });
    }

    private static boolean hasAnnotation(MethodDeclaration method, String name) {
        return method.getAnnotations().stream()
                .anyMatch(a -> name.equals(simpleName(a.getNameAsString())));
    }

    private static String simpleName(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }
}
