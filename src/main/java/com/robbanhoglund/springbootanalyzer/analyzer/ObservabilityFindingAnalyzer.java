package com.robbanhoglund.springbootanalyzer.analyzer;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingConfidence;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingFactory;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingRules;
import com.robbanhoglund.springbootanalyzer.analyzer.model.runtime.RuntimeStackAnalysis;
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
 * Detects observability gaps in Spring Boot source code using JavaParser AST analysis.
 *
 * <p>The three detections performed by this analyzer are:
 * <ol>
 *   <li><b>{@code @Timed} on Spring Boot 3+</b> — {@code @Timed} is a Micrometer annotation
 *       that only produces a timer metric. On Spring Boot 3.x the {@code @Observed} annotation
 *       from Micrometer Observation API is preferred because it produces both a timer metric
 *       and a distributed-trace span from a single annotation, without requiring AOP
 *       configuration.</li>
 *   <li><b>{@code @Scheduled} methods without observability</b> — scheduled tasks run outside
 *       the normal request context and therefore receive no automatic tracing or metrics unless
 *       the developer explicitly wraps them with {@code @Observed} (or equivalent). This
 *       detection fires when a method carries {@code @Scheduled} but not {@code @Observed}.</li>
 *   <li><b>Messaging listener methods without observability</b> — similarly, methods annotated
 *       with {@code @KafkaListener}, {@code @RabbitListener}, {@code @JmsListener}, or
 *       {@code @SqsListener} are not automatically traced unless wrapped with
 *       {@code @Observed}.</li>
 * </ol>
 *
 * <p>Source parsing uses JavaParser configured at Java 25 language level. Parse failures and
 * unresolvable files are silently skipped.
 */
@Component
public class ObservabilityFindingAnalyzer {

    private static final Set<String> MESSAGING_LISTENER_ANNOTATIONS =
            Set.of("KafkaListener", "RabbitListener", "JmsListener", "SqsListener");

    private final JavaParser javaParser;

