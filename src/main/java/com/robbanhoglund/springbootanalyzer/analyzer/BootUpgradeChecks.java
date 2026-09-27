package com.robbanhoglund.springbootanalyzer.analyzer;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingConfidence;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingFactory;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingRules;
import com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.ApplicationProperty;
import com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.ConfigurationAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.source.JavaSources;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Upgrade pitfalls that depend on the resolved Spring Boot version together with the build or
 * test sources: parameter-name retention (Boot 3.2+), Jackson 2 beans and test clients on Boot 4.
 */
final class BootUpgradeChecks {

    private static final Set<String> NAMED_BINDINGS =
            Set.of(
                    "PathVariable",
                    "RequestParam",
                    "RequestHeader",
                    "CookieValue",
                    "MatrixVariable",
                    "RequestAttribute",
                    "SessionAttribute",
                    "RequestPart");

    private static final Set<String> SPEL_ANNOTATIONS =
            Set.of(
                    "Cacheable",
                    "CachePut",
                    "CacheEvict",
                    "PreAuthorize",
                    "PostAuthorize",
                    "PreFilter",
                    "PostFilter",
                    "EventListener");

    private static final Pattern BOOT_PLUGIN =
            Pattern.compile(
                    "id\\s*\\(?\\s*['\"]org\\.springframework\\.boot['\"]"
                        + "|apply\\s*\\(?\\s*plugin\\s*[:=]\\s*['\"]org\\.springframework\\.boot['\"]"
                        + "|libs\\.plugins\\.spring\\.boot\\b|libs\\.plugins\\.springBoot\\b"
                        + "|libs\\.plugins\\.spring-boot\\b");

    private static final Pattern SPEL_VARIABLE = Pattern.compile("#([A-Za-z_]\\w*)");

    private static final Set<String> SPEL_BUILT_INS = Set.of("root", "result", "this");

    private static final Map<String, String> BOOT4_TEST_CLIENTS =
            Map.of(
                    "TestRestTemplate", "AutoConfigureTestRestTemplate",
                    "RestTestClient", "AutoConfigureRestTestClient");

    private BootUpgradeChecks() {}

