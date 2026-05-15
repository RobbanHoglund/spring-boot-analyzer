package com.robbanhoglund.springbootanalyzer.analyzer;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Detects cloud-native scalability and bean-lifecycle anti-patterns in {@code src/main/java}.
 *
 * <p>Rules covered:
 *
 * <ul>
 *   <li>{@link FindingRules#SPRING_PROTOTYPE_BEAN_IN_SINGLETON} — prototype-scoped bean injected
 *       directly into a singleton, losing per-use instantiation semantics.
 *   <li>{@link FindingRules#SPRING_FILTER_COMPONENT_REGISTRATION_LEAK} — {@code @Component} on a
 *       {@code Filter} implementation causes global servlet registration bypassing Security config.
 *   <li>{@link FindingRules#SPRING_HARDCODED_FILE_PATH} — absolute file system path literals
 *       passed to {@code new File(…)} or {@code Paths.get(…)}.
 *   <li>{@link FindingRules#SPRING_LOMBOK_DATA_ON_ENTITY} — {@code @Data} combined with
 *       {@code @Entity}, risking eager proxy initialisation and infinite recursion.
 *   <li>{@link FindingRules#SPRING_REST_TEMPLATE_NO_TIMEOUT} — {@code new RestTemplate()} with
 *       no-arg constructor and no explicit timeout.
 *   <li>{@link FindingRules#SPRING_WEBFLUX_BLOCKING_CALL} — {@code .block()}, {@code .blockFirst()},
 *       {@code .blockLast()}, or {@code Thread.sleep()} called inside a Spring-managed component.
 * </ul>
 */
@Component
public class ScalabilityPracticeFindingAnalyzer {

    private static final Pattern ABSOLUTE_PATH_PATTERN =
            Pattern.compile(
                    "^(/var/|/tmp/|/home/|/etc/|/opt/|/data/|/mnt/|/srv/|/usr/)"
                            + "|^[A-Za-z]:\\\\");

    private final JavaParser javaParser;

    public ScalabilityPracticeFindingAnalyzer() {
        this.javaParser =
                new JavaParser(
                        new ParserConfiguration()
                                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25)
                                .setCharacterEncoding(StandardCharsets.UTF_8));
    }

    private static final Set<String> SINGLETON_ANNOTATIONS =
            Set.of(
                    "Service",
                    "Component",
                    "Controller",
                    "RestController",
                    "Repository",
                    "Configuration");

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

        List<Path> sourceFiles;
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            sourceFiles =
                    walk.filter(Files::isRegularFile)
                            .filter(p -> p.toString().endsWith(".java"))
                            .sorted(Comparator.naturalOrder())
                            .toList();
        } catch (IOException e) {
            return findings;
        }

        // Pass 1: collect all simple type names of @Scope("prototype") beans
        Set<String> prototypeTypes = collectPrototypeTypes(sourceFiles);

        // Pass 2: per-file analysis
        for (Path sourceFile : sourceFiles) {
            try {
                analyzeSourceFile(repositoryRoot, sourceFile, prototypeTypes, findings);
            } catch (IOException e) {
                // Best-effort — skip unreadable files
            }
        }
        return findings;
    }

    // ---------------------------------------------------------------------------
    // Pass 1: collect prototype-scoped type names
    // ---------------------------------------------------------------------------

    private Set<String> collectPrototypeTypes(List<Path> sourceFiles) {
        Set<String> prototypeTypes = new HashSet<>();
        for (Path sourceFile : sourceFiles) {
            com.github.javaparser.ParseResult<CompilationUnit> parseResult;
            try {
                parseResult = javaParser.parse(sourceFile);
            } catch (IOException e) {
                continue;
            }
            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                continue;
            }
            CompilationUnit cu = parseResult.getResult().orElseThrow();
            for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                boolean isPrototype =
                        cls.getAnnotations().stream()
                                .anyMatch(
                                        a -> {
                                            if (!simpleName(a.getNameAsString()).equals("Scope")) {
                                                return false;
                                            }
                                            return a.toString().contains("prototype");
                                        });
                if (isPrototype) {
                    prototypeTypes.add(cls.getNameAsString());
                }
            }
        }
        return prototypeTypes;
    }

    // ---------------------------------------------------------------------------
    // Pass 2: per-file analysis
    // ---------------------------------------------------------------------------

    private void analyzeSourceFile(
            Path repositoryRoot,
            Path sourceFile,
            Set<String> prototypeTypes,
            List<Finding> findings)
            throws IOException {
        var parseResult = javaParser.parse(sourceFile);
        if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
            return;
        }
        CompilationUnit cu = parseResult.getResult().orElseThrow();
        String relativePath = repositoryRoot.relativize(sourceFile).toString().replace('\\', '/');

        detectHardcodedFilePaths(cu, relativePath, findings);
        detectRestTemplateNoTimeout(cu, relativePath, findings);
        detectWebFluxBlockingCalls(cu, relativePath, findings);

        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            detectLombokDataOnEntity(cls, relativePath, findings);
            detectFilterComponentRegistrationLeak(cls, relativePath, findings);
            if (!prototypeTypes.isEmpty()) {
                detectPrototypeBeanInSingleton(cls, relativePath, prototypeTypes, findings);
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_HARDCODED_FILE_PATH
    // ---------------------------------------------------------------------------

    private void detectHardcodedFilePaths(
            CompilationUnit cu, String relativePath, List<Finding> findings) {
        for (ObjectCreationExpr expr : cu.findAll(ObjectCreationExpr.class)) {
            String typeName = expr.getTypeAsString();
            if (!"File".equals(typeName) && !"java.io.File".equals(typeName)) {
                continue;
            }
            expr.getArguments().stream()
                    .filter(arg -> arg instanceof StringLiteralExpr)
                    .map(arg -> ((StringLiteralExpr) arg).asString())
                    .filter(val -> ABSOLUTE_PATH_PATTERN.matcher(val).find())
                    .findFirst()
                    .ifPresent(
                            val -> {
                                Integer line = expr.getBegin().map(p -> p.line).orElse(null);
                                findings.add(
                                        FindingFactory.builder(
                                                        FindingRules.SPRING_HARDCODED_FILE_PATH,
                                                        FindingConfidence.HIGH)
                                                .shortMessage(
                                                        "Hardcoded absolute path \""
                                                                + val
                                                                + "\" passed to new File() in "
                                                                + relativePath
                                                                + ".")
                                                .whyBadPractice(
                                                        "Hardcoded absolute file system paths break"
                                                            + " in containerised or cloud-native"
                                                            + " deployments where the path may not"
                                                            + " exist. Data written to a"
                                                            + " container's local file system is"
                                                            + " also lost on restart or horizontal"
                                                            + " scaling.")
                                                .possibleImpact(
                                                        "Application fails on startup or at runtime"
                                                            + " in cloud environments. Files"
                                                            + " written to a container's local disk"
                                                            + " are lost on pod restart, causing"
                                                            + " silent data loss.")
                                                .recommendation(
                                                        "Abstract file storage behind an interface"
                                                            + " and use cloud-agnostic object"
                                                            + " storage (Amazon S3, Azure Blob,"
                                                            + " GCS) for uploaded files and"
                                                            + " persistent data. Read paths from"
                                                            + " configuration properties rather"
                                                            + " than hardcoding them.")
                                                .limitations(
                                                        "Only detects string literals passed"
                                                                + " directly to new File(). Paths"
                                                                + " assembled via concatenation or"
                                                                + " variables are not detected.")
                                                .evidence(
                                                        "new File(\""
                                                                + val
                                                                + "\") found in "
                                                                + relativePath
                                                                + ".")
                                                .source(relativePath, line)
                                                .build());
                            });
        }

        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            if (!"get".equals(call.getNameAsString())) {
                continue;
            }
            boolean scopeIsPaths =
                    call.getScope().map(s -> s.toString().endsWith("Paths")).orElse(false);
            if (!scopeIsPaths) {
                continue;
            }
            call.getArguments().stream()
                    .filter(arg -> arg instanceof StringLiteralExpr)
                    .map(arg -> ((StringLiteralExpr) arg).asString())
                    .filter(val -> ABSOLUTE_PATH_PATTERN.matcher(val).find())
                    .findFirst()
                    .ifPresent(
                            val -> {
                                Integer line = call.getBegin().map(p -> p.line).orElse(null);
                                findings.add(
                                        FindingFactory.builder(
                                                        FindingRules.SPRING_HARDCODED_FILE_PATH,
                                                        FindingConfidence.HIGH)
                                                .shortMessage(
                                                        "Hardcoded absolute path \""
                                                                + val
                                                                + "\" passed to Paths.get() in "
                                                                + relativePath
                                                                + ".")
                                                .whyBadPractice(
                                                        "Hardcoded absolute file system paths break"
                                                            + " in containerised or cloud-native"
                                                            + " deployments where the path may not"
                                                            + " exist. Data written to a"
                                                            + " container's local file system is"
                                                            + " also lost on restart or horizontal"
                                                            + " scaling.")
                                                .possibleImpact(
                                                        "Application fails on startup or at runtime"
                                                            + " in cloud environments. Files"
                                                            + " written to a container's local disk"
                                                            + " are lost on pod restart, causing"
                                                            + " silent data loss.")
                                                .recommendation(
                                                        "Abstract file storage behind an interface"
                                                            + " and use cloud-agnostic object"
                                                            + " storage (Amazon S3, Azure Blob,"
                                                            + " GCS) for uploaded files and"
                                                            + " persistent data. Read paths from"
                                                            + " configuration properties rather"
                                                            + " than hardcoding them.")
                                                .limitations(
                                                        "Only detects string literals passed"
                                                                + " directly to Paths.get(). Paths"
                                                                + " assembled via concatenation or"
                                                                + " variables are not detected.")
                                                .evidence(
                                                        "Paths.get(\""
                                                                + val
                                                                + "\") found in "
                                                                + relativePath
                                                                + ".")
                                                .source(relativePath, line)
                                                .build());
                            });
        }
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_LOMBOK_DATA_ON_ENTITY
    // ---------------------------------------------------------------------------

    private void detectLombokDataOnEntity(
            ClassOrInterfaceDeclaration cls, String relativePath, List<Finding> findings) {
        boolean hasEntity =
                cls.getAnnotations().stream()
                        .anyMatch(a -> simpleName(a.getNameAsString()).equals("Entity"));
        boolean hasData =
                cls.getAnnotations().stream()
                        .anyMatch(a -> simpleName(a.getNameAsString()).equals("Data"));
        if (!hasEntity || !hasData) {
            return;
        }
        Integer line = cls.getBegin().map(p -> p.line).orElse(null);
        String target = cls.getNameAsString();
        findings.add(
                FindingFactory.builder(
                                FindingRules.SPRING_LOMBOK_DATA_ON_ENTITY, FindingConfidence.HIGH)
                        .shortMessage(
                                "@Data combined with @Entity on "
                                        + target
                                        + " — auto-generated equals/hashCode/toString risk.")
                        .whyBadPractice(
                                "Lombok @Data generates equals(), hashCode(), and toString() over"
                                    + " all fields. On JPA entities, these generated methods"
                                    + " traverse lazy-loaded associations, eagerly initialising the"
                                    + " entire object graph. Bidirectional relationships cause"
                                    + " infinite recursion and StackOverflowError when any of these"
                                    + " methods are called (e.g., during logging, hashing, or"
                                    + " serialisation).")
                        .possibleImpact(
                                "StackOverflowError in production when entities with bidirectional"
                                    + " associations are serialised to JSON, put in a Set/Map, or"
                                    + " logged. LazyInitializationException if lazy associations"
                                    + " are accessed outside a transaction via the generated"
                                    + " methods.")
                        .recommendation(
                                "Replace @Data with @Getter and @Setter. Implement equals() and"
                                    + " hashCode() manually based on the entity's primary key, or"
                                    + " use @EqualsAndHashCode(onlyExplicitlyIncluded = true) with"
                                    + " @EqualsAndHashCode.Include on the ID field. Exclude"
                                    + " bidirectional association fields from toString() using"
                                    + " @ToString.Exclude.")
                        .limitations(
                                "High confidence. Risk is always present when @Data is used on an"
                                        + " entity with any association fields.")
                        .evidence(
                                cls.getNameAsString()
                                        + " in "
                                        + relativePath
                                        + " has both @Entity and @Data.")
                        .source(relativePath, line)
                        .target(target)
                        .build());
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_REST_TEMPLATE_NO_TIMEOUT
    // ---------------------------------------------------------------------------

    private void detectRestTemplateNoTimeout(
            CompilationUnit cu, String relativePath, List<Finding> findings) {
        for (ObjectCreationExpr expr : cu.findAll(ObjectCreationExpr.class)) {
            String typeName = expr.getTypeAsString();
            if (!"RestTemplate".equals(typeName)) {
                continue;
            }
            if (!expr.getArguments().isEmpty()) {
                // Arguments suggest a custom factory/interceptor list is being passed
                continue;
            }
            Integer line = expr.getBegin().map(p -> p.line).orElse(null);
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_REST_TEMPLATE_NO_TIMEOUT,
                                    FindingConfidence.MEDIUM)
                            .shortMessage(
                                    "new RestTemplate() with no-arg constructor in "
                                            + relativePath
                                            + " — no timeout is configured.")
                            .whyBadPractice(
                                    "The no-arg RestTemplate constructor uses"
                                        + " SimpleClientHttpRequestFactory with connect and read"
                                        + " timeouts of zero, which means the connection can block"
                                        + " indefinitely. If a downstream service hangs, the"
                                        + " calling thread is held until the OS or JVM forcibly"
                                        + " closes the socket, which can take minutes.")
                            .possibleImpact(
                                    "Thread pool exhaustion and application-wide outage when a"
                                        + " downstream service slows down or becomes unresponsive."
                                        + " Under load, all available threads can be consumed"
                                        + " waiting for a response that never arrives.")
                            .recommendation(
                                    "Configure a timeout-aware request factory:"
                                        + " HttpComponentsClientHttpRequestFactory or"
                                        + " SimpleClientHttpRequestFactory with explicit connect"
                                        + " and read timeouts. Alternatively, inject and use the"
                                        + " auto-configured RestTemplateBuilder which applies"
                                        + " global timeout settings from"
                                        + " spring.mvc.async.request-timeout.")
                            .limitations(
                                    "Medium confidence — a timeout may be configured later by"
                                        + " calling setRequestFactory() on the returned instance."
                                        + " Only direct no-arg constructor calls are detected.")
                            .evidence("new RestTemplate() (no-arg) found in " + relativePath + ".")
                            .source(relativePath, line)
                            .build());
        }
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_PROTOTYPE_BEAN_IN_SINGLETON
    // ---------------------------------------------------------------------------

    private void detectPrototypeBeanInSingleton(
            ClassOrInterfaceDeclaration cls,
            String relativePath,
            Set<String> prototypeTypes,
            List<Finding> findings) {
        boolean isSingleton =
                cls.getAnnotations().stream()
                        .anyMatch(
                                a ->
                                        SINGLETON_ANNOTATIONS.contains(
                                                simpleName(a.getNameAsString())));
        if (!isSingleton) {
            return;
        }
        // Check directly-injected fields
        for (FieldDeclaration field : cls.getFields()) {
            String fieldType = field.getElementType().asString();
            String simpleFieldType = simpleName(fieldType);
            if (!prototypeTypes.contains(simpleFieldType)) {
                continue;
            }
            Integer line = field.getBegin().map(p -> p.line).orElse(null);
            String target = cls.getNameAsString() + "#" + field.getVariable(0).getNameAsString();
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_PROTOTYPE_BEAN_IN_SINGLETON,
                                    FindingConfidence.HIGH)
                            .shortMessage(
                                    "Prototype bean "
                                            + simpleFieldType
                                            + " injected as a field into singleton "
                                            + cls.getNameAsString()
                                            + " — it will only be instantiated once.")
                            .whyBadPractice(
                                    "When a @Scope(\"prototype\") bean is injected directly into a"
                                        + " singleton bean, Spring creates one instance of the"
                                        + " prototype at context startup and reuses it for the"
                                        + " singleton's entire lifetime. The prototype effectively"
                                        + " becomes a singleton, which defeats the purpose of the"
                                        + " scope and typically causes shared mutable state and"
                                        + " thread-safety bugs.")
                            .possibleImpact(
                                    "Race conditions and shared-state bugs if the prototype bean"
                                        + " holds per-request or per-use state. The application"
                                        + " appears to work correctly in low-concurrency testing"
                                        + " but fails unpredictably under production load.")
                            .recommendation(
                                    "Inject an ObjectFactory<"
                                            + simpleFieldType
                                            + "> or Provider<"
                                            + simpleFieldType
                                            + "> instead of the bean directly, and call"
                                            + " objectFactory.getObject() each time a fresh"
                                            + " instance is needed. Alternatively, annotate the"
                                            + " injection method with @Lookup to delegate instance"
                                            + " creation to the Spring container on every call.")
                            .limitations(
                                    "High confidence. Detects direct field injection. Constructor"
                                            + " injection of prototype types is also flagged.")
                            .evidence(
                                    "Field "
                                            + field.getVariable(0).getNameAsString()
                                            + " of type "
                                            + simpleFieldType
                                            + " (prototype) in singleton "
                                            + cls.getNameAsString()
                                            + " in "
                                            + relativePath
                                            + ".")
                            .source(relativePath, line)
                            .target(target)
                            .build());
        }

        // Check constructor parameters (constructor injection)
        for (ConstructorDeclaration ctor : cls.getConstructors()) {
            for (var param : ctor.getParameters()) {
                String paramType = simpleName(param.getTypeAsString());
                if (!prototypeTypes.contains(paramType)) {
                    continue;
                }
                Integer line = ctor.getBegin().map(p -> p.line).orElse(null);
                String target = cls.getNameAsString() + "(" + paramType + ")";
                findings.add(
                        FindingFactory.builder(
                                        FindingRules.SPRING_PROTOTYPE_BEAN_IN_SINGLETON,
                                        FindingConfidence.HIGH)
                                .shortMessage(
                                        "Prototype bean "
                                                + paramType
                                                + " injected via constructor into singleton "
                                                + cls.getNameAsString()
                                                + " — it will only be instantiated once.")
                                .whyBadPractice(
                                        "When a @Scope(\"prototype\") bean is injected into a"
                                            + " singleton's constructor, Spring creates one"
                                            + " instance at context startup and keeps it for the"
                                            + " singleton's lifetime. The prototype loses its"
                                            + " per-use semantics.")
                                .possibleImpact(
                                        "Race conditions and shared-state bugs under concurrent"
                                            + " load if the prototype bean holds per-use state.")
                                .recommendation(
                                        "Inject an ObjectFactory<"
                                                + paramType
                                                + "> or Provider<"
                                                + paramType
                                                + "> and call getObject() each time a fresh"
                                                + " instance is required, or use @Lookup method"
                                                + " injection.")
                                .limitations(
                                        "Constructor injection may be intentional if the class is"
                                                + " itself prototype-scoped — verify the caller's"
                                                + " scope.")
                                .evidence(
                                        "Constructor parameter of type "
                                                + paramType
                                                + " (prototype) in singleton "
                                                + cls.getNameAsString()
                                                + " in "
                                                + relativePath
                                                + ".")
                                .source(relativePath, line)
                                .target(target)
                                .build());
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_FILTER_COMPONENT_REGISTRATION_LEAK
    // ---------------------------------------------------------------------------

    private void detectFilterComponentRegistrationLeak(
            ClassOrInterfaceDeclaration cls, String relativePath, List<Finding> findings) {
        boolean hasComponent =
                cls.getAnnotations().stream()
                        .anyMatch(a -> simpleName(a.getNameAsString()).equals("Component"));
        if (!hasComponent) {
            return;
        }
        boolean implementsFilter =
                cls.getImplementedTypes().stream()
                        .anyMatch(t -> simpleName(t.getNameAsString()).equals("Filter"));
        if (!implementsFilter) {
            return;
        }
        Integer line = cls.getBegin().map(p -> p.line).orElse(null);
        String target = cls.getNameAsString();
        findings.add(
                FindingFactory.builder(
                                FindingRules.SPRING_FILTER_COMPONENT_REGISTRATION_LEAK,
                                FindingConfidence.HIGH)
                        .shortMessage(
                                cls.getNameAsString()
                                        + " implements Filter and is annotated @Component —"
                                        + " Spring Boot will register it globally, bypassing any"
                                        + " SecurityFilterChain restrictions.")
                        .whyBadPractice(
                                "Spring Boot auto-registers every @Component that implements"
                                    + " javax.servlet.Filter or jakarta.servlet.Filter into the"
                                    + " main Servlet filter chain. This happens independently of"
                                    + " any Spring Security configuration. If the filter is also"
                                    + " added to a SecurityFilterChain via addFilterBefore() /"
                                    + " addFilterAfter(), it will execute twice per request. URL"
                                    + " pattern restrictions configured in SecurityFilterChain do"
                                    + " not apply to the auto-registered instance.")
                        .possibleImpact(
                                "Security filters execute for every request including"
                                    + " unauthenticated or public endpoints where they should not"
                                    + " apply. Filters added to a SecurityFilterChain may execute"
                                    + " twice, causing double logging, double token consumption, or"
                                    + " incorrect authentication state.")
                        .recommendation(
                                "Remove @Component from the filter class. Register it exclusively"
                                    + " through Spring Security's SecurityFilterChain using"
                                    + " http.addFilterBefore() or http.addFilterAfter(). If the"
                                    + " filter must be a Spring bean for dependency injection but"
                                    + " should not be auto-registered, declare a"
                                    + " FilterRegistrationBean<YourFilter> bean and call"
                                    + " setEnabled(false) on it.")
                        .limitations(
                                "High confidence. Both @Component and Filter implementation are"
                                    + " required for the finding to trigger. @Service, @Controller,"
                                    + " etc. are not checked — only @Component directly.")
                        .evidence(
                                cls.getNameAsString()
                                        + " in "
                                        + relativePath
                                        + " implements Filter and carries @Component.")
                        .source(relativePath, line)
                        .target(target)
                        .build());
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_WEBFLUX_BLOCKING_CALL
    // ---------------------------------------------------------------------------

    private static final Set<String> BLOCKING_METHOD_NAMES =
            Set.of("block", "blockFirst", "blockLast");

    private void detectWebFluxBlockingCalls(
            CompilationUnit cu, String relativePath, List<Finding> findings) {
        boolean inSpringComponent =
                cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                        .anyMatch(
                                cls ->
                                        cls.getAnnotations().stream()
                                                .anyMatch(
                                                        a ->
                                                                SINGLETON_ANNOTATIONS.contains(
                                                                        simpleName(
                                                                                a
                                                                                        .getNameAsString()))));
        if (!inSpringComponent) {
            return;
        }

        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            boolean isThreadSleep =
                    "sleep".equals(name)
                            && call.getScope()
                                    .map(s -> "Thread".equals(s.toString()))
                                    .orElse(false);
            boolean isReactiveBlock =
                    BLOCKING_METHOD_NAMES.contains(name) && call.getArguments().isEmpty();

            if (!isReactiveBlock && !isThreadSleep) {
                continue;
            }

            Integer line = call.getBegin().map(p -> p.line).orElse(null);
            String callDescription = isThreadSleep ? "Thread.sleep()" : "." + name + "()";
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_WEBFLUX_BLOCKING_CALL,
                                    FindingConfidence.HIGH)
                            .shortMessage(
                                    callDescription
                                            + " called inside a Spring-managed component in "
                                            + relativePath
                                            + " — blocks the calling thread.")
                            .whyBadPractice(
                                    isReactiveBlock
                                            ? "Calling .block() on a Mono or Flux blocks the"
                                                  + " calling thread until the reactive pipeline"
                                                  + " completes. In a WebFlux application this"
                                                  + " blocks a Netty event-loop thread, preventing"
                                                  + " it from handling any other requests and"
                                                  + " causing cascading latency under load. In a"
                                                  + " Servlet/MVC application it signals a"
                                                  + " reactive-to-blocking impedance mismatch."
                                            : "Thread.sleep() blocks the current thread for an"
                                                    + " arbitrary duration. In WebFlux this starves"
                                                    + " the event-loop; in servlet applications it"
                                                    + " ties up a container thread and reduces"
                                                    + " throughput. Scheduled delays should use"
                                                    + " reactive operators (Mono.delay) or"
                                                    + " @Scheduled.")
                            .possibleImpact(
                                    "Event-loop thread starvation in WebFlux, cascading latency"
                                        + " under concurrent load, and thread pool exhaustion. In"
                                        + " the worst case the application becomes unresponsive"
                                        + " under traffic while appearing healthy at low"
                                        + " concurrency.")
                            .recommendation(
                                    isReactiveBlock
                                            ? "Remove .block() and propagate the Mono/Flux to the"
                                                  + " caller. If this class must remain blocking,"
                                                  + " switch to a non-reactive HTTP client"
                                                  + " (RestTemplate, HttpClient) configured with an"
                                                  + " explicit timeout, or offload to a bounded"
                                                  + " Scheduler (Schedulers.boundedElastic()) if"
                                                  + " blocking I/O cannot be avoided."
                                            : "Replace Thread.sleep() with"
                                                  + " Mono.delay(Duration.ofMillis(…)) in reactive"
                                                  + " pipelines, or use @Scheduled for periodic"
                                                  + " background work.")
                            .limitations(
                                    isReactiveBlock
                                            ? "Detects no-arg .block(), .blockFirst(), and"
                                                    + " .blockLast() calls. .block(Duration) with a"
                                                    + " timeout is also blocking but slightly less"
                                                    + " dangerous; consider reviewing those too."
                                            : "Detects Thread.sleep() calls in Spring-managed"
                                                    + " classes. Test classes are not scanned.")
                            .evidence(callDescription + " found in " + relativePath + ".")
                            .source(relativePath, line)
                            .build());
        }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private static String simpleName(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }
}
