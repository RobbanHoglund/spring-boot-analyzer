package com.robbanhoglund.springbootanalyzer.analyzer;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
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
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Detects testing anti-patterns in {@code src/test/java} source files.
 *
 * <p>Rules covered:
 *
 * <ul>
 *   <li>{@link FindingRules#SPRING_TEST_SPRINGBOOTTEST_OVERUSED} — {@code @SpringBootTest} used
 *       when a slice annotation ({@code @WebMvcTest}, {@code @DataJpaTest}) would suffice.
 *   <li>{@link FindingRules#SPRING_TEST_NO_TRANSACTIONAL_ROLLBACK} — integration test injects a
 *       repository but has no class-level {@code @Transactional} to roll back mutations.
 *   <li>{@link FindingRules#SPRING_TEST_MOCKBEAN_OVERUSE} — more than five {@code @MockBean}
 *       fields in a single test class.
 *   <li>{@link FindingRules#SPRING_TEST_FIXED_CLOCK_MISSING} — test calls {@code .now()} without
 *       a fixed {@code Clock} bean, making time-sensitive assertions non-deterministic.
 *   <li>{@link FindingRules#SPRING_TEST_SPRINGBOOTTEST_WEBENV_NONE_MISSING} — {@code
 *       @SpringBootTest} defaults to {@code MOCK} web environment but the test does not use
 *       {@code MockMvc}, {@code WebTestClient}, or {@code TestRestTemplate}.
 * </ul>
 */
@Component
public class TestingPracticeFindingAnalyzer {

    private static final int MOCKBEAN_THRESHOLD = 5;

    private static final Set<String> SPRING_TEST_ANNOTATIONS =
            Set.of(
                    "SpringBootTest",
                    "WebMvcTest",
                    "DataJpaTest",
                    "WebFluxTest",
                    "RestClientTest",
                    "JsonTest",
                    "DataMongoTest",
                    "DataRedisTest",
                    "DataNeo4jTest");

    private static final Set<String> WEB_TEST_FIELD_TYPES =
            Set.of("MockMvc", "WebTestClient", "TestRestTemplate");

    private final JavaParser javaParser;

    public TestingPracticeFindingAnalyzer() {
        this.javaParser =
                new JavaParser(
                        new ParserConfiguration()
                                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25)
                                .setCharacterEncoding(StandardCharsets.UTF_8));
    }

    /**
     * Analyzes all Java source files under {@code src/test/java} within the given repository root.
     *
     * @param repositoryRoot root directory of the locally checked-out repository
     * @return list of findings; never null
     */
    public List<Finding> analyze(Path repositoryRoot) {
        List<Finding> findings = new ArrayList<>();
        Path testRoot = repositoryRoot.resolve("src/test/java");
        if (Files.notExists(testRoot)) {
            return findings;
        }
        try (Stream<Path> files = Files.walk(testRoot)) {
            for (Path testFile :
                    files.filter(Files::isRegularFile)
                            .filter(p -> p.toString().endsWith(".java"))
                            .sorted(Comparator.naturalOrder())
                            .toList()) {
                analyzeTestFile(repositoryRoot, testFile, findings);
            }
        } catch (IOException e) {
            // Best-effort — skip unreadable files
        }
        return findings;
    }

    // ---------------------------------------------------------------------------
    // Per-file analysis
    // ---------------------------------------------------------------------------

    private void analyzeTestFile(Path repositoryRoot, Path testFile, List<Finding> findings)
            throws IOException {
        var parseResult = javaParser.parse(testFile);
        if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
            return;
        }
        CompilationUnit cu = parseResult.getResult().orElseThrow();
        String relativePath = repositoryRoot.relativize(testFile).toString().replace('\\', '/');

        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            detectSpringBootTestOverused(cls, relativePath, findings);
            detectNoTransactionalRollback(cls, relativePath, findings);
            detectMockBeanOveruse(cls, relativePath, findings);
            detectFixedClockMissing(cls, relativePath, findings);
            detectWebEnvNoneMissing(cu, cls, relativePath, findings);
        }
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_TEST_SPRINGBOOTTEST_OVERUSED
    // ---------------------------------------------------------------------------

    private void detectSpringBootTestOverused(
            ClassOrInterfaceDeclaration cls, String relativePath, List<Finding> findings) {
        if (!hasAnnotation(cls, "SpringBootTest")) {
            return;
        }

        boolean hasControllerField =
                cls.getFields().stream()
                        .filter(f -> hasAnnotation(f, "Autowired"))
                        .anyMatch(
                                f ->
                                        f.getVariables().stream()
                                                .anyMatch(
                                                        v ->
                                                                v.getTypeAsString()
                                                                        .endsWith("Controller")));

        boolean hasRepositoryField =
                cls.getFields().stream()
                        .filter(f -> hasAnnotation(f, "Autowired"))
                        .anyMatch(
                                f ->
                                        f.getVariables().stream()
                                                .anyMatch(
                                                        v ->
                                                                v.getTypeAsString()
                                                                        .endsWith("Repository")));

        if (hasControllerField) {
            String controllerType =
                    cls.getFields().stream()
                            .filter(f -> hasAnnotation(f, "Autowired"))
                            .flatMap(f -> f.getVariables().stream())
                            .filter(v -> v.getTypeAsString().endsWith("Controller"))
                            .map(v -> v.getTypeAsString())
                            .findFirst()
                            .orElse("YourController");
            Integer line = cls.getBegin().map(p -> p.line).orElse(null);
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_TEST_SPRINGBOOTTEST_OVERUSED,
                                    FindingConfidence.MEDIUM)
                            .shortMessage(
                                    "@SpringBootTest loads the full context but this test only"
                                            + " uses a controller — @WebMvcTest would be faster.")
                            .whyBadPractice(
                                    "@SpringBootTest starts the entire Spring application context,"
                                        + " including all beans, data sources, and scheduled tasks."
                                        + " @WebMvcTest loads only the web layer (controllers,"
                                        + " filters, advice) and is typically 3–10× faster.")
                            .possibleImpact(
                                    "Slow test suite; each @SpringBootTest class can add several"
                                            + " seconds to the CI build.")
                            .recommendation(
                                    "Replace @SpringBootTest with @WebMvcTest("
                                            + controllerType
                                            + ".class) and mock service dependencies with"
                                            + " @MockitoBean (Spring Boot 3.4+; the older @MockBean"
                                            + " is deprecated).")
                            .evidence(
                                    "Class "
                                            + cls.getNameAsString()
                                            + " injects "
                                            + controllerType
                                            + " via @Autowired.")
                            .source(relativePath, line)
                            .target(cls.getNameAsString())
                            .build());
        } else if (hasRepositoryField) {
            Integer line = cls.getBegin().map(p -> p.line).orElse(null);
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_TEST_SPRINGBOOTTEST_OVERUSED,
                                    FindingConfidence.MEDIUM)
                            .shortMessage(
                                    "@SpringBootTest loads the full context but this test only"
                                            + " uses a repository — @DataJpaTest would be faster.")
                            .whyBadPractice(
                                    "@SpringBootTest starts the entire Spring application context."
                                        + " @DataJpaTest loads only the JPA layer (entities,"
                                        + " repositories, Flyway/Liquibase) and uses an in-memory"
                                        + " database by default.")
                            .possibleImpact(
                                    "Slow test suite; each @SpringBootTest class can add several"
                                            + " seconds to the CI build.")
                            .recommendation(
                                    "Replace @SpringBootTest with @DataJpaTest. If you need"
                                            + " specific service beans, add them with @Import or"
                                            + " @MockitoBean (Spring Boot 3.4+; the older @MockBean"
                                            + " is deprecated).")
                            .evidence(
                                    "Class "
                                            + cls.getNameAsString()
                                            + " injects a Repository via @Autowired.")
                            .source(relativePath, line)
                            .target(cls.getNameAsString())
                            .build());
        }
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_TEST_NO_TRANSACTIONAL_ROLLBACK
    // ---------------------------------------------------------------------------

    private void detectNoTransactionalRollback(
            ClassOrInterfaceDeclaration cls, String relativePath, List<Finding> findings) {
        boolean isIntegrationTest =
                hasAnnotation(cls, "SpringBootTest") || hasAnnotation(cls, "DataJpaTest");
        if (!isIntegrationTest) {
            return;
        }
        boolean hasRepositoryField =
                cls.getFields().stream()
                        .filter(f -> hasAnnotation(f, "Autowired"))
                        .anyMatch(
                                f ->
                                        f.getVariables().stream()
                                                .anyMatch(
                                                        v ->
                                                                v.getTypeAsString()
                                                                        .endsWith("Repository")));
        if (!hasRepositoryField) {
            return;
        }
        if (hasAnnotation(cls, "Transactional")) {
            return;
        }

        Integer line = cls.getBegin().map(p -> p.line).orElse(null);
        findings.add(
                FindingFactory.builder(
                                FindingRules.SPRING_TEST_NO_TRANSACTIONAL_ROLLBACK,
                                FindingConfidence.MEDIUM)
                        .shortMessage(
                                "Integration test uses a repository without class-level"
                                        + " @Transactional — database mutations may bleed between"
                                        + " tests.")
                        .whyBadPractice(
                                "Without @Transactional on the test class, each test method that"
                                    + " writes to the database commits those rows. Subsequent test"
                                    + " methods may fail or produce incorrect results because of"
                                    + " data left behind by earlier methods.")
                        .possibleImpact(
                                "Order-dependent test failures; passing tests in isolation that"
                                        + " fail in the full suite; corrupted test database state.")
                        .recommendation(
                                "Annotate the test class with @Transactional. Spring will"
                                        + " automatically roll back each test method's transaction"
                                        + " after it completes.")
                        .limitations(
                                "Some tests intentionally verify committed data across"
                                    + " transactions. If that is the case, add @Commit or"
                                    + " @Rollback(false) on the specific method and document the"
                                    + " reason.")
                        .source(relativePath, line)
                        .target(cls.getNameAsString())
                        .build());
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_TEST_MOCKBEAN_OVERUSE
    // ---------------------------------------------------------------------------

    private void detectMockBeanOveruse(
            ClassOrInterfaceDeclaration cls, String relativePath, List<Finding> findings) {
        // @MockBean was deprecated in Spring Boot 3.4 in favor of Spring Framework 6.2's
        // @MockitoBean; both fragment the test-context cache the same way, so count both.
        long mockBeanCount =
                cls.getFields().stream()
                        .filter(
                                f ->
                                        hasAnnotation(f, "MockBean")
                                                || hasAnnotation(f, "MockitoBean"))
                        .count();
        if (mockBeanCount <= MOCKBEAN_THRESHOLD) {
            return;
        }
        Integer line = cls.getBegin().map(p -> p.line).orElse(null);
        findings.add(
                FindingFactory.builder(
                                FindingRules.SPRING_TEST_MOCKBEAN_OVERUSE, FindingConfidence.MEDIUM)
                        .shortMessage(
                                mockBeanCount
                                        + " mocked-bean fields (@MockBean/@MockitoBean) in "
                                        + cls.getNameAsString()
                                        + " — consider narrowing the test scope.")
                        .whyBadPractice(
                                "Each mocked bean (@MockBean, or @MockitoBean on Spring Boot 3.4+)"
                                    + " replaces a real Spring bean in the application context."
                                    + " When the mock configurations differ between test classes,"
                                    + " Spring must create a new context for each, making the test"
                                    + " suite significantly slower. A high count also indicates the"
                                    + " class under test has excessive dependencies.")
                        .possibleImpact(
                                "Slow CI builds due to repeated context reloads; brittle tests"
                                        + " tightly coupled to implementation details.")
                        .recommendation(
                                "Consider using plain unit tests (no Spring context) with"
                                    + " Mockito.mock() for unit-level tests of classes with many"
                                    + " dependencies. Reserve @MockitoBean (@MockBean before Spring"
                                    + " Boot 3.4) for integration tests that genuinely need the"
                                    + " Spring context.")
                        .evidence(
                                cls.getNameAsString()
                                        + " declares "
                                        + mockBeanCount
                                        + " @MockBean fields (threshold: "
                                        + MOCKBEAN_THRESHOLD
                                        + ").")
                        .source(relativePath, line)
                        .target(cls.getNameAsString())
                        .build());
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_TEST_FIXED_CLOCK_MISSING
    // ---------------------------------------------------------------------------

    private void detectFixedClockMissing(
            ClassOrInterfaceDeclaration cls, String relativePath, List<Finding> findings) {
        boolean hasSpringTestAnnotation =
                SPRING_TEST_ANNOTATIONS.stream().anyMatch(a -> hasAnnotation(cls, a));
        if (!hasSpringTestAnnotation) {
            return;
        }
        boolean callsNow =
                cls.findAll(MethodCallExpr.class).stream()
                        .anyMatch(call -> "now".equals(call.getNameAsString()));
        if (!callsNow) {
            return;
        }
        boolean hasClock =
                cls.getFields().stream()
                        .anyMatch(
                                f ->
                                        f.getVariables().stream()
                                                .anyMatch(
                                                        v -> "Clock".equals(v.getTypeAsString())));
        if (hasClock) {
            return;
        }

        Integer line = cls.getBegin().map(p -> p.line).orElse(null);
        findings.add(
                FindingFactory.builder(
                                FindingRules.SPRING_TEST_FIXED_CLOCK_MISSING, FindingConfidence.LOW)
                        .shortMessage(
                                "Test calls .now() without a fixed Clock — assertions may fail"
                                        + " non-deterministically.")
                        .whyBadPractice(
                                "Calling LocalDateTime.now(), Instant.now(), or similar methods"
                                        + " in a test produces a different value on every run. Any"
                                        + " assertion that depends on the current time (e.g. expiry"
                                        + " checks, age calculations) is inherently fragile.")
                        .possibleImpact(
                                "Intermittent CI failures around midnight, month-end, year-end,"
                                        + " or daylight saving time transitions.")
                        .recommendation(
                                "Inject a java.time.Clock bean into the application component"
                                        + " and replace LocalDateTime.now() with"
                                        + " LocalDateTime.now(clock). In the test, use"
                                        + " Clock.fixed(Instant.parse(\"2024-06-01T12:00:00Z\","
                                        + " ZoneOffset.UTC) to pin time.")
                        .limitations(
                                "Low confidence — the .now() call may be in a utility method or"
                                        + " logging statement that does not affect assertions.")
                        .source(relativePath, line)
                        .target(cls.getNameAsString())
                        .build());
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_TEST_SPRINGBOOTTEST_WEBENV_NONE_MISSING
    // ---------------------------------------------------------------------------

    private void detectWebEnvNoneMissing(
            CompilationUnit cu,
            ClassOrInterfaceDeclaration cls,
            String relativePath,
            List<Finding> findings) {
        Optional<AnnotationExpr> sbtAnnotation =
                cls.getAnnotations().stream()
                        .filter(a -> "SpringBootTest".equals(simpleName(a.getNameAsString())))
                        .findFirst();
        if (sbtAnnotation.isEmpty()) {
            return;
        }

        // If webEnvironment is explicitly set to NONE, RANDOM_PORT, or DEFINED_PORT → skip
        AnnotationExpr ann = sbtAnnotation.get();
        if (ann.isNormalAnnotationExpr()) {
            for (MemberValuePair pair : ann.asNormalAnnotationExpr().getPairs()) {
                if ("webEnvironment".equals(pair.getNameAsString())) {
                    String value = pair.getValue().toString();
                    if (value.contains("NONE")
                            || value.contains("RANDOM_PORT")
                            || value.contains("DEFINED_PORT")) {
                        return;
                    }
                    break;
                }
            }
        }

        // If the class uses MockMvc / WebTestClient / TestRestTemplate → web env is needed
        boolean hasWebTestField =
                cls.getFields().stream()
                        .anyMatch(
                                f ->
                                        f.getVariables().stream()
                                                .anyMatch(
                                                        v ->
                                                                WEB_TEST_FIELD_TYPES.contains(
                                                                        v.getTypeAsString())));
        if (hasWebTestField) {
            return;
        }

        // Also check imports for MockMvc / WebTestClient usage
        boolean importsMockMvcOrWebClient =
                cu.getImports().stream()
                        .anyMatch(
                                i -> {
                                    String name = i.getNameAsString();
                                    return name.contains("MockMvc")
                                            || name.contains("WebTestClient")
                                            || name.contains("TestRestTemplate");
                                });
        if (importsMockMvcOrWebClient) {
            return;
        }

        Integer line = cls.getBegin().map(p -> p.line).orElse(null);
        findings.add(
                FindingFactory.builder(
                                FindingRules.SPRING_TEST_SPRINGBOOTTEST_WEBENV_NONE_MISSING,
                                FindingConfidence.MEDIUM)
                        .shortMessage(
                                "@SpringBootTest starts a mock web layer that this test does not"
                                        + " use — add webEnvironment = NONE.")
                        .whyBadPractice(
                                "The default webEnvironment=MOCK initialises a full"
                                    + " DispatcherServlet and mock HTTP layer even when the test"
                                    + " has no web-facing assertions. This wastes memory and slows"
                                    + " startup.")
                        .possibleImpact(
                                "Unnecessary startup overhead for every test run; wasted memory"
                                        + " initialising servlet infrastructure.")
                        .recommendation(
                                "Add webEnvironment = SpringBootTest.WebEnvironment.NONE to the"
                                    + " @SpringBootTest annotation to skip the web layer entirely.")
                        .evidence(
                                "No MockMvc, WebTestClient, or TestRestTemplate field found in "
                                        + cls.getNameAsString()
                                        + ".")
                        .source(relativePath, line)
                        .target(cls.getNameAsString())
                        .build());
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private static boolean hasAnnotation(ClassOrInterfaceDeclaration cls, String name) {
        return cls.getAnnotations().stream()
                .anyMatch(a -> simpleName(a.getNameAsString()).equals(name));
    }

    private static boolean hasAnnotation(FieldDeclaration field, String name) {
        return field.getAnnotations().stream()
                .anyMatch(a -> simpleName(a.getNameAsString()).equals(name));
    }

    private static String simpleName(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }
}
