package com.robbanhoglund.springbootanalyzer.analyzer;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingConfidence;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingFactory;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingRules;
import com.robbanhoglund.springbootanalyzer.analyzer.source.JavaSources;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
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
 *       passed to {@code new File(…)}, {@code Paths.get(…)}, or {@code Path.of(…)}.
 *   <li>{@link FindingRules#SPRING_LOMBOK_DATA_ON_ENTITY} — {@code @Data} combined with
 *       {@code @Entity}, risking eager proxy initialisation and infinite recursion.
 *   <li>{@link FindingRules#SPRING_REST_TEMPLATE_NO_TIMEOUT} — {@code new RestTemplate()} with
 *       no-arg constructor and no explicit timeout.
 *   <li>{@link FindingRules#SPRING_WEBFLUX_BLOCKING_CALL} — {@code .block()}, {@code .blockFirst()},
 *       {@code .blockLast()}, or {@code Thread.sleep()} called inside a Spring-managed component.
 *   <li>{@link FindingRules#SPRING_NON_THREAD_SAFE_FORMATTER_FIELD} — {@code SimpleDateFormat},
 *       {@code NumberFormat}, etc. held as a field in a Spring singleton.
 *   <li>{@link FindingRules#SPRING_UNBOUNDED_FINDALL} — no-arg {@code repository.findAll()} that
 *       can load an entire table into memory.
 *   <li>{@link FindingRules#SPRING_ENTITY_MISSING_ID} — {@code @Entity} class with no
 *       {@code @Id}/{@code @EmbeddedId}.
 * </ul>
 */
@Component
public class ScalabilityPracticeFindingAnalyzer {

    private static final Pattern ABSOLUTE_PATH_PATTERN =
            Pattern.compile(
                    "^(/var/|/tmp/|/home/|/etc/|/opt/|/data/|/mnt/|/srv/|/usr/)"
                            + "|^[A-Za-z]:\\\\");

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
        // Pass 1: collect all simple type names of @Scope("prototype") beans and the names of
        // methods annotated @Transactional(propagation = REQUIRES_NEW).
        Set<String> prototypeTypes = collectPrototypeTypes(sources);
        Set<String> requiresNewMethods = collectRequiresNewMethods(sources);

        // Pass 2: per-file analysis
        for (JavaSources.JavaFile file : sources.files()) {
            if (file.compilationUnit() == null) {
                continue;
            }
            analyzeSourceFile(
                    file.compilationUnit(),
                    file.relativePath(),
                    prototypeTypes,
                    requiresNewMethods,
                    findings);
        }
        return findings;
    }

    private Set<String> collectRequiresNewMethods(JavaSources sources) {
        Set<String> methods = new HashSet<>();
        for (JavaSources.JavaFile file : sources.files()) {
            CompilationUnit cu = file.compilationUnit();
            if (cu == null) {
                continue;
            }
            for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
                boolean requiresNew =
                        method.getAnnotations().stream()
                                .anyMatch(
                                        a ->
                                                simpleName(a.getNameAsString())
                                                                .equals("Transactional")
                                                        && a.toString().contains("REQUIRES_NEW"));
                if (requiresNew) {
                    methods.add(method.getNameAsString());
                }
            }
        }
        return methods;
    }

    // ---------------------------------------------------------------------------
    // Pass 1: collect prototype-scoped type names
    // ---------------------------------------------------------------------------

    private Set<String> collectPrototypeTypes(JavaSources sources) {
        Set<String> prototypeTypes = new HashSet<>();
        for (JavaSources.JavaFile file : sources.files()) {
            CompilationUnit cu = file.compilationUnit();
            if (cu == null) {
                continue;
            }
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
            CompilationUnit cu,
            String relativePath,
            Set<String> prototypeTypes,
            Set<String> requiresNewMethods,
            List<Finding> findings) {
        detectHardcodedFilePaths(cu, relativePath, findings);
        detectRestTemplateNoTimeout(cu, relativePath, findings);
        detectWebFluxBlockingCalls(cu, relativePath, findings);
        detectUnboundedFindAll(cu, relativePath, findings);
        detectRestTemplateNewPerRequest(cu, relativePath, findings);
        detectJpaQueryNoPagination(cu, relativePath, findings);
        if (!requiresNewMethods.isEmpty()) {
            detectRequiresNewInLoop(cu, relativePath, requiresNewMethods, findings);
        }

        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            detectLombokDataOnEntity(cls, relativePath, findings);
            detectFilterComponentRegistrationLeak(cls, relativePath, findings);
            detectNonThreadSafeFormatterField(cls, relativePath, findings);
            detectEntityMissingId(cls, relativePath, findings);
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
            String callName = call.getNameAsString();
            // Paths.get(...) — legacy API
            boolean isPaths =
                    "get".equals(callName)
                            && call.getScope()
                                    .map(s -> s.toString().endsWith("Paths"))
                                    .orElse(false);
            // Path.of(...) — modern Java 11+ API, same semantics
            boolean isPathOf =
                    "of".equals(callName)
                            && call.getScope().map(s -> "Path".equals(s.toString())).orElse(false);
            if (!isPaths && !isPathOf) {
                continue;
            }
            String apiName = isPaths ? "Paths.get" : "Path.of";
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
                                                                + "\" passed to "
                                                                + apiName
                                                                + "() in "
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
                                                                + " directly to Paths.get() or"
                                                                + " Path.of(). Paths assembled via"
                                                                + " concatenation or variables are"
                                                                + " not detected.")
                                                .evidence(
                                                        apiName
                                                                + "(\""
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
        // @Component, @Service, @Repository are all meta-annotated with @Component and cause the
        // same auto-registration of the Filter into the global servlet chain.
        boolean hasSpringStereotype =
                cls.getAnnotations().stream()
                        .map(a -> simpleName(a.getNameAsString()))
                        .anyMatch(
                                n ->
                                        n.equals("Component")
                                                || n.equals("Service")
                                                || n.equals("Repository"));
        if (!hasSpringStereotype) {
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
                                        + " implements Filter and carries a Spring stereotype"
                                        + " annotation — Spring Boot will register it globally,"
                                        + " bypassing any SecurityFilterChain restrictions.")
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
                                "High confidence. Detects @Component, @Service, and @Repository"
                                        + " on Filter implementations. @Controller and"
                                        + " @RestController are intentionally excluded — those are"
                                        + " unlikely on a Filter class.")
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
            // Detect .block(), .blockFirst(), .blockLast() with 0 or 1 argument.
            // .block(Duration) with a timeout is still blocking and still dangerous in WebFlux.
            boolean isReactiveBlock =
                    BLOCKING_METHOD_NAMES.contains(name) && call.getArguments().size() <= 1;

            if (!isReactiveBlock && !isThreadSleep) {
                continue;
            }

            Integer line = call.getBegin().map(p -> p.line).orElse(null);
            String callDescription = isThreadSleep ? "Thread.sleep()" : "." + name + "()";
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_WEBFLUX_BLOCKING_CALL,
                                    FindingConfidence.MEDIUM)
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
    // Rule: SPRING_NON_THREAD_SAFE_FORMATTER_FIELD
    // ---------------------------------------------------------------------------

    private static final Set<String> NON_THREAD_SAFE_FORMATTER_TYPES =
            Set.of("SimpleDateFormat", "DateFormat", "NumberFormat", "DecimalFormat");

    private void detectNonThreadSafeFormatterField(
            ClassOrInterfaceDeclaration cls, String relativePath, List<Finding> findings) {
        boolean isSingleton =
                cls.getAnnotations().stream()
                        .anyMatch(
                                a ->
                                        SINGLETON_ANNOTATIONS.contains(
                                                simpleName(a.getNameAsString())));
        if (!isSingleton) {
            return;
        }
        for (FieldDeclaration field : cls.getFields()) {
            String fieldType = simpleName(field.getElementType().asString());
            if (!NON_THREAD_SAFE_FORMATTER_TYPES.contains(fieldType)) {
                continue;
            }
            Integer line = field.getBegin().map(p -> p.line).orElse(null);
            String fieldName =
                    field.getVariables().isEmpty()
                            ? fieldType
                            : field.getVariable(0).getNameAsString();
            String target = cls.getNameAsString() + "#" + fieldName;
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_NON_THREAD_SAFE_FORMATTER_FIELD,
                                    FindingConfidence.HIGH)
                            .shortMessage(
                                    fieldType
                                            + " field "
                                            + target
                                            + " in a Spring singleton is not thread-safe.")
                            .whyBadPractice(
                                    fieldType
                                            + " (and the other java.text formatters) is documented"
                                            + " as not thread-safe: it mutates internal Calendar"
                                            + " state during format()/parse(). A Spring"
                                            + " @Service/@Component is a singleton shared by every"
                                            + " request thread, so concurrent calls interleave and"
                                            + " corrupt that state.")
                            .possibleImpact(
                                    "Under concurrency the formatter silently returns wrong dates"
                                        + " or numbers, or throws NumberFormatException /"
                                        + " ArrayIndexOutOfBoundsException intermittently — bugs"
                                        + " that are very hard to reproduce.")
                            .recommendation(
                                    "Use java.time.format.DateTimeFormatter (immutable and"
                                        + " thread-safe) instead of SimpleDateFormat. If you must"
                                        + " use a java.text formatter, create a new instance per"
                                        + " call or store it in a ThreadLocal.")
                            .limitations(
                                    "High confidence — these types are well-documented as not"
                                            + " thread-safe and a singleton field is shared across"
                                            + " threads.")
                            .evidence(
                                    fieldType
                                            + " field declared in "
                                            + cls.getNameAsString()
                                            + " ("
                                            + relativePath
                                            + ").")
                            .source(relativePath, line)
                            .target(target)
                            .build());
        }
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_UNBOUNDED_FINDALL
    // ---------------------------------------------------------------------------

    private void detectUnboundedFindAll(
            CompilationUnit cu, String relativePath, List<Finding> findings) {
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            if (!"findAll".equals(call.getNameAsString()) || !call.getArguments().isEmpty()) {
                continue;
            }
            String receiver = receiverName(call.getScope().orElse(null));
            if (receiver == null) {
                continue;
            }
            String lower = receiver.toLowerCase(java.util.Locale.ROOT);
            if (!lower.contains("repository")
                    && !lower.contains("repo")
                    && !lower.contains("dao")) {
                continue;
            }
            Integer line = call.getBegin().map(p -> p.line).orElse(null);
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_UNBOUNDED_FINDALL, FindingConfidence.MEDIUM)
                            .shortMessage(
                                    receiver
                                            + ".findAll() with no Pageable in "
                                            + relativePath
                                            + " loads the whole table into memory.")
                            .whyBadPractice(
                                    "A no-argument findAll() issues SELECT * with no LIMIT. On a"
                                        + " table that grows over time this materialises every row"
                                        + " (and its associations) into the heap at once.")
                            .possibleImpact(
                                    "As the table grows the call causes long GC pauses and"
                                        + " eventually OutOfMemoryError, taking down the instance —"
                                        + " a failure that only appears once production data is"
                                        + " large enough.")
                            .recommendation(
                                    "Use the paginated overload findAll(Pageable) and stream/page"
                                        + " through results, or add a query with an explicit WHERE"
                                        + " and LIMIT. Reserve unbounded findAll() for small,"
                                        + " bounded reference tables only.")
                            .limitations(
                                    "Medium confidence — flagged by the receiver name containing"
                                        + " 'repository'/'repo'/'dao'. Small fixed lookup tables"
                                        + " are a legitimate exception.")
                            .evidence(receiver + ".findAll() found in " + relativePath + ".")
                            .source(relativePath, line)
                            .build());
        }
    }

    private static String receiverName(Expression scope) {
        if (scope instanceof NameExpr ne) {
            return ne.getNameAsString();
        }
        if (scope instanceof FieldAccessExpr fae) {
            return fae.getNameAsString();
        }
        return null;
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_ENTITY_MISSING_ID
    // ---------------------------------------------------------------------------

    private static final Set<String> ID_ANNOTATIONS = Set.of("Id", "EmbeddedId");

    private void detectEntityMissingId(
            ClassOrInterfaceDeclaration cls, String relativePath, List<Finding> findings) {
        boolean hasEntity =
                cls.getAnnotations().stream()
                        .anyMatch(a -> "Entity".equals(simpleName(a.getNameAsString())));
        if (!hasEntity) {
            return;
        }
        // An @IdClass on the type, or a superclass (possibly @MappedSuperclass), may supply the id.
        boolean hasIdClass =
                cls.getAnnotations().stream()
                        .anyMatch(a -> "IdClass".equals(simpleName(a.getNameAsString())));
        if (hasIdClass || !cls.getExtendedTypes().isEmpty()) {
            return;
        }
        boolean hasIdOnField =
                cls.getFields().stream()
                        .flatMap(f -> f.getAnnotations().stream())
                        .anyMatch(a -> ID_ANNOTATIONS.contains(simpleName(a.getNameAsString())));
        boolean hasIdOnMethod =
                cls.getMethods().stream()
                        .flatMap(m -> m.getAnnotations().stream())
                        .anyMatch(a -> ID_ANNOTATIONS.contains(simpleName(a.getNameAsString())));
        if (hasIdOnField || hasIdOnMethod) {
            return;
        }
        Integer line = cls.getBegin().map(p -> p.line).orElse(null);
        String target = cls.getNameAsString();
        findings.add(
                FindingFactory.builder(
                                FindingRules.SPRING_ENTITY_MISSING_ID, FindingConfidence.MEDIUM)
                        .shortMessage(
                                "@Entity "
                                        + target
                                        + " in "
                                        + relativePath
                                        + " declares no @Id — Hibernate mapping fails at startup.")
                        .whyBadPractice(
                                "Every JPA entity needs a persistent identity. Without @Id,"
                                        + " @EmbeddedId, or @IdClass, Hibernate cannot build the"
                                        + " persister for the entity and throws"
                                        + " '"
                                        + target
                                        + " has no identifier' while building the"
                                        + " EntityManagerFactory.")
                        .possibleImpact(
                                "The application context fails to start: the bean factory cannot"
                                        + " initialise the JPA EntityManagerFactory, so the whole"
                                        + " service is down.")
                        .recommendation(
                                "Add an identifier: a field annotated @Id (optionally with"
                                    + " @GeneratedValue), an @EmbeddedId for a composite key, or"
                                    + " @IdClass on the type. If identity is inherited, extend a"
                                    + " @MappedSuperclass that declares the @Id.")
                        .limitations(
                                "Medium confidence — the analyzer only sees this class. An @Id"
                                    + " provided by an interface default or a superclass it cannot"
                                    + " resolve would be a false positive (classes that extend a"
                                    + " parent are already skipped).")
                        .evidence(
                                "@Entity "
                                        + target
                                        + " with no @Id/@EmbeddedId/@IdClass found in "
                                        + relativePath
                                        + ".")
                        .source(relativePath, line)
                        .target(target)
                        .build());
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_RESTTEMPLATE_NEW_PER_REQUEST
    // ---------------------------------------------------------------------------

    private void detectRestTemplateNewPerRequest(
            CompilationUnit cu, String relativePath, List<Finding> findings) {
        // new RestTemplate(factory) created inside a regular method body. The no-arg
        // constructor is already covered by SPRING_REST_TEMPLATE_NO_TIMEOUT, so only the
        // arg-bearing form (which may have a timeout but is still recreated per call) is flagged.
        for (ObjectCreationExpr creation : cu.findAll(ObjectCreationExpr.class)) {
            if (!"RestTemplate".equals(simpleName(creation.getType().getNameAsString()))) {
                continue;
            }
            if (creation.getArguments().isEmpty()) {
                continue;
            }
            if (!isPerRequestInstantiation(creation)) {
                continue;
            }
            reportPerRequestClient(
                    relativePath,
                    creation.getBegin().map(p -> p.line).orElse(null),
                    "new RestTemplate(...)",
                    findings);
        }
        // RestClient.create(...) inside a regular method body.
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            boolean isRestClientCreate =
                    "create".equals(call.getNameAsString())
                            && call.getScope()
                                    .map(s -> "RestClient".equals(simpleName(s.toString())))
                                    .orElse(false);
            if (!isRestClientCreate || !isPerRequestInstantiation(call)) {
                continue;
            }
            reportPerRequestClient(
                    relativePath,
                    call.getBegin().map(p -> p.line).orElse(null),
                    "RestClient.create(...)",
                    findings);
        }
    }

    /**
     * True when {@code node} sits inside a method body (not a field initializer) of a
     * Spring-managed component, and that method is not a {@code @Bean} factory method. This is the
     * "created fresh on every call" shape, as opposed to a reused singleton bean.
     */
    private boolean isPerRequestInstantiation(Node node) {
        MethodDeclaration method = node.findAncestor(MethodDeclaration.class).orElse(null);
        if (method == null) {
            return false;
        }
        boolean isBeanMethod =
                method.getAnnotations().stream()
                        .anyMatch(a -> "Bean".equals(simpleName(a.getNameAsString())));
        if (isBeanMethod) {
            return false;
        }
        ClassOrInterfaceDeclaration cls =
                node.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
        return cls != null
                && cls.getAnnotations().stream()
                        .anyMatch(
                                a ->
                                        SINGLETON_ANNOTATIONS.contains(
                                                simpleName(a.getNameAsString())));
    }

    private void reportPerRequestClient(
            String relativePath, Integer line, String shape, List<Finding> findings) {
        findings.add(
                FindingFactory.builder(
                                FindingRules.SPRING_RESTTEMPLATE_NEW_PER_REQUEST,
                                FindingConfidence.MEDIUM)
                        .shortMessage(
                                shape
                                        + " is created inside a method in "
                                        + relativePath
                                        + " — a new HTTP client per call.")
                        .whyBadPractice(
                                "Instantiating an HTTP client inside a request-handling method"
                                    + " builds a fresh client — and its underlying connection pool"
                                    + " and TLS context — on every invocation. None of that is"
                                    + " reused across calls, and the auto-configured Micrometer"
                                    + " metrics and tracing instrumentation are bypassed.")
                        .possibleImpact(
                                "Per-call connection setup adds latency, leaks sockets under load,"
                                        + " and defeats connection keep-alive. Throughput collapses"
                                        + " when the endpoint is hot.")
                        .recommendation(
                                "Create the client once as a singleton bean (or inject the"
                                    + " auto-configured RestTemplateBuilder / RestClient.Builder)"
                                    + " and reuse it. Configure timeouts and connection pooling on"
                                    + " that single instance.")
                        .limitations(
                                "Medium confidence — flagged because the client is built inside a"
                                        + " non-@Bean method of a Spring component. A short-lived"
                                        + " client intentionally scoped to one operation may be"
                                        + " acceptable.")
                        .evidence(
                                shape + " inside a component method found in " + relativePath + ".")
                        .source(relativePath, line)
                        .build());
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_JPA_QUERY_NO_PAGINATION
    // ---------------------------------------------------------------------------

    private static final Set<String> COLLECTION_RETURN_TYPES =
            Set.of("List", "Collection", "Set", "Iterable");

    private void detectJpaQueryNoPagination(
            CompilationUnit cu, String relativePath, List<Finding> findings) {
        for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
            boolean hasQuery =
                    method.getAnnotations().stream()
                            .anyMatch(a -> "Query".equals(simpleName(a.getNameAsString())));
            if (!hasQuery) {
                continue;
            }
            if (!method.getType().isClassOrInterfaceType()) {
                continue;
            }
            String returnType = method.getType().asClassOrInterfaceType().getNameAsString();
            if (!COLLECTION_RETURN_TYPES.contains(returnType)) {
                continue;
            }
            boolean hasPageable =
                    method.getParameters().stream()
                            .map(Parameter::getType)
                            .anyMatch(t -> "Pageable".equals(simpleName(t.asString())));
            if (hasPageable) {
                continue;
            }
            Integer line = method.getBegin().map(p -> p.line).orElse(null);
            String target = method.getNameAsString();
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_JPA_QUERY_NO_PAGINATION,
                                    FindingConfidence.MEDIUM)
                            .shortMessage(
                                    "@Query method "
                                            + target
                                            + " in "
                                            + relativePath
                                            + " returns "
                                            + returnType
                                            + " with no Pageable — the query has no LIMIT.")
                            .whyBadPractice(
                                    "A @Query that returns a collection and takes no Pageable runs"
                                        + " without a LIMIT clause. Every matching row is fetched"
                                        + " and materialised into the heap, regardless of how large"
                                        + " the result set grows over time.")
                            .possibleImpact(
                                    "As the underlying data grows, the query causes rising memory"
                                        + " use, long GC pauses, and eventually OutOfMemoryError —"
                                        + " problems that only surface once production data is"
                                        + " large.")
                            .recommendation(
                                    "Add a Pageable parameter and return Page<T> or Slice<T>, or"
                                        + " constrain the query with an explicit WHERE/LIMIT."
                                        + " Reserve unbounded collection queries for small bounded"
                                        + " reference data.")
                            .limitations(
                                    "Medium confidence — some queries are intentionally bounded by"
                                        + " their WHERE clause to a small result set, in which case"
                                        + " pagination is unnecessary.")
                            .evidence(
                                    "@Query method "
                                            + target
                                            + " returning "
                                            + returnType
                                            + " without a Pageable parameter found in "
                                            + relativePath
                                            + ".")
                            .source(relativePath, line)
                            .target(target)
                            .build());
        }
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_REQUIRES_NEW_IN_LOOP
    // ---------------------------------------------------------------------------

    private void detectRequiresNewInLoop(
            CompilationUnit cu,
            String relativePath,
            Set<String> requiresNewMethods,
            List<Finding> findings) {
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            if (!requiresNewMethods.contains(call.getNameAsString())) {
                continue;
            }
            boolean inLoop =
                    call.findAncestor(ForStmt.class).isPresent()
                            || call.findAncestor(ForEachStmt.class).isPresent()
                            || call.findAncestor(WhileStmt.class).isPresent()
                            || call.findAncestor(DoStmt.class).isPresent();
            if (!inLoop) {
                continue;
            }
            Integer line = call.getBegin().map(p -> p.line).orElse(null);
            String target = call.getNameAsString();
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_REQUIRES_NEW_IN_LOOP, FindingConfidence.LOW)
                            .shortMessage(
                                    target
                                            + "(...) is annotated @Transactional(REQUIRES_NEW) and"
                                            + " is called inside a loop in "
                                            + relativePath
                                            + ".")
                            .whyBadPractice(
                                    "Propagation.REQUIRES_NEW suspends the current transaction and"
                                        + " starts a fresh one for the call, which borrows a second"
                                        + " connection from the pool while the outer transaction"
                                        + " still holds its own. Doing this once per loop iteration"
                                        + " multiplies connection pressure by the iteration count.")
                            .possibleImpact(
                                    "Large loops can exhaust the connection pool and deadlock"
                                            + " (every thread holds one connection and waits for a"
                                            + " second), or simply run far slower than a single"
                                            + " transaction would.")
                            .recommendation(
                                    "Move the loop inside a single transaction, or batch the work"
                                        + " so a new transaction is not opened per element. Reserve"
                                        + " REQUIRES_NEW for the few cases that genuinely need an"
                                        + " independent commit, and call them outside hot loops.")
                            .limitations(
                                    "Low confidence — the match is by method name across the"
                                            + " scanned sources, so an unrelated method sharing the"
                                            + " name could be flagged. Confirm the callee is the"
                                            + " REQUIRES_NEW method.")
                            .evidence(
                                    "Call to REQUIRES_NEW method "
                                            + target
                                            + "(...) found inside a loop in "
                                            + relativePath
                                            + ".")
                            .source(relativePath, line)
                            .target(target)
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
