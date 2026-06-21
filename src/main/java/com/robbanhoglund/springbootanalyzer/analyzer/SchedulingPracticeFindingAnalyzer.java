package com.robbanhoglund.springbootanalyzer.analyzer;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingConfidence;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingFactory;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingRules;
import com.robbanhoglund.springbootanalyzer.analyzer.source.JavaSources;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Detects scheduling and async anti-patterns in {@code src/main/java} source files.
 *
 * <p>Rules covered:
 *
 * <ul>
 *   <li>{@link FindingRules#SPRING_SCHEDULED_WITHOUT_ENABLE_SCHEDULING} — {@code @Scheduled} is used
 *       but no {@code @EnableScheduling} is present anywhere, so the jobs never run.
 *   <li>{@link FindingRules#SPRING_ASYNC_WITHOUT_ENABLE_ASYNC} — {@code @Async} is used but no
 *       {@code @EnableAsync} is present anywhere, so the methods run synchronously.
 *   <li>{@link FindingRules#SPRING_SCHEDULED_METHOD_INVALID_SIGNATURE} — a {@code @Scheduled} method
 *       declares parameters; Spring requires no-arg methods and fails at startup.
 *   <li>{@link FindingRules#SPRING_ASYNC_SELF_INVOCATION} — an {@code @Async} method is called via
 *       self-invocation, bypassing the proxy so it runs synchronously.
 * </ul>
 *
 * <p>{@code @Async} on a private method is detected separately by {@link
 * StaticPracticeFindingAnalyzer} as {@link FindingRules#SPRING_ASYNC_PROXY_BYPASS}.
 */
@Component
public class SchedulingPracticeFindingAnalyzer {

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

        // Cross-file enablement signals for the "@Scheduled/@Async used but not enabled" rules:
        // whether any class enables the feature, and the first place the feature is actually used.
        boolean enableScheduling = false;
        boolean enableAsync = false;
        String scheduledUsagePath = null;
        Integer scheduledUsageLine = null;
        String scheduledUsageTarget = null;
        String asyncUsagePath = null;
        Integer asyncUsageLine = null;
        String asyncUsageTarget = null;

        for (JavaSources.JavaFile file : sources.files()) {
            CompilationUnit cu = file.compilationUnit();
            if (cu == null) {
                continue;
            }
            for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (hasAnnotation(cls.getAnnotations(), "EnableScheduling")) {
                    enableScheduling = true;
                }
                if (hasAnnotation(cls.getAnnotations(), "EnableAsync")) {
                    enableAsync = true;
                }

                // @Async method names in this class drive self-invocation detection. Private
                // @Async methods are excluded (covered by SPRING_ASYNC_PROXY_BYPASS).
                Set<String> asyncMethodNames =
                        cls.getMethods().stream()
                                .filter(
                                        m ->
                                                !m.isPrivate()
                                                        && hasAnnotation(
                                                                m.getAnnotations(), "Async"))
                                .map(MethodDeclaration::getNameAsString)
                                .collect(Collectors.toSet());

                for (MethodDeclaration method : cls.getMethods()) {
                    if (hasAnnotation(method.getAnnotations(), "Scheduled")) {
                        if (scheduledUsageTarget == null) {
                            scheduledUsageTarget =
                                    cls.getNameAsString() + "#" + method.getNameAsString();
                            scheduledUsageLine = method.getBegin().map(p -> p.line).orElse(null);
                            scheduledUsagePath = file.relativePath();
                        }
                        detectScheduledInvalidSignature(cls, method, file.relativePath(), findings);
                    }
                    if (asyncUsageTarget == null
                            && !method.isPrivate()
                            && hasAnnotation(method.getAnnotations(), "Async")) {
                        asyncUsageTarget = cls.getNameAsString() + "#" + method.getNameAsString();
                        asyncUsageLine = method.getBegin().map(p -> p.line).orElse(null);
                        asyncUsagePath = file.relativePath();
                    }
                    detectAsyncSelfInvocation(
                            cls, method, asyncMethodNames, file.relativePath(), findings);
                }
            }
        }

        if (scheduledUsageTarget != null && !enableScheduling) {
            addScheduledWithoutEnableSchedulingFinding(
                    scheduledUsagePath, scheduledUsageLine, scheduledUsageTarget, findings);
        }
        if (asyncUsageTarget != null && !enableAsync) {
            addAsyncWithoutEnableAsyncFinding(
                    asyncUsagePath, asyncUsageLine, asyncUsageTarget, findings);
        }
        return findings;
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_SCHEDULED_WITHOUT_ENABLE_SCHEDULING
    // ---------------------------------------------------------------------------

    private void addScheduledWithoutEnableSchedulingFinding(
            String relativePath, Integer line, String target, List<Finding> findings) {
        findings.add(
                FindingFactory.builder(
                                FindingRules.SPRING_SCHEDULED_WITHOUT_ENABLE_SCHEDULING,
                                FindingConfidence.MEDIUM)
                        .shortMessage(
                                "@Scheduled is used (first seen on "
                                        + target
                                        + ") but no @EnableScheduling was found — the jobs never"
                                        + " run.")
                        .whyBadPractice(
                                "Spring Boot does not enable scheduling automatically. @Scheduled"
                                    + " methods are only registered with a TaskScheduler when a"
                                    + " configuration class is annotated with @EnableScheduling."
                                    + " Without it the annotations are parsed but no trigger is"
                                    + " ever registered.")
                        .possibleImpact(
                                "Background jobs (cleanups, polling, refresh tasks, health pings)"
                                    + " silently never execute. The application starts cleanly, so"
                                    + " the omission is easy to miss until the missing work causes"
                                    + " a downstream incident.")
                        .recommendation(
                                "Add @EnableScheduling to a @Configuration class (or the main"
                                        + " @SpringBootApplication class) and confirm the scheduled"
                                        + " methods run as expected.")
                        .evidence(
                                "@Scheduled is used (first seen on "
                                        + target
                                        + " in "
                                        + relativePath
                                        + ") but no @EnableScheduling annotation was found in"
                                        + " src/main/java.")
                        .limitations(
                                "Static analysis only sees src/main/java. If @EnableScheduling is"
                                    + " applied through an imported library configuration, a"
                                    + " meta-annotation, or XML, this finding is a false positive.")
                        .source(relativePath, line)
                        .target(target)
                        .build());
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_ASYNC_WITHOUT_ENABLE_ASYNC
    // ---------------------------------------------------------------------------

    private void addAsyncWithoutEnableAsyncFinding(
            String relativePath, Integer line, String target, List<Finding> findings) {
        findings.add(
                FindingFactory.builder(
                                FindingRules.SPRING_ASYNC_WITHOUT_ENABLE_ASYNC,
                                FindingConfidence.MEDIUM)
                        .shortMessage(
                                "@Async is used (first seen on "
                                        + target
                                        + ") but no @EnableAsync was found — the methods run"
                                        + " synchronously.")
                        .whyBadPractice(
                                "Spring Boot does not enable async processing automatically. @Async"
                                    + " only takes effect when a configuration class is annotated"
                                    + " with @EnableAsync. Without it the proxy that dispatches the"
                                    + " call to an executor is never created, so the method runs"
                                    + " inline on the caller's thread.")
                        .possibleImpact(
                                "Work the developer expected to run in the background blocks the"
                                    + " calling thread (often an HTTP request thread), increasing"
                                    + " latency and reducing throughput. Fire-and-forget semantics"
                                    + " are silently lost.")
                        .recommendation(
                                "Add @EnableAsync to a @Configuration class and configure a"
                                    + " ThreadPoolTaskExecutor so async methods are dispatched off"
                                    + " the caller's thread.")
                        .evidence(
                                "@Async is used (first seen on "
                                        + target
                                        + " in "
                                        + relativePath
                                        + ") but no @EnableAsync annotation was found in"
                                        + " src/main/java.")
                        .limitations(
                                "Static analysis only sees src/main/java. If @EnableAsync is"
                                    + " applied through an imported library configuration, a"
                                    + " meta-annotation, or XML, this finding is a false positive.")
                        .source(relativePath, line)
                        .target(target)
                        .build());
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_SCHEDULED_METHOD_INVALID_SIGNATURE
    // ---------------------------------------------------------------------------

    private void detectScheduledInvalidSignature(
            ClassOrInterfaceDeclaration cls,
            MethodDeclaration method,
            String relativePath,
            List<Finding> findings) {
        if (method.getParameters().isEmpty()) {
            return;
        }
        Integer line = method.getBegin().map(p -> p.line).orElse(null);
        String target = cls.getNameAsString() + "#" + method.getNameAsString();
        findings.add(
                FindingFactory.builder(
                                FindingRules.SPRING_SCHEDULED_METHOD_INVALID_SIGNATURE,
                                FindingConfidence.HIGH)
                        .shortMessage(
                                "@Scheduled method "
                                        + target
                                        + " declares parameters — Spring requires no-arg scheduled"
                                        + " methods and fails at startup.")
                        .whyBadPractice(
                                "Spring's ScheduledAnnotationBeanPostProcessor can only invoke a"
                                    + " scheduled method with no arguments, because it has no"
                                    + " source of values to pass. When it registers a @Scheduled"
                                    + " method that declares parameters it throws"
                                    + " IllegalStateException (\"Only no-arg methods may be"
                                    + " annotated with @Scheduled\").")
                        .possibleImpact(
                                "The application context fails to start: the bean post-processor"
                                        + " throws while wiring the scheduled task, so the whole"
                                        + " application is down rather than just the one job.")
                        .recommendation(
                                "Remove the parameters from the @Scheduled method. If the method"
                                    + " needs collaborators or configuration, inject them as bean"
                                    + " fields/constructor arguments and reference them from the"
                                    + " no-arg body.")
                        .evidence(
                                "@Scheduled method "
                                        + method.getNameAsString()
                                        + " declares "
                                        + method.getParameters().size()
                                        + " parameter(s) in "
                                        + relativePath
                                        + ".")
                        .source(relativePath, line)
                        .target(target)
                        .build());
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_ASYNC_SELF_INVOCATION
    // ---------------------------------------------------------------------------

    private void detectAsyncSelfInvocation(
            ClassOrInterfaceDeclaration cls,
            MethodDeclaration method,
            Set<String> asyncMethodNames,
            String relativePath,
            List<Finding> findings) {
        if (asyncMethodNames.isEmpty()) {
            return;
        }
        method.findAll(MethodCallExpr.class)
                .forEach(
                        call -> {
                            if (!asyncMethodNames.contains(call.getNameAsString())) {
                                return;
                            }
                            // Only flag calls without an external receiver: no scope (implicit
                            // this) or an explicit this scope. A call qualified by any other
                            // receiver goes through that bean's proxy and is fine.
                            var scope = call.getScope().orElse(null);
                            boolean isSelfCall = scope == null || scope instanceof ThisExpr;
                            if (!isSelfCall) {
                                return;
                            }
                            // Don't emit on the async method's own recursive self-call.
                            if (method.getNameAsString().equals(call.getNameAsString())) {
                                return;
                            }
                            Integer line = call.getBegin().map(p -> p.line).orElse(null);
                            String callerTarget =
                                    cls.getNameAsString() + "#" + method.getNameAsString();
                            findings.add(
                                    FindingFactory.builder(
                                                    FindingRules.SPRING_ASYNC_SELF_INVOCATION,
                                                    FindingConfidence.MEDIUM)
                                            .shortMessage(
                                                    "@Async method '"
                                                            + call.getNameAsString()
                                                            + "' called via self-invocation in "
                                                            + callerTarget
                                                            + " — it will run synchronously.")
                                            .whyBadPractice(
                                                    "Spring dispatches @Async calls through a proxy"
                                                        + " wrapper around the bean. When a method"
                                                        + " in the same class calls an @Async"
                                                        + " method directly, the call bypasses the"
                                                        + " proxy, so no executor is involved and"
                                                        + " the method runs on the caller's"
                                                        + " thread.")
                                            .possibleImpact(
                                                    "Work assumed to run in the background blocks"
                                                        + " the caller. Any expected concurrency or"
                                                        + " fire-and-forget behaviour is silently"
                                                        + " lost.")
                                            .recommendation(
                                                    "Move the @Async method to a separate"
                                                        + " Spring-managed bean and call it through"
                                                        + " the injected reference, or self-inject"
                                                        + " the bean and invoke the method through"
                                                        + " the injected proxy.")
                                            .evidence(
                                                    "Method "
                                                            + method.getNameAsString()
                                                            + " calls @Async method "
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
    // Helpers
    // ---------------------------------------------------------------------------

    private static boolean hasAnnotation(List<AnnotationExpr> annotations, String name) {
        return annotations.stream().anyMatch(a -> name.equals(simpleName(a.getNameAsString())));
    }

    private static String simpleName(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }
}