    public ObservabilityFindingAnalyzer() {
        this.javaParser =
                new JavaParser(
                        new ParserConfiguration()
                                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25)
                                .setCharacterEncoding(StandardCharsets.UTF_8));
    }

    /**
     * Walks every {@code .java} file under {@code <repositoryRoot>/src/main/java} and
     * returns all observability-gap findings.
     *
     * <p>The runtime stack analysis is used to determine whether the project is running on
     * Spring Boot 3 or later, which governs whether the {@code @Timed} vs {@code @Observed}
     * detection is active.
     *
     * @param repositoryRoot       root directory of the project being analysed
     * @param runtimeStackAnalysis the detected runtime stacks; used for version gating
     * @return all detected observability-gap findings; never null, may be empty
     */
    public List<Finding> analyze(Path repositoryRoot, RuntimeStackAnalysis runtimeStackAnalysis) {
        List<Finding> findings = new ArrayList<>();
        detectObservabilityGaps(repositoryRoot, runtimeStackAnalysis, findings);
        return findings;
    }

    private void detectObservabilityGaps(
            Path repositoryRoot,
            RuntimeStackAnalysis runtimeStackAnalysis,
            List<Finding> findings) {
        Path sourceRoot = repositoryRoot.resolve("src/main/java");
        if (Files.notExists(sourceRoot)) {
            return;
        }
        boolean springBoot3Plus = isSpringBoot3Plus(runtimeStackAnalysis);
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file :
                    files.filter(Files::isRegularFile)
                            .filter(path -> path.toString().endsWith(".java"))
                            .sorted(Comparator.naturalOrder())
                            .toList()) {
                try {
                    javaParser
                            .parse(file)
                            .ifSuccessful(
                                    cu -> {
                                        String relativePath =
                                                repositoryRoot
                                                        .relativize(file)
                                                        .toString()
                                                        .replace('\\', '/');
                                        cu.findAll(ClassOrInterfaceDeclaration.class)
                                                .forEach(
                                                        cls ->
                                                                cls.getMethods()
                                                                        .forEach(
                                                                                method ->
                                                                                        detectObservabilityOnMethod(
                                                                                                relativePath,
                                                                                                cls,
                                                                                                method,
                                                                                                springBoot3Plus,
                                                                                                findings)));
                                    });
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void detectObservabilityOnMethod(
            String relativePath,
            ClassOrInterfaceDeclaration declaration,
            MethodDeclaration method,
            boolean springBoot3Plus,
            List<Finding> findings) {
        boolean isScheduled = hasAnnotation(method.getAnnotations(), "Scheduled");
        boolean isListener =
                hasAnyAnnotation(method.getAnnotations(), MESSAGING_LISTENER_ANNOTATIONS);
        boolean hasTimed = hasAnnotation(method.getAnnotations(), "Timed");
        boolean hasObserved = hasAnnotation(method.getAnnotations(), "Observed");
        Integer line = method.getBegin().map(p -> p.line).orElse(null);
        String target = declaration.getNameAsString() + "#" + method.getNameAsString();

        if (hasTimed && springBoot3Plus) {
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_TIMED_PREFER_OBSERVED,
                                    FindingConfidence.HIGH)
                            .shortMessage(
                                    "@Timed on "
                                            + target
                                            + " — prefer @Observed on Spring Boot 3+.")
                            .whyBadPractice(
                                    "@Timed only produces a timer metric. @Observed (from the"
                                        + " Micrometer Observation API) produces both a metric and"
                                        + " a distributed trace span with a single annotation,"
                                        + " making it the preferred approach on Spring Boot 3.x.")
                            .possibleImpact(
                                    "Using @Timed means trace context is not propagated through"
                                        + " this method, so it will not appear as a named span in"
                                        + " distributed tracing backends such as Zipkin or Jaeger.")
                            .recommendation(
                                    "Replace @Timed with @Observed and ensure an ObservedAspect"
                                        + " @Bean is registered. @Observed can be customised with"
                                        + " name and contextualName attributes.")
                            .evidence(
                                    "@Timed found on "
                                            + method.getNameAsString()
                                            + " in "
                                            + relativePath
                                            + " in a Spring Boot 3+ project.")
                            .limitations(
                                    "Static analysis cannot verify whether the application targets"
                                            + " a tracing backend or whether ObservedAspect is"
                                            + " configured.")
                            .source(relativePath, line)
                            .target(target)
                            .build());
        }

        if (isScheduled && !hasTimed && !hasObserved) {
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_SCHEDULED_NO_OBSERVABILITY,
                                    FindingConfidence.MEDIUM)
                            .shortMessage(
                                    "@Scheduled method "
                                            + target
                                            + " has no observability annotation.")
                            .whyBadPractice(
                                    "Scheduled jobs run in the background and are invisible to"
                                        + " distributed tracing unless instrumented. Without @Timed"
                                        + " or @Observed, their execution duration and failure rate"
                                        + " are not tracked.")
                            .possibleImpact(
                                    "Slow or failing background jobs go undetected until downstream"
                                            + " effects appear. There are no metrics to alert on or"
                                            + " traces to correlate with upstream triggers.")
                            .recommendation(
                                    springBoot3Plus
                                            ? "Add @Observed to the method and register an"
                                                    + " ObservedAspect @Bean. This gives you both a"
                                                    + " timer metric and a trace span."
                                            : "Add @Timed to the method (and a TimedAspect @Bean)"
                                                    + " to at least track execution duration and"
                                                    + " failure rate.")
                            .evidence(
                                    "@Scheduled method "
                                            + method.getNameAsString()
                                            + " found without @Timed or @Observed in "
                                            + relativePath
                                            + ".")
                            .limitations(
                                    "Static analysis cannot verify whether the scheduler thread"
                                        + " pool is already observed through another mechanism.")
                            .source(relativePath, line)
                            .target(target)
                            .build());
        }

        if (isListener && !hasTimed && !hasObserved) {
            String presentAnnotation =
                    MESSAGING_LISTENER_ANNOTATIONS.stream()
                            .filter(name -> hasAnnotation(method.getAnnotations(), name))
                            .findFirst()
                            .orElse("messaging listener");
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_LISTENER_NO_OBSERVABILITY,
                                    FindingConfidence.MEDIUM)
                            .shortMessage(
                                    "@"
                                            + presentAnnotation
                                            + " method "
                                            + target
                                            + " has no observability annotation.")
                            .whyBadPractice(
                                    "Message consumers run asynchronously and are invisible to"
                                        + " distributed tracing unless instrumented. Without @Timed"
                                        + " or @Observed, consumer lag and processing failures are"
                                        + " not tracked.")
                            .possibleImpact(
                                    "Slow consumers, poison messages, and processing errors go"
                                            + " unreported. Correlating a failed message to the"
                                            + " producing trace requires manual effort.")
                            .recommendation(
                                    springBoot3Plus
                                            ? "Add @Observed to the listener method and register an"
                                                    + " ObservedAspect @Bean. This propagates trace"
                                                    + " context and produces a timer metric."
                                            : "Add @Timed to the listener method (and a TimedAspect"
                                                    + " @Bean) to track per-message processing"
                                                    + " duration and error rate.")
                            .evidence(
                                    "@"
                                            + presentAnnotation
                                            + " method "
                                            + method.getNameAsString()
                                            + " found without @Timed or @Observed in "
                                            + relativePath
                                            + ".")
                            .limitations(
                                    "Static analysis cannot verify whether the messaging container"
                                            + " already provides observation via a custom"
                                            + " MessageListenerContainer instrumentation.")
                            .source(relativePath, line)
                            .target(target)
                            .build());
        }
    }

    private static boolean isSpringBoot3Plus(RuntimeStackAnalysis runtimeStackAnalysis) {
        if (runtimeStackAnalysis == null || runtimeStackAnalysis.springBootVersion() == null) {
            return false;
        }
        try {
            String[] parts = runtimeStackAnalysis.springBootVersion().split("\\.");
            return parts.length > 0 && Integer.parseInt(parts[0]) >= 3;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private boolean hasAnyAnnotation(NodeList<AnnotationExpr> annotations, Set<String> names) {
        return annotations.stream()
                .map(annotation -> simpleName(annotation.getNameAsString()))
                .anyMatch(names::contains);
    }

    private boolean hasAnnotation(NodeList<AnnotationExpr> annotations, String name) {
        return annotations.stream()
                .map(annotation -> simpleName(annotation.getNameAsString()))
                .anyMatch(name::equals);
    }

    private String simpleName(String value) {
        int separator = value.lastIndexOf('.');
        return separator < 0 ? value : value.substring(separator + 1);
    }
}