    static List<Finding> analyze(
            JavaSources sources, String bootVersion, ConfigurationAnalysis configurationAnalysis) {
        List<Finding> findings = new ArrayList<>();
        detectParameterNamesNotRetained(sources, bootVersion, findings);
        if (SpringBootVersions.major(bootVersion) >= 4) {
            detectJackson2ObjectMapperBeans(sources, configurationAnalysis, findings);
            detectBoot4TestClients(sources.repositoryRoot(), findings);
        }
        return findings;
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_PARAMETER_NAMES_NOT_RETAINED
    // ---------------------------------------------------------------------------

    private record BuildVerdict(Boolean retained, String description) {}

    private record NameUsage(
            String relativePath, Integer line, String target, String description) {}

    private static void detectParameterNamesNotRetained(
            JavaSources sources, String bootVersion, List<Finding> findings) {
        // Spring Framework 6.1 (Spring Boot 3.2) dropped the bytecode-based parameter name lookup.
        if (bootVersion == null || !SpringBootVersions.isAtLeast(bootVersion, 3, 2)) {
            return;
        }
        BuildVerdict verdict = inspectBuild(sources.repositoryRoot());
        if (verdict.retained() == null || verdict.retained()) {
            return;
        }
        NameUsage usage = firstParameterNameUsage(sources);
        if (usage == null) {
            return;
        }
        findings.add(
                FindingFactory.builder(
                                FindingRules.SPRING_PARAMETER_NAMES_NOT_RETAINED,
                                FindingConfidence.MEDIUM)
                        .shortMessage(
                                usage.description()
                                        + " relies on the parameter name, but the build does not"
                                        + " compile with -parameters — it fails at runtime.")
                        .whyBadPractice(
                                "Since Spring Framework 6.1 parameter names come only from the"
                                    + " -parameters compiler flag. The Spring Boot Gradle plugin"
                                    + " and spring-boot-starter-parent set it; "
                                        + verdict.description()
                                        + ". Without it Spring cannot tell which request value or"
                                        + " SpEL variable a parameter stands for.")
                        .possibleImpact(
                                "Handler calls fail with IllegalArgumentException (\"Name for"
                                    + " argument of type ... not specified, and parameter name"
                                    + " information not available via reflection\"), and SpEL"
                                    + " references such as #id evaluate to null — cache keys become"
                                    + " null and security expressions misjudge access.")
                        .recommendation(
                                "Compile with -parameters (Maven: <parameters>true</parameters> in"
                                    + " maven-compiler-plugin; Gradle:"
                                    + " options.compilerArgs.add(\"-parameters\")), or name every"
                                    + " binding explicitly, for example @PathVariable(\"id\").")
                        .evidence(
                                usage.description()
                                        + " in "
                                        + usage.relativePath()
                                        + "; "
                                        + verdict.description()
                                        + ".")
                        .limitations(
                                "A parent POM outside the repository, or build logic the analyzer"
                                        + " cannot read, may set the flag; such projects are not"
                                        + " reported.")
                        .source(usage.relativePath(), usage.line())
                        .target(usage.target())
                        .build());
    }

    private static BuildVerdict inspectBuild(Path root) {
        Path pom = root.resolve("pom.xml");
        if (Files.isRegularFile(pom)) {
            return inspectPom(root, pom, 0);
        }
        Path gradle =
                Files.isRegularFile(root.resolve("build.gradle.kts"))
                        ? root.resolve("build.gradle.kts")
                        : root.resolve("build.gradle");
        if (!Files.isRegularFile(gradle)) {
            return new BuildVerdict(null, "no build file was found");
        }
        StringBuilder buildLogic = new StringBuilder();
        try (Stream<Path> files = Files.walk(root, 6)) {
            for (Path file :
                    files.filter(Files::isRegularFile)
                            .filter(path -> !path.toString().contains("node_modules"))
                            .filter(
                                    path ->
                                            !path.toString()
                                                    .contains(
                                                            java.io.File.separator
                                                                    + "build"
                                                                    + java.io.File.separator))
                            .filter(
                                    path -> {
                                        String name = path.getFileName().toString();
                                        return name.endsWith(".gradle")
                                                || name.endsWith(".gradle.kts")
                                                || name.equals("libs.versions.toml");
                                    })
                            .toList()) {
                buildLogic.append(read(file)).append('\n');
            }
        } catch (IOException exception) {
            return new BuildVerdict(null, "the build files could not be read");
        }
        String text = buildLogic.toString();
        if (text.contains("-parameters")) {
            return new BuildVerdict(true, "the build passes -parameters");
        }
        boolean catalogPlugin =
                text.contains("alias(libs.plugins.")
                        && text.contains("\"org.springframework.boot\"");
        if (BOOT_PLUGIN.matcher(text).find() || catalogPlugin) {
            return new BuildVerdict(true, "the Spring Boot Gradle plugin is applied");
        }
        return new BuildVerdict(
                false,
                gradle.getFileName()
                        + " applies neither the org.springframework.boot plugin nor"
                        + " -parameters");
    }

    private static BuildVerdict inspectPom(Path root, Path pom, int depth) {
        String content = read(pom);
        if (content.contains("<parameters>true</parameters>")
                || content.contains("<maven.compiler.parameters>true</maven.compiler.parameters>")
                || content.contains("-parameters")) {
            return new BuildVerdict(true, "the POM enables -parameters");
        }
        Matcher parent = Pattern.compile("<parent>(.*?)</parent>", Pattern.DOTALL).matcher(content);
        if (!parent.find()) {
            return new BuildVerdict(
                    false,
                    root.relativize(pom).toString().replace('\\', '/')
                            + " has no spring-boot-starter-parent and no -parameters setting");
        }
        String parentBlock = parent.group(1);
        if (parentBlock.contains("<artifactId>spring-boot-starter-parent</artifactId>")) {
            return new BuildVerdict(true, "spring-boot-starter-parent enables -parameters");
        }
        Matcher relative =
                Pattern.compile("<relativePath>(.*?)</relativePath>").matcher(parentBlock);
        String relativePath = relative.find() ? relative.group(1).trim() : "../pom.xml";
        if (relativePath.isEmpty() || depth >= 3) {
            return new BuildVerdict(null, "the parent POM is not part of the repository");
        }
        Path parentPom = pom.getParent().resolve(relativePath).normalize();
        if (Files.isDirectory(parentPom)) {
            parentPom = parentPom.resolve("pom.xml");
        }
        if (!parentPom.startsWith(root.normalize()) || !Files.isRegularFile(parentPom)) {
            return new BuildVerdict(null, "the parent POM is not part of the repository");
        }
        return inspectPom(root, parentPom, depth + 1);
    }

    private static NameUsage firstParameterNameUsage(JavaSources sources) {
        for (JavaSources.JavaFile file : sources.files()) {
            CompilationUnit cu = file.compilationUnit();
            if (cu == null) {
                continue;
            }
            for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
                String target =
                        method.findAncestor(
                                                com.github.javaparser.ast.body
                                                        .ClassOrInterfaceDeclaration.class)
                                        .map(c -> c.getNameAsString() + "#")
                                        .orElse("")
                                + method.getNameAsString();
                for (Parameter parameter : method.getParameters()) {
                    for (AnnotationExpr annotation : parameter.getAnnotations()) {
                        String name = simpleName(annotation.getNameAsString());
                        if (NAMED_BINDINGS.contains(name)
                                && !namesItsValue(annotation)
                                && !bindsAllValues(parameter.getTypeAsString())) {
                            return new NameUsage(
                                    file.relativePath(),
                                    parameter.getBegin().map(p -> p.line).orElse(null),
                                    target,
                                    "@"
                                            + name
                                            + " "
                                            + parameter.getTypeAsString()
                                            + " "
                                            + parameter.getNameAsString()
                                            + " in "
                                            + target);
                        }
                    }
                }
                Set<String> parameterNames =
                        method.getParameters().stream()
                                .map(Parameter::getNameAsString)
                                .collect(java.util.stream.Collectors.toSet());
                for (AnnotationExpr annotation : method.getAnnotations()) {
                    if (!SPEL_ANNOTATIONS.contains(simpleName(annotation.getNameAsString()))) {
                        continue;
                    }
                    for (StringLiteralExpr literal : annotation.findAll(StringLiteralExpr.class)) {
                        Matcher variable = SPEL_VARIABLE.matcher(literal.asString());
                        while (variable.find()) {
                            String referenced = variable.group(1);
                            if (!SPEL_BUILT_INS.contains(referenced)
                                    && parameterNames.contains(referenced)) {
                                return new NameUsage(
                                        file.relativePath(),
                                        method.getBegin().map(p -> p.line).orElse(null),
                                        target,
                                        "#"
                                                + referenced
                                                + " in @"
                                                + simpleName(annotation.getNameAsString())
                                                + " on "
                                                + target);
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Map-like parameters receive every value, so they need no name. */
    private static boolean bindsAllValues(String type) {
        return type.matches("(Multi)?(Value)?Map(<.*)?") || type.equals("HttpHeaders");
    }

    /** Whether a binding annotation names its value ({@code @PathVariable("id")}). */
    private static boolean namesItsValue(AnnotationExpr annotation) {
        if (annotation.isSingleMemberAnnotationExpr()) {
            return true;
        }
        if (annotation.isNormalAnnotationExpr()) {
            return annotation.asNormalAnnotationExpr().getPairs().stream()
                    .anyMatch(
                            pair ->
                                    "value".equals(pair.getNameAsString())
                                            || "name".equals(pair.getNameAsString()));
        }
        return false;
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_JACKSON2_OBJECTMAPPER_IGNORED_FOR_HTTP
    // ---------------------------------------------------------------------------

    private static final Set<String> JACKSON2_BEAN_TYPES =
            Set.of(
                    "ObjectMapper",
                    "JsonMapper",
                    "Jackson2ObjectMapperBuilder",
                    "Jackson2ObjectMapperBuilderCustomizer");

    private static void detectJackson2ObjectMapperBeans(
            JavaSources sources,
            ConfigurationAnalysis configurationAnalysis,
            List<Finding> findings) {
        if (configurationAnalysis != null && configurationAnalysis.properties() != null) {
            for (ApplicationProperty property : configurationAnalysis.properties()) {
                if ("spring.http.converters.preferred-json-mapper".equals(property.name())
                        && property.value() != null
                        && "jackson2".equalsIgnoreCase(property.value().trim())) {
                    return;
                }
            }
        }
        for (JavaSources.JavaFile file : sources.files()) {
            CompilationUnit cu = file.compilationUnit();
            if (cu == null
                    || !(file.content().contains("com.fasterxml.jackson.databind")
                            || file.content().contains("Jackson2ObjectMapperBuilder"))) {
                continue;
            }
            for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
                String returnType = method.getType().asString();
                String simple = simpleName(returnType);
                if (method.getAnnotationByName("Bean").isEmpty()
                        || !JACKSON2_BEAN_TYPES.contains(simple)) {
                    continue;
                }
                boolean jackson2 =
                        simple.startsWith("Jackson2")
                                || returnType.startsWith("com.fasterxml.")
                                || cu.getImports().stream()
                                        .anyMatch(
                                                i ->
                                                        i.getNameAsString()
                                                                        .startsWith(
                                                                                "com.fasterxml.jackson")
                                                                && i.getNameAsString()
                                                                        .endsWith("." + simple));
                if (!jackson2) {
                    continue;
                }
                String target =
                        method.findAncestor(
                                                com.github.javaparser.ast.body
                                                        .ClassOrInterfaceDeclaration.class)
                                        .map(c -> c.getNameAsString() + "#")
                                        .orElse("")
                                + method.getNameAsString();
                findings.add(
                        FindingFactory.builder(
                                        FindingRules.SPRING_JACKSON2_OBJECTMAPPER_IGNORED_FOR_HTTP,
                                        FindingConfidence.HIGH)
                                .shortMessage(
                                        "@Bean "
                                                + target
                                                + " configures Jackson 2, but Spring Boot 4"
                                                + " converts HTTP JSON with Jackson 3 — its"
                                                + " settings do not reach request or response"
                                                + " bodies.")
                                .whyBadPractice(
                                        "Spring Boot 4 auto-configures a Jackson 3 JsonMapper"
                                            + " (tools.jackson) for Spring MVC and WebFlux. Its"
                                            + " Jackson 2 message converter only uses a Jackson 2"
                                            + " ObjectMapper bean when"
                                            + " spring.http.converters.preferred-json-mapper=jackson2"
                                            + " or Jackson 3 is missing, so this bean now only"
                                            + " serves code that injects it.")
                                .possibleImpact(
                                        "Date formats, naming strategies, inclusion rules or"
                                            + " modules configured here silently no longer apply to"
                                            + " the REST API.")
                                .recommendation(
                                        "Move HTTP-relevant settings to spring.jackson.* properties"
                                            + " or a JsonMapperBuilderCustomizer, or set"
                                            + " spring.http.converters.preferred-json-mapper=jackson2"
                                            + " while migrating.")
                                .evidence(
                                        target
                                                + " returns "
                                                + returnType
                                                + " from Jackson 2 on a Spring Boot 4 project.")
                                .limitations(
                                        "The bean may be intended only for code that injects it;"
                                                + " the finding is informational.")
                                .source(
                                        file.relativePath(),
                                        method.getBegin().map(p -> p.line).orElse(null))
                                .target(target)
                                .build());
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_BOOT4_TEST_CLIENT_NOT_AUTOCONFIGURED
    // ---------------------------------------------------------------------------

    private static void detectBoot4TestClients(Path root, List<Finding> findings) {
        Path testRoot = root.resolve("src/test/java");
        if (!Files.isDirectory(testRoot)) {
            return;
        }
        List<Path> testFiles;
        try (Stream<Path> files = Files.walk(testRoot)) {
            testFiles =
                    files.filter(Files::isRegularFile)
                            .filter(path -> path.toString().endsWith(".java"))
                            .sorted()
                            .toList();
        } catch (IOException exception) {
            return;
        }
        for (Path file : testFiles) {
            String content = read(file);
            // A base class may carry the annotation; such hierarchies are not followed.
            if (!content.contains("@SpringBootTest")
                    || Pattern.compile("\\bclass\\s+\\w+\\s+extends\\b").matcher(content).find()) {
                continue;
            }
            for (Map.Entry<String, String> client : BOOT4_TEST_CLIENTS.entrySet()) {
                // An injected field or parameter; a client built with new needs no bean.
                Matcher declaration =
                        Pattern.compile(
                                        "\\b"
                                                + client.getKey()
                                                + "\\s+\\w+\\s*(?:[;,)]|=(?!\\s*new\\b))")
                                .matcher(content);
                if (!declaration.find() || content.contains("@" + client.getValue())) {
                    continue;
                }
                String relativePath = root.relativize(file).toString().replace('\\', '/');
                int line = content.substring(0, declaration.start()).split("\n", -1).length;
                String className = file.getFileName().toString().replace(".java", "");
                findings.add(
                        FindingFactory.builder(
                                        FindingRules.SPRING_BOOT4_TEST_CLIENT_NOT_AUTOCONFIGURED,
                                        FindingConfidence.HIGH)
                                .shortMessage(
                                        className
                                                + " injects "
                                                + client.getKey()
                                                + " without @"
                                                + client.getValue()
                                                + " — on Spring Boot 4 the test context fails to"
                                                + " load.")
                                .whyBadPractice(
                                        "Spring Boot 4 moved "
                                                + client.getKey()
                                                + " to the spring-boot-resttestclient module, which"
                                                + " only contributes the client when the test class"
                                                + " is annotated @"
                                                + client.getValue()
                                                + ". @SpringBootTest alone no longer registers it.")
                                .possibleImpact(
                                        "The injection point cannot be satisfied"
                                            + " (NoSuchBeanDefinitionException) and every test in"
                                            + " the class fails before it runs.")
                                .recommendation(
                                        "Annotate "
                                                + className
                                                + " with @"
                                                + client.getValue()
                                                + ", or switch to RestTestClient/MockMvcTester.")
                                .evidence(
                                        relativePath
                                                + " is a @SpringBootTest that declares a "
                                                + client.getKey()
                                                + " but has no @"
                                                + client.getValue()
                                                + ".")
                                .limitations(
                                        "Test classes that extend a base class are not reported;"
                                                + " the base class may carry the annotation.")
                                .source(relativePath, line)
                                .target(className)
                                .build());
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | java.io.UncheckedIOException exception) {
            return "";
        }
    }

    private static String simpleName(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }
}
