package com.robbanhoglund.springbootanalyzer.analyzer;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.VoidType;
import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildInfo;
import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingConfidence;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingFactory;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingRules;
import com.robbanhoglund.springbootanalyzer.analyzer.source.JavaSources;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Detects observability blind spots in {@code src/main/java} that are not covered by the existing
 * {@link ObservabilityFindingAnalyzer} (which focuses on {@code @Scheduled} and messaging
 * listeners).
 *
 * <p>Rules covered:
 *
 * <ul>
 *   <li>{@link FindingRules#SPRING_ASYNC_NO_OBSERVABILITY} — {@code @Async} method with no
 *       {@code @Observed} or {@code @Timed}.
 *   <li>{@link FindingRules#SPRING_EVENT_LISTENER_NO_OBSERVABILITY} — {@code @EventListener} /
 *       {@code @TransactionalEventListener} method with no {@code @Observed} or {@code @Timed}.
 *   <li>{@link FindingRules#SPRING_OBSERVED_ON_PRIVATE_METHOD} — {@code @Observed} on a private
 *       method that Spring's proxy cannot intercept.
 *   <li>{@link FindingRules#SPRING_WEBCLIENT_MANUALLY_CONSTRUCTED} — {@code WebClient} created
 *       via {@code WebClient.create()} or {@code WebClient.builder()} instead of the
 *       auto-configured {@code WebClient.Builder} bean.
 * </ul>
 */
@Component
public class ObservabilityGapFindingAnalyzer {

    private static final Set<String> OBSERVABILITY_ANNOTATIONS = Set.of("Observed", "Timed");
    private static final Set<String> EVENT_LISTENER_ANNOTATIONS =
            Set.of("EventListener", "TransactionalEventListener");

    /**
     * Return types Spring's {@code AsyncExecutionAspectSupport} can hand back from an {@code
     * @Async} call besides {@code void}. Reactor's {@code Mono}/{@code Flux} are not among them.
     */
    private static final Set<String> ASYNC_ALLOWED_RETURN_TYPES =
            Set.of("Future", "CompletableFuture", "ListenableFuture");

    /**
     * Analyzes all Java source files under {@code src/main/java}.
     *
     * @param repositoryRoot root directory of the locally checked-out repository
     * @return list of findings; never null
     */
    public List<Finding> analyze(Path repositoryRoot) {
        return analyze(JavaSources.from(repositoryRoot), null);
    }

    /**
     * Analyzes the {@code src/main/java} sources parsed once and shared across the pipeline.
     *
     * @param sources the source tree parsed once for this analysis
     * @param buildInfo resolved build information, used to gate the "missing observability
     *     annotation" rules on an observability stack actually being present; may be null
     * @return list of findings; never null
     */
    public List<Finding> analyze(JavaSources sources, BuildInfo buildInfo) {
        List<Finding> findings = new ArrayList<>();
        boolean observabilityStackPresent = hasObservabilityStack(buildInfo);
        // Without @EnableAsync the @Async annotation is inert and the method returns its value
        // synchronously, so the return-type rule only applies once async is enabled.
        boolean asyncEnabled =
                sources.files().stream()
                        .anyMatch(
                                file ->
                                        file.content() != null
                                                && file.content().contains("@EnableAsync"));
        String bootVersion = buildInfo == null ? null : buildInfo.springBootVersion();
        for (JavaSources.JavaFile file : sources.files()) {
            if (file.compilationUnit() != null) {
                analyzeSourceFile(
                        file.compilationUnit(),
                        file.relativePath(),
                        observabilityStackPresent,
                        asyncEnabled,
                        bootVersion,
                        findings);
            }
        }
        return findings;
    }

    /**
     * Recommending {@code @Observed}/{@code @Timed} only makes sense when Micrometer or actuator
     * is on the classpath — without them the annotations are inert, so the advice would be noise.
     */
    private static boolean hasObservabilityStack(BuildInfo buildInfo) {
        if (buildInfo == null || buildInfo.dependencies() == null) {
            return false;
        }
        return buildInfo.dependencies().stream()
                .anyMatch(
                        dep -> {
                            String lower = dep.toLowerCase(java.util.Locale.ROOT);
                            return lower.contains("micrometer")
                                    || lower.contains("spring-boot-starter-actuator")
                                    || lower.contains("opentelemetry")
                                    || lower.contains("spring-cloud-starter-sleuth");
                        });
    }

    // ---------------------------------------------------------------------------
    // Per-file analysis
    // ---------------------------------------------------------------------------

    private void analyzeSourceFile(
            CompilationUnit cu,
            String relativePath,
            boolean observabilityStackPresent,
            boolean asyncEnabled,
            String bootVersion,
            List<Finding> findings) {
        detectWebClientManualConstruction(cu, relativePath, findings);

        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            for (MethodDeclaration method : cls.getMethods()) {
                if (observabilityStackPresent) {
                    detectAsyncNoObservability(cls, method, relativePath, findings);
                    detectEventListenerNoObservability(cls, method, relativePath, findings);
                }
                detectObservedOnPrivateMethod(cls, method, relativePath, findings);
                if (asyncEnabled) {
                    detectAsyncNonFutureReturn(cls, method, relativePath, bootVersion, findings);
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_ASYNC_NO_OBSERVABILITY
    // ---------------------------------------------------------------------------

    private void detectAsyncNoObservability(
            ClassOrInterfaceDeclaration cls,
            MethodDeclaration method,
            String relativePath,
            List<Finding> findings) {
        if (!hasAnnotation(method, "Async")) {
            return;
        }
        if (hasAnyAnnotation(method, OBSERVABILITY_ANNOTATIONS)) {
            return;
        }
        Integer line = method.getBegin().map(p -> p.line).orElse(null);
        String target = cls.getNameAsString() + "#" + method.getNameAsString();
        findings.add(
                FindingFactory.builder(
                                FindingRules.SPRING_ASYNC_NO_OBSERVABILITY,
                                FindingConfidence.MEDIUM)
                        .shortMessage(
                                "@Async method "
                                        + target
                                        + " has no observability annotation — background work"
                                        + " is invisible to traces and metrics.")
                        .whyBadPractice(
                                "@Async dispatches work to a thread pool, creating a new execution"
                                    + " context that is detached from the caller's trace span by"
                                    + " default. Without @Observed or @Timed, this background work"
                                    + " is completely invisible in distributed traces and"
                                    + " dashboards.")
                        .possibleImpact(
                                "Async work that fails, runs slowly, or starves the thread pool is"
                                    + " impossible to diagnose because there is no trace span, no"
                                    + " latency histogram, and no error counter.")
                        .recommendation(
                                "Add @Observed(name = \""
                                        + toMetricName(method.getNameAsString())
                                        + "\") to the method. Ensure"
                                        + " ObservedAspect is registered as a bean"
                                        + " (spring-boot-actuator does this automatically).")
                        .evidence(
                                "Method "
                                        + target
                                        + " is annotated with @Async but has no @Observed or"
                                        + " @Timed annotation in "
                                        + relativePath
                                        + ".")
                        .source(relativePath, line)
                        .target(target)
                        .build());
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_EVENT_LISTENER_NO_OBSERVABILITY
    // ---------------------------------------------------------------------------

    private void detectEventListenerNoObservability(
            ClassOrInterfaceDeclaration cls,
            MethodDeclaration method,
            String relativePath,
            List<Finding> findings) {
        if (!hasAnyAnnotation(method, EVENT_LISTENER_ANNOTATIONS)) {
            return;
        }
        if (hasAnyAnnotation(method, OBSERVABILITY_ANNOTATIONS)) {
            return;
        }
        String eventAnn =
                method.getAnnotations().stream()
                        .map(a -> simpleName(a.getNameAsString()))
                        .filter(EVENT_LISTENER_ANNOTATIONS::contains)
                        .findFirst()
                        .orElse("EventListener");
        Integer line = method.getBegin().map(p -> p.line).orElse(null);
        String target = cls.getNameAsString() + "#" + method.getNameAsString();
        findings.add(
                FindingFactory.builder(
                                FindingRules.SPRING_EVENT_LISTENER_NO_OBSERVABILITY,
                                FindingConfidence.LOW)
                        .shortMessage(
                                "@"
                                        + eventAnn
                                        + " method "
                                        + target
                                        + " has no observability annotation — event handling is"
                                        + " invisible to traces.")
                        .whyBadPractice(
                                "Application events often trigger significant work (sending"
                                        + " notifications, updating projections, triggering"
                                        + " integrations). Without instrumentation, these execution"
                                        + " paths are invisible in distributed traces, making it"
                                        + " impossible to correlate slow responses with event"
                                        + " processing overhead.")
                        .possibleImpact(
                                "Latency regressions caused by event handler slowness are not"
                                        + " visible in traces; failed event handlers leave no"
                                        + " observable signal beyond application logs.")
                        .recommendation(
                                "Add @Observed(name = \""
                                        + toMetricName(method.getNameAsString())
                                        + "\") if the handler performs"
                                        + " significant work. Trivial listeners (e.g. logging only)"
                                        + " can be excluded.")
                        .limitations(
                                "Low confidence — lightweight listeners that only log or update"
                                        + " a counter do not need @Observed.")
                        .source(relativePath, line)
                        .target(target)
                        .build());
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_OBSERVED_ON_PRIVATE_METHOD
    // ---------------------------------------------------------------------------

    private void detectObservedOnPrivateMethod(
            ClassOrInterfaceDeclaration cls,
            MethodDeclaration method,
            String relativePath,
            List<Finding> findings) {
        if (!method.isPrivate()) {
            return;
        }
        if (!hasAnnotation(method, "Observed")) {
            return;
        }
        Integer line = method.getBegin().map(p -> p.line).orElse(null);
        String target = cls.getNameAsString() + "#" + method.getNameAsString();
        findings.add(
                FindingFactory.builder(
                                FindingRules.SPRING_OBSERVED_ON_PRIVATE_METHOD,
                                FindingConfidence.HIGH)
                        .shortMessage(
                                "@Observed on private method "
                                        + target
                                        + " — Spring's proxy cannot intercept it; no span will be"
                                        + " created.")
                        .whyBadPractice(
                                "Spring applies @Observed through ObservedAspect, which is a"
                                    + " proxy-based AOP aspect. Proxies can only intercept calls"
                                    + " made through the proxy reference; they cannot override"
                                    + " private methods. The annotation is silently ignored at"
                                    + " runtime.")
                        .possibleImpact(
                                "No trace span is created for the method despite the annotation;"
                                        + " developers may incorrectly assume the method is being"
                                        + " observed and make decisions based on missing data.")
                        .recommendation(
                                "Change the method visibility to package-private, protected, or"
                                    + " public so that the proxy can intercept the call. If the"
                                    + " method must remain private, extract the observable work to"
                                    + " a separate public method or use AspectJ weaving.")
                        .limitations(
                                "If the project uses AspectJ compile-time or load-time weaving"
                                        + " instead of Spring proxies, private methods can be"
                                        + " instrumented.")
                        .evidence(
                                "@Observed found on private method "
                                        + method.getNameAsString()
                                        + " in "
                                        + relativePath
                                        + ".")
                        .source(relativePath, line)
                        .target(target)
                        .build());
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_WEBCLIENT_MANUALLY_CONSTRUCTED
    // ---------------------------------------------------------------------------

    private void detectWebClientManualConstruction(
            CompilationUnit cu, String relativePath, List<Finding> findings) {
        // Check if the file imports WebClient at all — skip if not
        boolean importsWebClient =
                cu.getImports().stream().anyMatch(i -> i.getNameAsString().contains("WebClient"));
        if (!importsWebClient) {
            return;
        }

        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            String methodName = call.getNameAsString();
            boolean isManualConstruction =
                    // WebClient.create() / WebClient.create(url)
                    ("create".equals(methodName)
                                    && call.getScope()
                                            .map(s -> "WebClient".equals(s.toString()))
                                            .orElse(false))
                            // WebClient.builder() — manual builder (as opposed to injected builder)
                            || ("builder".equals(methodName)
                                    && call.getScope()
                                            .map(s -> "WebClient".equals(s.toString()))
                                            .orElse(false));
            if (!isManualConstruction) {
                continue;
            }
            Integer line = call.getBegin().map(p -> p.line).orElse(null);
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_WEBCLIENT_MANUALLY_CONSTRUCTED,
                                    FindingConfidence.HIGH)
                            .shortMessage(
                                    "WebClient constructed manually via WebClient."
                                            + methodName
                                            + "() — bypasses Spring Boot's auto-configured"
                                            + " observability.")
                            .whyBadPractice(
                                    "Spring Boot auto-configures a WebClient.Builder bean with"
                                        + " Micrometer tracing, HTTP client metrics"
                                        + " (http.client.requests), and observation context"
                                        + " propagation pre-wired. Calling WebClient.create() or"
                                        + " WebClient.builder() directly constructs a bare client"
                                        + " with none of that instrumentation.")
                            .possibleImpact(
                                    "Outbound HTTP calls are invisible in distributed traces;"
                                        + " http.client.requests metrics are not recorded; trace"
                                        + " context is not propagated to downstream services.")
                            .recommendation(
                                    "Inject WebClient.Builder from Spring's context and call"
                                        + " .build() on it: @Autowired WebClient.Builder builder;"
                                        + " WebClient client = builder.baseUrl(url).build();")
                            .evidence(
                                    "WebClient."
                                            + methodName
                                            + "() call found in "
                                            + relativePath
                                            + " at line "
                                            + (line != null ? line : "?")
                                            + ".")
                            .location(relativePath + (line != null ? ":" + line : ""))
                            .source(relativePath, line)
                            .build());
        }
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_ASYNC_NON_FUTURE_RETURN
    // ---------------------------------------------------------------------------

    private void detectAsyncNonFutureReturn(
            ClassOrInterfaceDeclaration cls,
            MethodDeclaration method,
            String relativePath,
            String bootVersion,
            List<Finding> findings) {
        if (!hasAnnotation(method, "Async")) {
            return;
        }
        if (method.getType() instanceof VoidType) {
            return;
        }
        String rawType = rawTypeName(method.getType().asString());
        // ASYNC_ALLOWED_RETURN_TYPES holds simple names, so compare against the simple name to
        // avoid a false positive when the method declares a fully-qualified return type
        // (e.g. java.util.concurrent.CompletableFuture<T>).
        String simpleType = rawType.substring(rawType.lastIndexOf('.') + 1);
        if (ASYNC_ALLOWED_RETURN_TYPES.contains(simpleType)) {
            return;
        }
        Integer line = method.getBegin().map(p -> p.line).orElse(null);
        String target = cls.getNameAsString() + "#" + method.getNameAsString();
        // Spring Framework 6 (Spring Boot 3) rejects the call; Framework 5 silently returns null.
        boolean legacyFramework = SpringBootVersions.isBefore(bootVersion, 3, 0);
        boolean modernFramework = SpringBootVersions.isAtLeast(bootVersion, 3, 0);
        String behaviour =
                modernFramework
                        ? "Spring Framework 6+ rejects it: every call throws"
                                + " IllegalArgumentException (\"Invalid return type for async"
                                + " method (only Future and void supported)\")."
                        : legacyFramework
                                ? "Spring Framework 5 submits the work and returns null to the"
                                        + " caller, discarding the computed value."
                                : "On Spring Framework 6+ (Spring Boot 3+) every call throws"
                                        + " IllegalArgumentException; on Framework 5 (Spring Boot"
                                        + " 2) the caller silently receives null.";
        findings.add(
                FindingFactory.builder(
                                FindingRules.SPRING_ASYNC_NON_FUTURE_RETURN, FindingConfidence.HIGH)
                        .shortMessage(
                                "@Async method "
                                        + target
                                        + " returns "
                                        + rawType
                                        + ", which Spring's async proxy does not support.")
                        .whyBadPractice(
                                "Spring's async interceptor hands the call to an executor and can"
                                        + " only return void or a Future (CompletableFuture,"
                                        + " ListenableFuture) to the caller. Reactor types such as"
                                        + " Mono and Flux are not supported. "
                                        + behaviour)
                        .possibleImpact(
                                modernFramework
                                        ? "Every invocation fails at runtime, so the feature behind"
                                                + " this method is broken."
                                        : "Callers either fail on every invocation or silently"
                                                + " receive null instead of the computed result.")
                        .recommendation(
                                "Return CompletableFuture<"
                                        + rawType
                                        + "> and complete it with"
                                        + " CompletableFuture.completedFuture(result), or return"
                                        + " void if the caller does not need a result. For"
                                        + " reactive code, drop @Async and return the publisher"
                                        + " directly.")
                        .evidence(
                                "Method "
                                        + target
                                        + " is annotated with @Async and returns "
                                        + method.getType().asString()
                                        + " in "
                                        + relativePath
                                        + ".")
                        .source(relativePath, line)
                        .target(target)
                        .build());
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private static boolean hasAnnotation(ClassOrInterfaceDeclaration cls, String name) {
        return cls.getAnnotations().stream()
                .anyMatch(a -> simpleName(a.getNameAsString()).equals(name));
    }

    private static boolean hasAnnotation(MethodDeclaration method, String name) {
        return method.getAnnotations().stream()
                .anyMatch(a -> simpleName(a.getNameAsString()).equals(name));
    }

    private static boolean hasAnyAnnotation(MethodDeclaration method, Set<String> names) {
        return method.getAnnotations().stream()
                .anyMatch(a -> names.contains(simpleName(a.getNameAsString())));
    }

    private static String simpleName(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    /** Strips generic type parameters: {@code CompletableFuture<String>} → {@code CompletableFuture}. */
    private static String rawTypeName(String type) {
        int lt = type.indexOf('<');
        return lt >= 0 ? type.substring(0, lt).trim() : type.trim();
    }

    /** Converts a camelCase method name to a dot-separated metric name. */
    private static String toMetricName(String methodName) {
        return methodName.replaceAll("([A-Z])", ".$1").toLowerCase().replaceAll("^\\.+", "");
    }
}
