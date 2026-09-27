package com.robbanhoglund.springbootanalyzer.analyzer;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildInfo;
import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingConfidence;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingFactory;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingRules;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingSeverity;
import com.robbanhoglund.springbootanalyzer.analyzer.source.JavaSources;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Detects bean-definition mistakes that the Spring container rejects or silently ignores.
 *
 * <p>Rules covered:
 *
 * <ul>
 *   <li>{@link FindingRules#SPRING_ENABLE_ANNOTATION_ON_NON_BEAN_CLASS} — an {@code @Enable...}
 *       annotation on a class Spring never registers.
 *   <li>{@link FindingRules#SPRING_BEAN_METHOD_INVALID} — {@code @Bean} methods or
 *       {@code @Configuration} classes that fail configuration-class validation.
 *   <li>{@link FindingRules#SPRING_CONFIGURATION_PROPERTIES_INVALID_PREFIX} — a
 *       {@code @ConfigurationProperties} prefix that is not canonical.
 *   <li>{@link FindingRules#SPRING_CONFIGURATION_PROPERTIES_BEAN_CONSTRUCTOR_BINDING} — a
 *       constructor-bound properties class that is also a component.
 *   <li>{@link FindingRules#SPRING_SCOPED_BEAN_WITHOUT_PROXY} — a request- or session-scoped
 *       bean without a scoped proxy injected into a singleton.
 *   <li>{@link FindingRules#SPRING_BFPP_BEAN_METHOD_NOT_STATIC} — a non-static {@code @Bean}
 *       method returning a {@code BeanFactoryPostProcessor} in a class that uses injection.
 * </ul>
 */
@Component
public class ContainerPracticeFindingAnalyzer {

    /** Annotations that make a class a bean Spring finds by component scanning. */
    private static final Set<String> STEREOTYPES =
            Set.of(
                    "Component",
                    "Service",
                    "Repository",
                    "Controller",
                    "RestController",
                    "ControllerAdvice",
                    "RestControllerAdvice",
                    "Configuration",
                    "SpringBootApplication",
                    "SpringBootConfiguration",
                    "AutoConfiguration",
                    "TestConfiguration");

    private static final Set<String> SECURITY_ENABLERS =
            Set.of(
                    "EnableWebSecurity",
                    "EnableWebFluxSecurity",
                    "EnableMethodSecurity",
                    "EnableGlobalMethodSecurity",
                    "EnableReactiveMethodSecurity");

    private static final Set<String> CONFIGURATION_CLASSES =
            Set.of(
                    "Configuration",
                    "SpringBootApplication",
                    "SpringBootConfiguration",
                    "AutoConfiguration",
                    "TestConfiguration");

    private static final Set<String> BFPP_TYPES =
            Set.of(
                    "PropertySourcesPlaceholderConfigurer",
                    "PropertyPlaceholderConfigurer",
                    "BeanFactoryPostProcessor",
                    "BeanDefinitionRegistryPostProcessor",
                    "CustomScopeConfigurer",
                    "CustomEditorConfigurer",
                    "CustomAutowireConfigurer",
                    "MapperScannerConfigurer");

    private static final Set<String> INJECTION_ANNOTATIONS =
            Set.of("Autowired", "Value", "Inject", "Resource");

    /** Calls that register the class literals they are passed as configuration classes. */
    private static final Set<String> REGISTRATION_METHODS =
            Set.of("run", "sources", "main", "register", "child", "parent", "sibling");

    private static final Set<String> VALUE_PARAMETER_TYPES =
            Set.of(
                    "String",
                    "int",
                    "long",
                    "short",
                    "byte",
                    "double",
                    "float",
                    "char",
                    "boolean",
                    "Integer",
                    "Long",
                    "Short",
                    "Byte",
                    "Double",
                    "Float",
                    "Character",
                    "Boolean",
                    "Duration",
                    "DataSize",
                    "Period",
                    "List",
                    "Set",
                    "Map",
                    "Collection",
                    "URI",
                    "URL",
                    "Path",
                    "File",
                    "Pattern",
                    "Charset",
                    "Locale",
                    "InetAddress",
                    "Class",
                    "Resource",
                    "LocalDate",
                    "LocalTime",
                    "LocalDateTime",
                    "Instant",
                    "ZoneId",
                    "BigDecimal",
                    "BigInteger",
                    "UUID");

    private static final Pattern CANONICAL_PREFIX =
            Pattern.compile(
                    "[a-z0-9][a-z0-9-]*(?:\\[[0-9a-z-]+])*(?:\\.[a-z0-9][a-z0-9-]*(?:\\[[0-9a-z-]+])*)*");

    private static final Set<String> WEB_SCOPES =
            Set.of("request", "session", "application", "websocket");

    public List<Finding> analyze(JavaSources sources, BuildInfo buildInfo) {
        List<Finding> findings = new ArrayList<>();
        String bootVersion = buildInfo == null ? null : buildInfo.springBootVersion();
        ProjectIndex index = ProjectIndex.of(sources);
        for (JavaSources.JavaFile file : sources.files()) {
            CompilationUnit cu = file.compilationUnit();
            if (cu == null) {
                continue;
            }
            String relativePath = file.relativePath();
            for (TypeDeclaration<?> type : cu.getTypes()) {
                detectEnableAnnotationOnNonBean(type, cu, relativePath, index, findings);
            }
            for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (cls.isInterface()) {
                    continue;
                }
                detectInvalidBeanMethods(cls, relativePath, bootVersion, findings);
                detectBfppBeanMethodNotStatic(cls, relativePath, index, findings);
                detectScopedBeanInjection(cls, relativePath, index, findings);
            }
            for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
                detectConfigurationPropertiesPrefix(type, relativePath, findings);
                detectConfigurationPropertiesConstructorBinding(type, relativePath, findings);
            }
            for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
                if (hasAnnotation(method.getAnnotations(), "Bean")) {
                    method.getAnnotationByName("ConfigurationProperties")
                            .ifPresent(
                                    annotation ->
                                            checkPrefix(
                                                    annotation,
                                                    enclosingName(method)
                                                            + "#"
                                                            + method.getNameAsString(),
                                                    relativePath,
                                                    method,
                                                    findings));
                }
            }
        }
        return findings;
    }

    // ---------------------------------------------------------------------------
    // Project index
    // ---------------------------------------------------------------------------

    /** Cross-file facts: custom stereotypes, explicit registrations and scoped beans. */
    private record ProjectIndex(
            Set<String> stereotypes,
            Set<String> explicitlyRegistered,
            Map<String, String> unproxiedScopedBeans,
            Set<String> bfppClasses) {

        static ProjectIndex of(JavaSources sources) {
            Set<String> stereotypes = new LinkedHashSet<>(STEREOTYPES);
            Set<String> registered = new LinkedHashSet<>();
            Map<String, String> scoped = new LinkedHashMap<>();
            Set<String> bfppClasses = new LinkedHashSet<>();
            // Custom annotations meta-annotated with a stereotype act as stereotypes themselves.
            boolean grew = true;
            for (int round = 0; grew && round < 5; round++) {
                grew = false;
                for (JavaSources.JavaFile file : sources.files()) {
                    if (file.compilationUnit() == null) {
                        continue;
                    }
                    for (AnnotationDeclaration annotation :
                            file.compilationUnit().findAll(AnnotationDeclaration.class)) {
                        if (annotation.getAnnotations().stream()
                                .anyMatch(
                                        a ->
                                                stereotypes.contains(
                                                        simpleName(a.getNameAsString())))) {
                            grew |= stereotypes.add(annotation.getNameAsString());
                        }
                    }
                }
            }
            for (JavaSources.JavaFile file : sources.files()) {
                CompilationUnit cu = file.compilationUnit();
                if (cu == null) {
                    continue;
                }
                // @Import(X.class), SpringApplication.run(X.class), context.register(X.class),
                // new SpringApplicationBuilder(X.class).child(Y.class)
                for (AnnotationExpr annotation : cu.findAll(AnnotationExpr.class)) {
                    if ("Import".equals(simpleName(annotation.getNameAsString()))) {
                        annotation
                                .findAll(ClassExpr.class)
                                .forEach(c -> registered.add(simpleName(c.getType().asString())));
                    }
                }
                for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
                    if (REGISTRATION_METHODS.contains(call.getNameAsString())) {
                        call.getArguments().stream()
                                .filter(Expression::isClassExpr)
                                .forEach(
                                        c ->
                                                registered.add(
                                                        simpleName(
                                                                c.asClassExpr()
                                                                        .getType()
                                                                        .asString())));
                    }
                }
                for (ObjectCreationExpr creation : cu.findAll(ObjectCreationExpr.class)) {
                    if ("SpringApplicationBuilder"
                                    .equals(simpleName(creation.getType().getNameAsString()))
                            || "SpringApplication"
                                    .equals(simpleName(creation.getType().getNameAsString()))) {
                        creation.getArguments().stream()
                                .filter(Expression::isClassExpr)
                                .forEach(
                                        c ->
                                                registered.add(
                                                        simpleName(
                                                                c.asClassExpr()
                                                                        .getType()
                                                                        .asString())));
                    }
                }
                for (ClassOrInterfaceDeclaration cls :
                        cu.findAll(ClassOrInterfaceDeclaration.class)) {
                    String scope = unproxiedWebScope(cls.getAnnotations());
                    if (scope != null
                            && cls.getAnnotations().stream()
                                    .anyMatch(
                                            a ->
                                                    stereotypes.contains(
                                                            simpleName(a.getNameAsString())))) {
                        scoped.put(cls.getNameAsString(), scope);
                    }
                    boolean implementsBfpp =
                            cls.getImplementedTypes().stream()
                                    .anyMatch(
                                            t ->
                                                    "BeanFactoryPostProcessor"
                                                                    .equals(t.getNameAsString())
                                                            || "BeanDefinitionRegistryPostProcessor"
                                                                    .equals(t.getNameAsString()));
                    if (implementsBfpp) {
                        bfppClasses.add(cls.getNameAsString());
                    }
                }
            }
            registered.addAll(ComponentScanModel.autoConfigurationSimpleNames(sources));
            return new ProjectIndex(
                    Set.copyOf(stereotypes),
                    Set.copyOf(registered),
                    Map.copyOf(scoped),
                    Set.copyOf(bfppClasses));
        }

        boolean isStereotype(List<AnnotationExpr> annotations) {
            return annotations.stream()
                    .anyMatch(a -> stereotypes.contains(simpleName(a.getNameAsString())));
        }
    }

    /**
     * The web scope of a class declared with {@code @Scope("request")} and friends when it has no
     * scoped proxy; {@code @RequestScope}/{@code @SessionScope} default to a class proxy.
     */
    private static String unproxiedWebScope(List<AnnotationExpr> annotations) {
        for (AnnotationExpr annotation : annotations) {
            String name = simpleName(annotation.getNameAsString());
            String text = annotation.toString();
            boolean explicitNoProxy =
                    text.contains("ScopedProxyMode.NO") || text.contains("ScopedProxyMode.DEFAULT");
            boolean proxied = text.contains("proxyMode") && !explicitNoProxy;
            if (Set.of("RequestScope", "SessionScope", "ApplicationScope").contains(name)) {
                if (explicitNoProxy) {
                    return name.replace("Scope", "").toLowerCase(Locale.ROOT);
                }
                continue;
            }
            if (!"Scope".equals(name) || proxied) {
                continue;
            }
            String lower = text.toLowerCase(Locale.ROOT);
            for (String scope : WEB_SCOPES) {
                if (lower.contains("\"" + scope + "\"") || lower.contains("scope_" + scope)) {
                    return scope;
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_ENABLE_ANNOTATION_ON_NON_BEAN_CLASS
    // ---------------------------------------------------------------------------

    private void detectEnableAnnotationOnNonBean(
            TypeDeclaration<?> type,
            CompilationUnit cu,
            String relativePath,
            ProjectIndex index,
            List<Finding> findings) {
        if (!(type instanceof ClassOrInterfaceDeclaration cls)
                || cls.isInterface()
                || cls.isAbstract()) {
            return;
        }
        if (index.isStereotype(cls.getAnnotations())
                || index.explicitlyRegistered().contains(cls.getNameAsString())) {
            return;
        }
        for (AnnotationExpr annotation : cls.getAnnotations()) {
            String name = simpleName(annotation.getNameAsString());
            if (!name.startsWith("Enable") || !isSpringAnnotation(annotation, cu)) {
                continue;
            }
            boolean security = SECURITY_ENABLERS.contains(name);
            FindingFactory.Builder builder =
                    FindingFactory.builder(
                                    FindingRules.SPRING_ENABLE_ANNOTATION_ON_NON_BEAN_CLASS,
                                    FindingConfidence.HIGH)
                            .shortMessage(
                                    "@"
                                            + name
                                            + " on "
                                            + cls.getNameAsString()
                                            + " is ignored — the class is not a @Configuration or"
                                            + " component, so Spring never registers it.")
                            .whyBadPractice(
                                    "Spring processes @Enable... annotations only on classes it"
                                        + " registers: @Configuration classes and other components"
                                        + " found by component scanning, @Import-ed classes and"
                                        + " auto-configurations. Since Spring Security 6,"
                                        + " @EnableWebSecurity and @EnableMethodSecurity no longer"
                                        + " include @Configuration, so a class that carries only"
                                        + " them is never registered.")
                            .possibleImpact(
                                    security
                                            ? "The security configuration in this class does not"
                                                    + " apply: its filter chain and method-security"
                                                    + " rules are never created, so the annotations"
                                                    + " that look protected are not enforced."
                                            : "The feature the annotation enables stays off, and"
                                                    + " rules that check whether it is enabled see"
                                                    + " the annotation and stay silent.")
                            .recommendation(
                                    "Annotate " + cls.getNameAsString() + " with @Configuration.")
                            .evidence(
                                    cls.getNameAsString()
                                            + " in "
                                            + relativePath
                                            + " carries @"
                                            + name
                                            + " but no stereotype annotation, and nothing imports"
                                            + " it.")
                            .limitations(
                                    "Registrations made programmatically or through another module"
                                            + " are not visible.")
                            .source(relativePath, cls.getBegin().map(p -> p.line).orElse(null))
                            .target(cls.getNameAsString());
            if (!security) {
                builder.severity(FindingSeverity.WARNING);
            }
            findings.add(builder.build());
            return;
        }
    }

    private static boolean isSpringAnnotation(AnnotationExpr annotation, CompilationUnit cu) {
        String name = annotation.getNameAsString();
        if (name.contains(".")) {
            return name.startsWith("org.springframework.");
        }
        return cu.getImports().stream()
                .anyMatch(
                        importDeclaration -> {
                            String imported = importDeclaration.getNameAsString();
                            return imported.startsWith("org.springframework.")
                                    && (importDeclaration.isAsterisk()
                                            || imported.endsWith("." + name));
                        });
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_BEAN_METHOD_INVALID
    // ---------------------------------------------------------------------------

    private void detectInvalidBeanMethods(
            ClassOrInterfaceDeclaration cls,
            String relativePath,
            String bootVersion,
            List<Finding> findings) {
        AnnotationExpr configuration =
                cls.getAnnotations().stream()
                        .filter(
                                a ->
                                        CONFIGURATION_CLASSES.contains(
                                                simpleName(a.getNameAsString())))
                        .findFirst()
                        .orElse(null);
        List<MethodDeclaration> beanMethods =
                cls.getMethods().stream()
                        .filter(method -> hasAnnotation(method.getAnnotations(), "Bean"))
                        .toList();
        if (beanMethods.isEmpty()) {
            return;
        }
        boolean fullConfiguration = configuration != null;
        boolean proxied =
                fullConfiguration
                        && !"AutoConfiguration".equals(simpleName(configuration.getNameAsString()))
                        && !configuration
                                .toString()
                                .replaceAll("\\s", "")
                                .contains("proxyBeanMethods=false");
        // Framework 6.2 (Spring Boot 3.4) added the void and @Autowired checks.
        boolean recentChecks =
                bootVersion == null || SpringBootVersions.isAtLeast(bootVersion, 3, 4);
        String owner = cls.getNameAsString();
        if (proxied
                && cls.isFinal()
                && beanMethods.stream().anyMatch(method -> !method.isStatic())) {
            addInvalidBean(
                    owner,
                    "@Configuration class " + owner + " is final",
                    "@Configuration classes are subclassed with CGLIB to route calls between @Bean"
                        + " methods through the container; Spring reports \"@Configuration class"
                        + " may not be final\".",
                    "Remove final, or declare @Configuration(proxyBeanMethods = false) when the"
                            + " @Bean methods do not call each other.",
                    relativePath,
                    cls,
                    findings);
        }
        Map<String, Integer> names = new LinkedHashMap<>();
        for (MethodDeclaration method : beanMethods) {
            String target = owner + "#" + method.getNameAsString();
            names.merge(method.getNameAsString(), 1, Integer::sum);
            if (proxied && !method.isStatic() && (method.isPrivate() || method.isFinal())) {
                addInvalidBean(
                        target,
                        "@Bean method "
                                + target
                                + " is "
                                + (method.isPrivate() ? "private" : "final"),
                        "Spring overrides @Bean methods in a CGLIB subclass of the configuration"
                                + " and reports \"@Bean method must not be private or final\".",
                        "Make the method package-private, protected or public and non-final, or"
                                + " declare it static.",
                        relativePath,
                        method,
                        findings);
            }
            if (recentChecks && method.getType().isVoidType()) {
                addInvalidBean(
                        target,
                        "@Bean method " + target + " returns void",
                        "A @Bean method must return the bean it creates; Spring 6.2+ reports"
                                + " \"@Bean method must not be declared as void\" — often an init"
                                + " method that was meant to be @PostConstruct.",
                        "Return the bean, or turn the method into an initialization hook"
                            + " (@PostConstruct, an ApplicationRunner) if it only performs work.",
                        relativePath,
                        method,
                        findings);
            }
            if (recentChecks && hasAnnotation(method.getAnnotations(), "Autowired")) {
                addInvalidBean(
                        target,
                        "@Bean method " + target + " is also annotated @Autowired",
                        "@Bean method parameters are always autowired, so @Autowired on the method"
                                + " makes no sense; Spring 6.2+ reports \"@Bean method must not be"
                                + " declared as autowired\".",
                        "Remove @Autowired from the method.",
                        relativePath,
                        method,
                        findings);
            }
        }
        boolean uniqueMethodsEnforced =
                fullConfiguration
                        && !configuration
                                .toString()
                                .replaceAll("\\s", "")
                                .contains("enforceUniqueMethods=false")
                        && (bootVersion == null || SpringBootVersions.isAtLeast(bootVersion, 3, 0));
        if (uniqueMethodsEnforced) {
            for (Map.Entry<String, Integer> entry : names.entrySet()) {
                if (entry.getValue() < 2) {
                    continue;
                }
                MethodDeclaration second =
                        beanMethods.stream()
                                .filter(method -> method.getNameAsString().equals(entry.getKey()))
                                .skip(1)
                                .findFirst()
                                .orElseThrow();
                addInvalidBean(
                        owner + "#" + entry.getKey(),
                        owner
                                + " declares "
                                + entry.getValue()
                                + " @Bean methods named "
                                + entry.getKey(),
                        "Overloaded @Bean methods define the same bean name; Spring 6 rejects them"
                            + " in a @Configuration class (\"@Configuration class contains"
                            + " overloaded @Bean methods\") unless enforceUniqueMethods = false.",
                        "Give each @Bean method its own name, or merge them into one factory"
                                + " method.",
                        relativePath,
                        second,
                        findings);
            }
        }
    }

    private void addInvalidBean(
            String target,
            String problem,
            String why,
            String recommendation,
            String relativePath,
            Node node,
            List<Finding> findings) {
        findings.add(
                FindingFactory.builder(
                                FindingRules.SPRING_BEAN_METHOD_INVALID, FindingConfidence.HIGH)
                        .shortMessage(problem + " — startup fails.")
                        .whyBadPractice(why)
                        .possibleImpact(
                                "Configuration-class validation fails and the application context"
                                        + " does not start.")
                        .recommendation(recommendation)
                        .evidence(problem + ".")
                        .source(relativePath, node.getBegin().map(p -> p.line).orElse(null))
                        .target(target)
                        .build());
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_BFPP_BEAN_METHOD_NOT_STATIC
    // ---------------------------------------------------------------------------

    private void detectBfppBeanMethodNotStatic(
            ClassOrInterfaceDeclaration cls,
            String relativePath,
            ProjectIndex index,
            List<Finding> findings) {
        boolean usesInjection =
                cls.getFields().stream()
                                .anyMatch(
                                        field ->
                                                hasAnyAnnotation(
                                                        field.getAnnotations(),
                                                        INJECTION_ANNOTATIONS))
                        || cls.getMethods().stream()
                                .anyMatch(
                                        method ->
                                                hasAnyAnnotation(
                                                        method.getAnnotations(),
                                                        Set.of(
                                                                "PostConstruct",
                                                                "PreDestroy",
                                                                "Autowired")));
        if (!usesInjection) {
            return;
        }
        for (MethodDeclaration method : cls.getMethods()) {
            String returnType = simpleName(method.getType().asString());
            if (!hasAnnotation(method.getAnnotations(), "Bean")
                    || method.isStatic()
                    || !(BFPP_TYPES.contains(returnType)
                            || index.bfppClasses().contains(returnType))) {
                continue;
            }
            String target = cls.getNameAsString() + "#" + method.getNameAsString();
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_BFPP_BEAN_METHOD_NOT_STATIC,
                                    FindingConfidence.HIGH)
                            .shortMessage(
                                    "@Bean method "
                                            + target
                                            + " returns a "
                                            + returnType
                                            + " but is not static — @Autowired, @Value and"
                                            + " @PostConstruct in "
                                            + cls.getNameAsString()
                                            + " stop working.")
                            .whyBadPractice(
                                    "Bean factory post-processors run before any other bean is"
                                        + " created. To call a non-static @Bean method Spring must"
                                        + " instantiate the configuration class that early, before"
                                        + " the annotation processors exist, so its injection"
                                        + " points are never processed. Spring only logs a warning"
                                        + " (\"@Bean method ... is non-static and returns an object"
                                        + " assignable to Spring's BeanFactoryPostProcessor"
                                        + " interface\").")
                            .possibleImpact(
                                    "Fields injected into the configuration class stay null and its"
                                            + " lifecycle callbacks never run, which usually shows"
                                            + " up as NullPointerExceptions or missing"
                                            + " configuration.")
                            .recommendation("Declare the @Bean method static.")
                            .evidence(
                                    target
                                            + " is a non-static @Bean method returning "
                                            + returnType
                                            + ", and "
                                            + cls.getNameAsString()
                                            + " uses field injection or lifecycle callbacks.")
                            .source(relativePath, method.getBegin().map(p -> p.line).orElse(null))
                            .target(target)
                            .build());
        }
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_SCOPED_BEAN_WITHOUT_PROXY
    // ---------------------------------------------------------------------------

    private void detectScopedBeanInjection(
            ClassOrInterfaceDeclaration cls,
            String relativePath,
            ProjectIndex index,
            List<Finding> findings) {
        if (index.unproxiedScopedBeans().isEmpty()
                || !index.isStereotype(cls.getAnnotations())
                || unproxiedWebScope(cls.getAnnotations()) != null
                || cls.getAnnotations().stream()
                        .anyMatch(
                                a ->
                                        Set.of("Scope", "RequestScope", "SessionScope")
                                                .contains(simpleName(a.getNameAsString())))) {
            return;
        }
        for (FieldDeclaration field : cls.getFields()) {
            if (!hasAnyAnnotation(field.getAnnotations(), Set.of("Autowired", "Inject", "Resource"))
                    || hasAnnotation(field.getAnnotations(), "Lazy")) {
                continue;
            }
            for (VariableDeclarator variable : field.getVariables()) {
                reportScopedInjection(
                        cls,
                        variable.getTypeAsString(),
                        variable.getNameAsString(),
                        field,
                        relativePath,
                        index,
                        findings);
            }
        }
        List<ConstructorDeclaration> constructors = cls.getConstructors();
        ConstructorDeclaration injected =
                constructors.size() == 1
                        ? constructors.get(0)
                        : constructors.stream()
                                .filter(c -> hasAnnotation(c.getAnnotations(), "Autowired"))
                                .findFirst()
                                .orElse(null);
        if (injected != null) {
            for (Parameter parameter : injected.getParameters()) {
                if (!hasAnnotation(parameter.getAnnotations(), "Lazy")) {
                    reportScopedInjection(
                            cls,
                            parameter.getTypeAsString(),
                            parameter.getNameAsString(),
                            parameter,
                            relativePath,
                            index,
                            findings);
                }
            }
        }
    }

    private void reportScopedInjection(
            ClassOrInterfaceDeclaration cls,
            String declaredType,
            String name,
            Node node,
            String relativePath,
            ProjectIndex index,
            List<Finding> findings) {
        String type = simpleName(declaredType);
        String scope = index.unproxiedScopedBeans().get(type);
        if (scope == null) {
            return;
        }
        String target = cls.getNameAsString() + "." + name;
        findings.add(
                FindingFactory.builder(
                                FindingRules.SPRING_SCOPED_BEAN_WITHOUT_PROXY,
                                FindingConfidence.HIGH)
                        .shortMessage(
                                type
                                        + " is "
                                        + scope
                                        + "-scoped without a scoped proxy and is injected into the"
                                        + " singleton "
                                        + cls.getNameAsString()
                                        + " — startup fails.")
                        .whyBadPractice(
                                "The singleton is created at startup, outside any "
                                        + scope
                                        + ", and Spring resolves "
                                        + type
                                        + " right then. Without proxyMode it throws"
                                        + " ScopeNotActiveException (\"Scope '"
                                        + scope
                                        + "' is not active for the current thread\").")
                        .possibleImpact("The application context fails to start.")
                        .recommendation(
                                "Declare the scope with a proxy — @"
                                        + (scope.equals("session")
                                                ? "SessionScope"
                                                : "RequestScope")
                                        + " or @Scope(value = \""
                                        + scope
                                        + "\", proxyMode = ScopedProxyMode.TARGET_CLASS) — or"
                                        + " inject an ObjectProvider<"
                                        + type
                                        + "> and resolve it per call.")
                        .evidence(
                                target
                                        + " injects "
                                        + type
                                        + ", which is annotated @Scope(\""
                                        + scope
                                        + "\") without proxyMode.")
                        .limitations(
                                "Only direct field and constructor injection of the scoped class"
                                        + " itself is followed; injection by interface type is not"
                                        + " resolved.")
                        .source(relativePath, node.getBegin().map(p -> p.line).orElse(null))
                        .target(target)
                        .build());
    }

    // ---------------------------------------------------------------------------
    // Rules: @ConfigurationProperties prefix and constructor binding
    // ---------------------------------------------------------------------------

    private void detectConfigurationPropertiesPrefix(
            TypeDeclaration<?> type, String relativePath, List<Finding> findings) {
        type.getAnnotationByName("ConfigurationProperties")
                .ifPresent(
                        annotation ->
                                checkPrefix(
                                        annotation,
                                        type.getNameAsString(),
                                        relativePath,
                                        type,
                                        findings));
    }

    private void checkPrefix(
            AnnotationExpr annotation,
            String target,
            String relativePath,
            Node node,
            List<Finding> findings) {
        String prefix = prefixOf(annotation);
        if (prefix == null || prefix.isEmpty() || CANONICAL_PREFIX.matcher(prefix).matches()) {
            return;
        }
        String canonical =
                prefix.replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                        .replace('_', '-')
                        .toLowerCase(Locale.ROOT);
        findings.add(
                FindingFactory.builder(
                                FindingRules.SPRING_CONFIGURATION_PROPERTIES_INVALID_PREFIX,
                                FindingConfidence.HIGH)
                        .shortMessage(
                                "@ConfigurationProperties(\""
                                        + prefix
                                        + "\") on "
                                        + target
                                        + " is not a canonical property name — startup fails.")
                        .whyBadPractice(
                                "Spring Boot binds the class under the prefix as a canonical"
                                    + " configuration property name: lowercase letters, digits and"
                                    + " dashes, separated by dots. Anything else throws"
                                    + " InvalidConfigurationPropertyNameException (\"Canonical"
                                    + " names should be kebab-case ('-' separated), lowercase"
                                    + " alpha-numeric characters and must start with a letter\").")
                        .possibleImpact("The application context fails to start.")
                        .recommendation(
                                "Use \""
                                        + canonical
                                        + "\"; relaxed binding still matches camelCase, underscore"
                                        + " and upper-case variants in the configuration files.")
                        .evidence("Prefix \"" + prefix + "\" on " + target + ".")
                        .source(relativePath, node.getBegin().map(p -> p.line).orElse(null))
                        .target(target)
                        .build());
    }

    private static String prefixOf(AnnotationExpr annotation) {
        Expression value = null;
        if (annotation.isSingleMemberAnnotationExpr()) {
            value = annotation.asSingleMemberAnnotationExpr().getMemberValue();
        } else if (annotation.isNormalAnnotationExpr()) {
            value =
                    annotation.asNormalAnnotationExpr().getPairs().stream()
                            .filter(
                                    pair ->
                                            "prefix".equals(pair.getNameAsString())
                                                    || "value".equals(pair.getNameAsString()))
                            .map(pair -> pair.getValue())
                            .findFirst()
                            .orElse(null);
        }
        return value != null && value.isStringLiteralExpr()
                ? value.asStringLiteralExpr().asString()
                : null;
    }

    private void detectConfigurationPropertiesConstructorBinding(
            TypeDeclaration<?> type, String relativePath, List<Finding> findings) {
        if (type.getAnnotationByName("ConfigurationProperties").isEmpty()
                || type.getAnnotations().stream()
                        .noneMatch(a -> STEREOTYPES.contains(simpleName(a.getNameAsString())))) {
            return;
        }
        List<Parameter> bindParameters = constructorBindingParameters(type);
        if (bindParameters.isEmpty()
                || bindParameters.stream()
                        .noneMatch(
                                p ->
                                        VALUE_PARAMETER_TYPES.contains(
                                                rawType(p.getTypeAsString())))) {
            return;
        }
        String stereotype =
                type.getAnnotations().stream()
                        .map(a -> simpleName(a.getNameAsString()))
                        .filter(STEREOTYPES::contains)
                        .findFirst()
                        .orElse("Component");
        Parameter first = bindParameters.get(0);
        findings.add(
                FindingFactory.builder(
                                FindingRules
                                        .SPRING_CONFIGURATION_PROPERTIES_BEAN_CONSTRUCTOR_BINDING,
                                FindingConfidence.HIGH)
                        .shortMessage(
                                type.getNameAsString()
                                        + " binds its properties through the constructor but is"
                                        + " also a @"
                                        + stereotype
                                        + " — Spring tries to autowire "
                                        + first.getTypeAsString()
                                        + " "
                                        + first.getNameAsString()
                                        + " and startup fails.")
                        .whyBadPractice(
                                "Constructor binding only happens when Spring Boot creates the"
                                    + " properties object itself — through"
                                    + " @EnableConfigurationProperties or"
                                    + " @ConfigurationPropertiesScan. As a component the class is"
                                    + " instantiated like any bean, so its constructor parameters"
                                    + " are resolved as bean dependencies and there is no String or"
                                    + " int bean to inject.")
                        .possibleImpact(
                                "The application context fails to start (\"Parameter 0 of"
                                        + " constructor ... required a bean of type ... that could"
                                        + " not be found\").")
                        .recommendation(
                                "Remove @"
                                        + stereotype
                                        + " and register the class with"
                                        + " @ConfigurationPropertiesScan or"
                                        + " @EnableConfigurationProperties("
                                        + type.getNameAsString()
                                        + ".class).")
                        .evidence(
                                type.getNameAsString()
                                        + " is annotated @ConfigurationProperties and @"
                                        + stereotype
                                        + ", and its constructor takes "
                                        + bindParameters.size()
                                        + " property value(s).")
                        .source(relativePath, type.getBegin().map(p -> p.line).orElse(null))
                        .target(type.getNameAsString())
                        .build());
    }

    /** Parameters of the constructor Spring would bind: a record, or a single parameterized one. */
    private static List<Parameter> constructorBindingParameters(TypeDeclaration<?> type) {
        if (type instanceof RecordDeclaration record) {
            return record.getParameters();
        }
        if (!(type instanceof ClassOrInterfaceDeclaration cls)) {
            return List.of();
        }
        List<ConstructorDeclaration> constructors = cls.getConstructors();
        if (constructors.size() != 1 || constructors.get(0).getParameters().isEmpty()) {
            return List.of();
        }
        return constructors.get(0).getParameters();
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private static String enclosingName(Node node) {
        Optional<ClassOrInterfaceDeclaration> owner =
                node.findAncestor(ClassOrInterfaceDeclaration.class);
        return owner.map(ClassOrInterfaceDeclaration::getNameAsString).orElse("");
    }

    private static boolean hasAnnotation(List<AnnotationExpr> annotations, String name) {
        return annotations.stream().anyMatch(a -> name.equals(simpleName(a.getNameAsString())));
    }

    private static boolean hasAnyAnnotation(List<AnnotationExpr> annotations, Set<String> names) {
        return annotations.stream().anyMatch(a -> names.contains(simpleName(a.getNameAsString())));
    }

    private static String rawType(String type) {
        String raw = type;
        int generic = raw.indexOf('<');
        if (generic >= 0) {
            raw = raw.substring(0, generic);
        }
        raw = raw.replace("[]", "").replace("...", "").trim();
        return simpleName(raw);
    }

    private static String simpleName(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }
}
