package com.robbanhoglund.springbootanalyzer.analyzer;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingConfidence;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingFactory;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingRules;
import com.robbanhoglund.springbootanalyzer.analyzer.source.JavaSources;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
 *   <li>{@link FindingRules#SPRING_TRANSACTIONAL_SYNCHRONIZED} — a {@code synchronized}
 *       {@code @Transactional} method, whose lock is released before the commit.
 *   <li>{@link FindingRules#SPRING_TX_EVENT_LISTENER_NO_TRANSACTION} — an event with a
 *       {@code @TransactionalEventListener} published from a method without a transaction.
 * </ul>
 *
 * <p>{@code @Transactional} on private methods and self-invocation are detected by {@link
 * StaticPracticeFindingAnalyzer} as {@link FindingRules#SPRING_TRANSACTIONAL_ON_PRIVATE_METHOD}
 * and {@link FindingRules#SPRING_TRANSACTIONAL_SELF_INVOCATION} respectively.
 */
@Component
public class TransactionPracticeFindingAnalyzer {

    /**
     * Analyzes all Java source files under {@code src/main/java} within the given repository root.
     *
     * @param repositoryRoot root directory of the locally checked-out repository
     * @return list of findings; never null
     */
    public List<Finding> analyze(Path repositoryRoot) {
        return analyze(JavaSources.from(repositoryRoot));
    }

    /**
     * Analyzes the {@code src/main/java} sources parsed once and shared across the pipeline.
     *
     * @param sources the source tree parsed once for this analysis
     * @return list of findings; never null
     */
    public List<Finding> analyze(JavaSources sources) {
        List<Finding> findings = new ArrayList<>();
        Set<String> transactionalEvents = transactionalEventTypes(sources);
        Set<String> calledInTransactions =
                transactionalEvents.isEmpty() ? Set.of() : methodsCalledFromTransactions(sources);
        for (JavaSources.JavaFile file : sources.files()) {
            if (file.compilationUnit() == null) {
                continue;
            }
            analyzeSourceFile(file.compilationUnit(), file.relativePath(), findings);
            if (!transactionalEvents.isEmpty()) {
                detectTransactionalEventPublishedWithoutTransaction(
                        file.compilationUnit(),
                        file.relativePath(),
                        transactionalEvents,
                        calledInTransactions,
                        findings);
            }
        }
        return findings;
    }

    // ---------------------------------------------------------------------------
    // Per-file analysis
    // ---------------------------------------------------------------------------

    private void analyzeSourceFile(
            CompilationUnit cu, String relativePath, List<Finding> findings) {
        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            for (MethodDeclaration method : cls.getMethods()) {
                detectAsyncTransactional(cls, method, relativePath, findings);
                detectTransactionalOnPostConstruct(cls, method, relativePath, findings);
                detectTransactionalSynchronized(cls, method, relativePath, findings);
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
                                "Database operations inside the async method run in a new,"
                                    + " independent transaction that the caller cannot roll back"
                                    + " and whose failure the caller never observes. Work the"
                                    + " caller assumed to be atomic with its own transaction is"
                                    + " not.")
                        .recommendation(
                                "If an independent transaction on the async thread is intended,"
                                    + " keep the combination but make the decoupling explicit"
                                    + " (naming, documentation, failure handling). If the work must"
                                    + " share the caller's transaction, run it synchronously before"
                                    + " dispatching the async part. If delegating to a"
                                    + " @Transactional helper, place the helper in a separate bean"
                                    + " so the proxy is not bypassed by self-invocation.")
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
    // Rule: SPRING_TRANSACTIONAL_ON_POSTCONSTRUCT
    // ---------------------------------------------------------------------------

    /**
     * Flags methods annotated with both {@code @PostConstruct} and {@code @Transactional}. Spring
     * invokes {@code @PostConstruct} callbacks while the bean is still being initialized, before the
     * transactional proxy that would start a transaction is in place. The {@code @Transactional}
     * annotation therefore has no effect during initialization — the callback runs without a
     * transaction.
     */
    private void detectTransactionalOnPostConstruct(
            ClassOrInterfaceDeclaration cls,
            MethodDeclaration method,
            String relativePath,
            List<Finding> findings) {
        if (!hasAnnotation(method, "PostConstruct")) {
            return;
        }
        if (!hasTransactionalAnnotation(method)) {
            return;
        }
        Integer line = method.getBegin().map(p -> p.line).orElse(null);
        String target = cls.getNameAsString() + "#" + method.getNameAsString();
        findings.add(
                FindingFactory.builder(
                                FindingRules.SPRING_TRANSACTIONAL_ON_POSTCONSTRUCT,
                                FindingConfidence.HIGH)
                        .shortMessage(
                                "Method "
                                        + target
                                        + " is annotated with both @PostConstruct and"
                                        + " @Transactional — no transaction is started during"
                                        + " initialization.")
                        .whyBadPractice(
                                "Spring runs @PostConstruct callbacks as part of bean"
                                    + " initialization, before the AOP proxy that applies"
                                    + " @Transactional wraps the bean. The annotation is processed"
                                    + " for normal (post-initialization) calls but is not in effect"
                                    + " while the @PostConstruct method itself runs, so the work"
                                    + " executes with no active transaction.")
                        .possibleImpact(
                                "Persistence operations in the callback run without transactional"
                                    + " guarantees: no rollback on failure, and reads/writes may"
                                    + " use auto-commit or fail with 'no active transaction'"
                                    + " depending on the setup. The developer's assumption of"
                                    + " atomicity is silently false.")
                        .recommendation(
                                "Move the transactional work out of @PostConstruct. Delegate to a"
                                    + " separate @Transactional bean method invoked through the"
                                    + " proxy, or run initialization on an"
                                    + " ApplicationReadyEvent/ContextRefreshedEvent listener where"
                                    + " the proxy is fully in place.")
                        .evidence(
                                "Method "
                                        + target
                                        + " has both @PostConstruct and @Transactional annotations"
                                        + " in "
                                        + relativePath
                                        + ".")
                        .source(relativePath, line)
                        .target(target)
                        .build());
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_TRANSACTIONAL_SYNCHRONIZED
    // ---------------------------------------------------------------------------

    private void detectTransactionalSynchronized(
            ClassOrInterfaceDeclaration cls,
            MethodDeclaration method,
            String relativePath,
            List<Finding> findings) {
        if (!method.isSynchronized()
                || method.isPrivate()
                || method.isStatic()
                || !(hasTransactionalAnnotation(method)
                        || cls.getAnnotations().stream()
                                .anyMatch(
                                        a ->
                                                "Transactional"
                                                        .equals(
                                                                simpleName(
                                                                        a.getNameAsString()))))) {
            return;
        }
        String target = cls.getNameAsString() + "#" + method.getNameAsString();
        findings.add(
                FindingFactory.builder(
                                FindingRules.SPRING_TRANSACTIONAL_SYNCHRONIZED,
                                FindingConfidence.HIGH)
                        .shortMessage(
                                target
                                        + " is synchronized and @Transactional — the lock is"
                                        + " released before the transaction commits.")
                        .whyBadPractice(
                                "The transaction proxy wraps the method: it begins the transaction"
                                    + " before the synchronized method is entered and commits after"
                                    + " it returns. Another thread can acquire the lock in between"
                                    + " and read the state the first thread has not committed yet.")
                        .possibleImpact(
                                "Concurrent calls still interleave their read-modify-write cycles,"
                                        + " so updates are lost despite the lock.")
                        .recommendation(
                                "Serialize at the database instead — optimistic locking with"
                                        + " @Version, a pessimistic lock (SELECT ... FOR UPDATE),"
                                        + " or an atomic UPDATE statement — or take the lock in a"
                                        + " caller that wraps the whole transaction.")
                        .evidence(
                                "Method "
                                        + target
                                        + " is declared synchronized and is transactional.")
                        .source(relativePath, method.getBegin().map(p -> p.line).orElse(null))
                        .target(target)
                        .build());
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_TX_EVENT_LISTENER_NO_TRANSACTION
    // ---------------------------------------------------------------------------

    /**
     * Event types handled by a @TransactionalEventListener that does not opt into
     * {@code fallbackExecution}: publishing them without a transaction skips the listener.
     */
    private Set<String> transactionalEventTypes(JavaSources sources) {
        Set<String> types = new LinkedHashSet<>();
        for (JavaSources.JavaFile file : sources.files()) {
            if (file.compilationUnit() == null
                    || !file.content().contains("TransactionalEventListener")) {
                continue;
            }
            for (MethodDeclaration method :
                    file.compilationUnit().findAll(MethodDeclaration.class)) {
                AnnotationExpr listener =
                        method.getAnnotationByName("TransactionalEventListener").orElse(null);
                if (listener == null
                        || listener.toString()
                                .replaceAll("\\s", "")
                                .contains("fallbackExecution=true")) {
                    continue;
                }
                listener.findAll(ClassExpr.class)
                        .forEach(
                                classExpr -> types.add(simpleName(classExpr.getType().asString())));
                if (method.getParameters().size() == 1) {
                    types.add(rawType(method.getParameter(0).getType().asString()));
                }
            }
        }
        types.remove("Object");
        types.remove("ApplicationEvent");
        return types;
    }

    /**
     * Names of methods invoked on another object from transactional code anywhere in the
     * project. A publisher called like that may run inside the caller's transaction.
     */
    private Set<String> methodsCalledFromTransactions(JavaSources sources) {
        Set<String> names = new LinkedHashSet<>();
        for (JavaSources.JavaFile file : sources.files()) {
            if (file.compilationUnit() == null) {
                continue;
            }
            for (ClassOrInterfaceDeclaration cls :
                    file.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
                boolean classTransactional =
                        cls.getAnnotations().stream()
                                .anyMatch(
                                        a ->
                                                "Transactional"
                                                        .equals(simpleName(a.getNameAsString())));
                for (MethodDeclaration method : cls.getMethods()) {
                    if (!classTransactional && !hasTransactionalAnnotation(method)) {
                        continue;
                    }
                    method.findAll(MethodCallExpr.class).stream()
                            .filter(
                                    call ->
                                            call.getScope().isPresent()
                                                    && !call.getScope().get().isThisExpr())
                            .forEach(call -> names.add(call.getNameAsString()));
                }
            }
        }
        return names;
    }

    private void detectTransactionalEventPublishedWithoutTransaction(
            CompilationUnit cu,
            String relativePath,
            Set<String> eventTypes,
            Set<String> calledInTransactions,
            List<Finding> findings) {
        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            boolean classTransactional =
                    cls.getAnnotations().stream()
                            .anyMatch(a -> "Transactional".equals(simpleName(a.getNameAsString())));
            if (classTransactional) {
                continue;
            }
            Set<String> transactionalCallers = new LinkedHashSet<>();
            for (MethodDeclaration method : cls.getMethods()) {
                if (hasTransactionalAnnotation(method)) {
                    method.findAll(MethodCallExpr.class).stream()
                            .filter(
                                    call ->
                                            call.getScope().isEmpty()
                                                    || call.getScope().get().isThisExpr())
                            .forEach(call -> transactionalCallers.add(call.getNameAsString()));
                }
            }
            for (MethodDeclaration method : cls.getMethods()) {
                // A helper called from a @Transactional method of the same class runs inside that
                // transaction, and one that transactional code elsewhere calls may as well.
                if (hasTransactionalAnnotation(method)
                        || transactionalCallers.contains(method.getNameAsString())
                        || calledInTransactions.contains(method.getNameAsString())) {
                    continue;
                }
                for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                    if (!"publishEvent".equals(call.getNameAsString())
                            || call.getArguments().size() != 1) {
                        continue;
                    }
                    String eventType = eventType(call.getArgument(0), cls, method);
                    if (eventType == null || !eventTypes.contains(eventType)) {
                        continue;
                    }
                    String target = cls.getNameAsString() + "#" + method.getNameAsString();
                    findings.add(
                            FindingFactory.builder(
                                            FindingRules.SPRING_TX_EVENT_LISTENER_NO_TRANSACTION,
                                            FindingConfidence.MEDIUM)
                                    .shortMessage(
                                            target
                                                    + " publishes "
                                                    + eventType
                                                    + " without a transaction — its"
                                                    + " @TransactionalEventListener is skipped.")
                                    .whyBadPractice(
                                            "A @TransactionalEventListener runs in a phase of the"
                                                + " publishing transaction (after commit by"
                                                + " default). When no transaction is active, Spring"
                                                + " skips it and only logs \"No transaction is"
                                                + " active - skipping\" at debug level, unless the"
                                                + " listener sets fallbackExecution = true.")
                                    .possibleImpact(
                                            "The reaction to the event (notification, projection"
                                                    + " update, outbox write) silently never"
                                                    + " happens.")
                                    .recommendation(
                                            "Publish the event inside a @Transactional method, or"
                                                + " set fallbackExecution = true on the listener if"
                                                + " it should also run without a transaction.")
                                    .evidence(
                                            "publishEvent(...) of "
                                                    + eventType
                                                    + " in "
                                                    + target
                                                    + ", which is not @Transactional; a"
                                                    + " @TransactionalEventListener handles "
                                                    + eventType
                                                    + ".")
                                    .limitations(
                                            "Methods that transactional code elsewhere calls by"
                                                    + " name are not reported; a transaction opened"
                                                    + " by a caller the analyzer cannot see is"
                                                    + " missed.")
                                    .source(
                                            relativePath,
                                            call.getBegin().map(p -> p.line).orElse(null))
                                    .target(target)
                                    .build());
                }
            }
        }
    }

    private static String eventType(
            Expression argument, ClassOrInterfaceDeclaration cls, MethodDeclaration method) {
        if (argument instanceof ObjectCreationExpr creation) {
            return simpleName(creation.getType().getNameAsString());
        }
        if (argument instanceof NameExpr name) {
            String variable = name.getNameAsString();
            for (Parameter parameter : method.getParameters()) {
                if (parameter.getNameAsString().equals(variable)) {
                    return rawType(parameter.getType().asString());
                }
            }
            for (VariableDeclarator declarator : method.findAll(VariableDeclarator.class)) {
                if (declarator.getNameAsString().equals(variable)) {
                    return rawType(declarator.getTypeAsString());
                }
            }
            for (VariableDeclarator declarator : cls.findAll(VariableDeclarator.class)) {
                if (declarator.getNameAsString().equals(variable)) {
                    return rawType(declarator.getTypeAsString());
                }
            }
        }
        return null;
    }

    private static String rawType(String type) {
        int generic = type.indexOf('<');
        return simpleName(generic >= 0 ? type.substring(0, generic) : type);
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
