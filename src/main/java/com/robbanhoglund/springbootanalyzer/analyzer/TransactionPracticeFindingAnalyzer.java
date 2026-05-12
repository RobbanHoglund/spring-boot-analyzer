package com.robbanhoglund.springbootanalyzer.analyzer;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ThisExpr;
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
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Detects transaction-related anti-patterns in {@code src/main/java} source files.
 *
 * <p>Rules covered:
 *
 * <ul>
 *   <li>{@link FindingRules#SPRING_TRANSACTIONAL_ON_PRIVATE_METHOD} — {@code @Transactional} on a
 *       private method that Spring's proxy cannot intercept.
 *   <li>{@link FindingRules#SPRING_TRANSACTIONAL_SELF_INVOCATION} — a {@code @Transactional}
 *       method called directly from within the same class, bypassing the proxy.
 *   <li>{@link FindingRules#SPRING_ASYNC_TRANSACTIONAL} — a method annotated with both
 *       {@code @Async} and {@code @Transactional}; the transaction context is not propagated to
 *       the async thread.
 * </ul>
 */
@Component
public class TransactionPracticeFindingAnalyzer {

    private static final Set<String> TRANSACTIONAL_ANNOTATIONS =
            Set.of("Transactional", "javax.transaction.Transactional");

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
            analyzeClass(cls, relativePath, findings);
        }
    }

    private void analyzeClass(
            ClassOrInterfaceDeclaration cls, String relativePath, List<Finding> findings) {
        // Collect all @Transactional method names for self-invocation detection
        Set<String> transactionalMethodNames =
                cls.getMethods().stream()
                        .filter(m -> hasTransactionalAnnotation(m))
                        .map(MethodDeclaration::getNameAsString)
                        .collect(Collectors.toSet());

        for (MethodDeclaration method : cls.getMethods()) {
            detectTransactionalOnPrivateMethod(cls, method, relativePath, findings);
            detectAsyncTransactional(cls, method, relativePath, findings);
            detectTransactionalSelfInvocation(
                    cls, method, transactionalMethodNames, relativePath, findings);
        }
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_TRANSACTIONAL_ON_PRIVATE_METHOD
    // ---------------------------------------------------------------------------

    private void detectTransactionalOnPrivateMethod(
            ClassOrInterfaceDeclaration cls,
            MethodDeclaration method,
            String relativePath,
            List<Finding> findings) {
        if (!method.isPrivate()) {
            return;
        }
        if (!hasTransactionalAnnotation(method)) {
            return;
        }
        Integer line = method.getBegin().map(p -> p.line).orElse(null);
        String target = cls.getNameAsString() + "#" + method.getNameAsString();
        findings.add(
                FindingFactory.builder(
                                FindingRules.SPRING_TRANSACTIONAL_ON_PRIVATE_METHOD,
                                FindingConfidence.HIGH)
                        .shortMessage(
                                "@Transactional on private method "
                                        + target
                                        + " — Spring's proxy cannot intercept private methods.")
                        .whyBadPractice(
                                "Spring applies transaction management through a proxy (JDK dynamic"
                                    + " proxy or CGLIB) that wraps the bean. Proxies can only"
                                    + " intercept calls made through the proxy reference, and they"
                                    + " cannot override private methods. The @Transactional"
                                    + " annotation is silently ignored.")
                        .possibleImpact(
                                "No transaction is started or committed; any database operations in"
                                    + " the method run outside a transaction boundary, risking data"
                                    + " inconsistency or unexpected rollback behavior.")
                        .recommendation(
                                "Change the method visibility to package-private, protected, or"
                                    + " public. If the method must remain private, extract the"
                                    + " transactional logic to a public wrapper method or use"
                                    + " AspectJ compile-time weaving instead of proxy-based AOP.")
                        .limitations(
                                "If the project uses AspectJ weaving instead of Spring proxies,"
                                        + " private methods can be intercepted.")
                        .evidence(
                                "@Transactional found on private method "
                                        + method.getNameAsString()
                                        + " in "
                                        + relativePath
                                        + ".")
                        .source(relativePath, line)
                        .target(target)
                        .build());
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_TRANSACTIONAL_SELF_INVOCATION
    // ---------------------------------------------------------------------------

    private void detectTransactionalSelfInvocation(
            ClassOrInterfaceDeclaration cls,
            MethodDeclaration method,
            Set<String> transactionalMethodNames,
            String relativePath,
            List<Finding> findings) {
        if (transactionalMethodNames.isEmpty()) {
            return;
        }
        method.findAll(MethodCallExpr.class)
                .forEach(
                        call -> {
                            if (!transactionalMethodNames.contains(call.getNameAsString())) {
                                return;
                            }
                            var scope = call.getScope().orElse(null);
                            boolean isSelfCall = scope == null || scope instanceof ThisExpr;
                            if (!isSelfCall) {
                                return;
                            }
                            // Don't emit on the method's own recursive self-call
                            if (method.getNameAsString().equals(call.getNameAsString())) {
                                return;
                            }
                            Integer line = call.getBegin().map(p -> p.line).orElse(null);
                            String callerTarget =
                                    cls.getNameAsString() + "#" + method.getNameAsString();
                            findings.add(
                                    FindingFactory.builder(
                                                    FindingRules
                                                            .SPRING_TRANSACTIONAL_SELF_INVOCATION,
                                                    FindingConfidence.MEDIUM)
                                            .shortMessage(
                                                    "@Transactional method '"
                                                            + call.getNameAsString()
                                                            + "' called via self-invocation in "
                                                            + callerTarget
                                                            + " — transaction will be bypassed.")
                                            .whyBadPractice(
                                                    "Spring applies transaction management through"
                                                        + " a proxy wrapper around the bean. When a"
                                                        + " method in the same class calls another"
                                                        + " method directly, the call bypasses the"
                                                        + " proxy and the @Transactional annotation"
                                                        + " is not intercepted.")
                                            .possibleImpact(
                                                    "The called method executes without the"
                                                        + " expected transaction boundary, risking"
                                                        + " data inconsistency, missing rollback on"
                                                        + " failure, or unexpected behavior when"
                                                        + " propagation settings are relied upon.")
                                            .recommendation(
                                                    "Inject the bean into itself (self-injection"
                                                        + " via @Autowired or ApplicationContext)"
                                                        + " and call the method through the"
                                                        + " injected reference, or extract the"
                                                        + " transactional method into a separate"
                                                        + " Spring-managed bean.")
                                            .evidence(
                                                    "Method "
                                                            + method.getNameAsString()
                                                            + " calls @Transactional method "
                                                            + call.getNameAsString()
                                                            + " directly in "
                                                            + relativePath
                                                            + ".")
                                            .source(relativePath, line)
                                            .target(callerTarget)
                                            .build());
                        });
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_ASYNC_TRANSACTIONAL
    // ---------------------------------------------------------------------------

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
