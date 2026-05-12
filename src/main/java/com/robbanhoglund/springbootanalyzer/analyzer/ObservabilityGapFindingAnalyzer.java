package com.robbanhoglund.springbootanalyzer.analyzer;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.VoidType;
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
import java.util.stream.Stream;
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
 *   <li>{@link FindingRules#SPRING_EXCEPTION_HANDLER_NO_METRICS} — {@code @ExceptionHandler} in
 *       a {@code @ControllerAdvice} class with no {@code MeterRegistry} reference.
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
    private static final Set<String> METRICS_TYPES =
            Set.of("MeterRegistry", "Counter", "Timer", "DistributionSummary", "Gauge");
    private static final Set<String> ASYNC_ALLOWED_RETURN_TYPES =
            Set.of("Future", "CompletableFuture", "ListenableFuture", "Mono", "Flux");

    private final JavaParser javaParser;

    public ObservabilityGapFindingAnalyzer() {
        this.javaParser =
                new JavaParser(
                        new ParserConfiguration()
                                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25)
                                .setCharacterEncoding(StandardCharsets.UTF_8));
    }

    /**
     * Analyzes all Java source files under {@code src/main/java}.
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

        detectWebClientManualConstruction(cu, relativePath, findings);

        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            boolean isControllerAdvice =
                    hasAnnotation(cls, "ControllerAdvice")
                            || hasAnnotation(cls, "RestControllerAdvice");
            boolean classHasMetrics = classReferencesMetrics(cls);

            for (MethodDeclaration method : cls.getMethods()) {
                detectAsyncNoObservability(cls, method, relativePath, findings);
                detectEventListenerNoObservability(cls, method, relativePath, findings);
                detectObservedOnPrivateMethod(cls, method, relativePath, findings);
                detectAsyncNonFutureReturn(cls, method, relativePath, findings);
                if (isControllerAdvice) {
                    detectExceptionHandlerNoMetrics(
                            cls, method, classHasMetrics, relativePath, findings);
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
    // Rule: SPRING_EXCEPTION_HANDLER_NO_METRICS
    // ---------------------------------------------------------------------------

    private void detectExceptionHandlerNoMetrics(
            ClassOrInterfaceDeclaration cls,
            MethodDeclaration method,
            boolean classHasMetrics,
            String relativePath,
            List<Finding> findings) {
        if (!hasAnnotation(method, "ExceptionHandler")) {
            return;
        }
        // Skip if the class-level already injects a metrics type (centralised recording)
        if (classHasMetrics) {
            return;
        }
        // Also skip if the method itself calls a metrics type (local reference)
        boolean methodCallsMetrics =
                method.findAll(MethodCallExpr.class).stream()
                        .anyMatch(
                                call ->
                                        call.getScope()
                                                .map(
                                                        s ->
                                                                METRICS_TYPES.stream()
                                                                        .anyMatch(
                                                                                t ->
                                                                                        s.toString()
                                                                                                .contains(
                                                                                                        t)))
                                                .orElse(false));
        if (methodCallsMetrics) {
            return;
        }
        Integer line = method.getBegin().map(p -> p.line).orElse(null);
        String target = cls.getNameAsString() + "#" + method.getNameAsString();
        findings.add(
                FindingFactory.builder(
                                FindingRules.SPRING_EXCEPTION_HANDLER_NO_METRICS,
                                FindingConfidence.LOW)
                        .shortMessage(
                                "@ExceptionHandler "
                                        + target
                                        + " records no error metrics — error rates are invisible.")
                        .whyBadPractice(
                                "Error rate is one of the three core RED method signals (Rate,"
                                    + " Errors, Duration). An @ExceptionHandler that only logs or"
                                    + " returns an error response gives no metric signal. Operators"
                                    + " cannot alert on error rate increases without a counter.")
                        .possibleImpact(
                                "Error rate spikes are invisible until users report problems;"
                                        + " SLO burn rate cannot be calculated from metrics alone.")
                        .recommendation(
                                "Inject MeterRegistry and increment a counter:"
                                    + " meterRegistry.counter(\"http.errors\", \"exception\","
                                    + " ex.getClass().getSimpleName()).increment(); or use @Timed"
                                    + " on the handler method.")
                        .limitations(
                                "Low confidence — the class may delegate to a shared metrics"
                                        + " utility that is not directly visible in the field"
                                        + " declarations.")
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
            List<Finding> findings) {
        if (!hasAnnotation(method, "Async")) {
            return;
        }
        if (method.getType() instanceof VoidType) {
            return;
        }
        String rawType = rawTypeName(method.getType().asString());
        if (ASYNC_ALLOWED_RETURN_TYPES.contains(rawType)) {
            return;
        }
        Integer line = method.getBegin().map(p -> p.line).orElse(null);
        String target = cls.getNameAsString() + "#" + method.getNameAsString();
        findings.add(
                FindingFactory.builder(
                                FindingRules.SPRING_ASYNC_NON_FUTURE_RETURN, FindingConfidence.HIGH)
                        .shortMessage(
                                "@Async method "
                                        + target
                                        + " returns "
                                        + rawType
                                        + " — the return value is discarded by the async proxy.")
                        .whyBadPractice(
                                "Spring's async proxy intercepts the method call, dispatches it to"
                                    + " a thread pool, and immediately returns to the caller. The"
                                    + " actual return value from the method body is discarded. Only"
                                    + " void, Future, CompletableFuture, ListenableFuture, Mono,"
                                    + " and Flux are supported return types for @Async methods.")
                        .possibleImpact(
                                "The caller always receives null (or an immediately-resolved empty"
                                    + " value) instead of the computed result. This is a silent"
                                    + " data loss bug that is hard to diagnose because the method"
                                    + " body executes correctly — the result just never reaches the"
                                    + " caller.")
                        .recommendation(
                                "Change the return type to CompletableFuture<"
                                        + rawType
                                        + "> and wrap the return value: return"
                                        + " CompletableFuture.completedFuture(result)."
                                        + " Alternatively, change the return type to void if the"
                                        + " caller does not use the return value.")
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

    /** Returns true if the class has a field whose type name is a known metrics type. */
    private static boolean classReferencesMetrics(ClassOrInterfaceDeclaration cls) {
        return cls.getFields().stream()
                .anyMatch(
                        f ->
                                f.getVariables().stream()
                                        .anyMatch(
                                                v ->
                                                        METRICS_TYPES.stream()
                                                                .anyMatch(
                                                                        t ->
                                                                                v.getTypeAsString()
                                                                                        .contains(
                                                                                                t))));
    }

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
