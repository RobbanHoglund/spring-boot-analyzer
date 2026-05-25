package com.robbanhoglund.springbootanalyzer.analyzer;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
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
 * Detects security-related anti-patterns in {@code src/main/java} source files.
 *
 * <p>Rules covered:
 *
 * <ul>
 *   <li>{@link FindingRules#SPRING_CSRF_DISABLED_CODE} — CSRF protection explicitly disabled via
 *       {@code .csrf().disable()} or {@code csrf(AbstractHttpConfigurer::disable)}.
 *   <li>{@link FindingRules#SPRING_PREAUTHORIZE_ON_PRIVATE_METHOD} — {@code @PreAuthorize},
 *       {@code @PostAuthorize}, {@code @Secured}, or {@code @RolesAllowed} on a private method
 *       that Spring Security's AOP proxy cannot intercept.
 *   <li>{@link FindingRules#SPRING_WEAK_PASSWORD_HASH} — {@code MessageDigest.getInstance()} called
 *       with {@code "MD5"}, {@code "SHA-1"}, or {@code "SHA-256"} — algorithms that are too fast
 *       for safe password hashing.
 * </ul>
 */
@Component
public class SecurityPracticeFindingAnalyzer {

    private static final Set<String> SECURITY_ANNOTATIONS =
            Set.of("PreAuthorize", "PostAuthorize", "Secured", "RolesAllowed");

    private final JavaParser javaParser;

    public SecurityPracticeFindingAnalyzer() {
        this.javaParser =
                new JavaParser(
                        new ParserConfiguration()
                                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25)
                                .setCharacterEncoding(StandardCharsets.UTF_8));
    }

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

        detectCsrfDisabled(cu, relativePath, findings);
        detectWeakPasswordHash(cu, relativePath, findings);

        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            for (MethodDeclaration method : cls.getMethods()) {
                detectPreAuthorizeOnPrivateMethod(cls, method, relativePath, findings);
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_CSRF_DISABLED_CODE
    // ---------------------------------------------------------------------------

    /**
     * Detects CSRF disabled via two patterns:
     * <ol>
     *   <li>{@code .csrf().disable()} — a {@code disable()} call whose receiver is a call named
     *       {@code csrf}.
     *   <li>{@code csrf(AbstractHttpConfigurer::disable)} — a {@code csrf(...)} call whose
     *       argument contains a method reference to {@code disable}.
     * </ol>
     */
    private void detectCsrfDisabled(
            CompilationUnit cu, String relativePath, List<Finding> findings) {
        // Pattern 1: csrf().disable()
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            if (!"disable".equals(call.getNameAsString())) {
                continue;
            }
            boolean scopeIsCsrfCall =
                    call.getScope()
                            .filter(s -> s instanceof MethodCallExpr)
                            .map(s -> "csrf".equals(((MethodCallExpr) s).getNameAsString()))
                            .orElse(false);
            if (scopeIsCsrfCall) {
                Integer line = call.getBegin().map(p -> p.line).orElse(null);
                findings.add(
                        FindingFactory.builder(
                                        FindingRules.SPRING_CSRF_DISABLED_CODE,
                                        FindingConfidence.MEDIUM)
                                .shortMessage(
                                        "CSRF protection explicitly disabled via csrf().disable()"
                                                + " in "
                                                + relativePath
                                                + ".")
                                .whyBadPractice(
                                        "CSRF (Cross-Site Request Forgery) protection prevents"
                                            + " malicious sites from making state-changing requests"
                                            + " on behalf of authenticated users. Disabling it"
                                            + " leaves browser-based applications vulnerable to"
                                            + " CSRF attacks.")
                                .possibleImpact(
                                        "Authenticated users can be tricked into performing"
                                                + " unintended state-changing actions (transfers,"
                                                + " account changes, data deletion) via malicious"
                                                + " cross-origin requests.")
                                .recommendation(
                                        "Only disable CSRF for stateless REST APIs that use"
                                            + " token-based authentication (e.g. JWT in the"
                                            + " Authorization header) and do not rely on browser"
                                            + " session cookies. If session cookies are used, keep"
                                            + " CSRF protection enabled.")
                                .limitations(
                                        "Medium confidence — disabling CSRF is intentional and"
                                            + " correct for stateless REST APIs with token-based"
                                            + " authentication.")
                                .evidence("csrf().disable() call found in " + relativePath + ".")
                                .source(relativePath, line)
                                .build());
                return; // One finding per file is sufficient
            }
        }

        // Pattern 2: csrf(AbstractHttpConfigurer::disable) — method reference argument
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            if (!"csrf".equals(call.getNameAsString())) {
                continue;
            }
            boolean hasDisableMethodRef =
                    call.getArguments().stream()
                            .anyMatch(
                                    arg -> {
                                        if (arg instanceof MethodReferenceExpr ref) {
                                            return "disable".equals(ref.getIdentifier());
                                        }
                                        // Also check lambda: csrf(c -> c.disable())
                                        // Use method-call search rather than toString().contains()
                                        // to avoid false positives from variable names or comments.
                                        return arg.findAll(MethodCallExpr.class).stream()
                                                .anyMatch(
                                                        m -> "disable".equals(m.getNameAsString()));
                                    });
            if (hasDisableMethodRef) {
                Integer line = call.getBegin().map(p -> p.line).orElse(null);
                findings.add(
                        FindingFactory.builder(
                                        FindingRules.SPRING_CSRF_DISABLED_CODE,
                                        FindingConfidence.MEDIUM)
                                .shortMessage(
                                        "CSRF protection explicitly disabled via"
                                                + " csrf(AbstractHttpConfigurer::disable) in "
                                                + relativePath
                                                + ".")
                                .whyBadPractice(
                                        "CSRF (Cross-Site Request Forgery) protection prevents"
                                            + " malicious sites from making state-changing requests"
                                            + " on behalf of authenticated users. Disabling it"
                                            + " leaves browser-based applications vulnerable to"
                                            + " CSRF attacks.")
                                .possibleImpact(
                                        "Authenticated users can be tricked into performing"
                                                + " unintended state-changing actions (transfers,"
                                                + " account changes, data deletion) via malicious"
                                                + " cross-origin requests.")
                                .recommendation(
                                        "Only disable CSRF for stateless REST APIs that use"
                                            + " token-based authentication (e.g. JWT in the"
                                            + " Authorization header) and do not rely on browser"
                                            + " session cookies. If session cookies are used, keep"
                                            + " CSRF protection enabled.")
                                .limitations(
                                        "Medium confidence — disabling CSRF is intentional and"
                                            + " correct for stateless REST APIs with token-based"
                                            + " authentication.")
                                .evidence(
                                        "csrf(AbstractHttpConfigurer::disable) call found in "
                                                + relativePath
                                                + ".")
                                .source(relativePath, line)
                                .build());
                return;
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_PREAUTHORIZE_ON_PRIVATE_METHOD
    // ---------------------------------------------------------------------------

    private void detectPreAuthorizeOnPrivateMethod(
            ClassOrInterfaceDeclaration cls,
            MethodDeclaration method,
            String relativePath,
            List<Finding> findings) {
        if (!method.isPrivate()) {
            return;
        }
        String securityAnn =
                method.getAnnotations().stream()
                        .map(a -> simpleName(a.getNameAsString()))
                        .filter(SECURITY_ANNOTATIONS::contains)
                        .findFirst()
                        .orElse(null);
        if (securityAnn == null) {
            return;
        }
        Integer line = method.getBegin().map(p -> p.line).orElse(null);
        String target = cls.getNameAsString() + "#" + method.getNameAsString();
        findings.add(
                FindingFactory.builder(
                                FindingRules.SPRING_PREAUTHORIZE_ON_PRIVATE_METHOD,
                                FindingConfidence.HIGH)
                        .shortMessage(
                                "@"
                                        + securityAnn
                                        + " on private method "
                                        + target
                                        + " — Spring Security's proxy cannot intercept private"
                                        + " methods.")
                        .whyBadPractice(
                                "Spring Security applies authorization through a proxy (JDK dynamic"
                                        + " proxy or CGLIB). Proxies can only intercept calls made"
                                        + " through the proxy reference and cannot override private"
                                        + " methods. The security annotation is silently ignored.")
                        .possibleImpact(
                                "The authorization check is never executed for calls to this"
                                    + " method; any caller — even unauthenticated or unauthorized"
                                    + " callers — can invoke the method without restriction.")
                        .recommendation(
                                "Change the method visibility to package-private, protected, or"
                                    + " public. If the method must remain private, extract the"
                                    + " secured logic to a public wrapper method, or use AspectJ"
                                    + " compile-time weaving instead of proxy-based AOP.")
                        .limitations(
                                "If the project uses AspectJ weaving instead of Spring proxies,"
                                        + " private methods can be intercepted.")
                        .evidence(
                                "@"
                                        + securityAnn
                                        + " found on private method "
                                        + method.getNameAsString()
                                        + " in "
                                        + relativePath
                                        + ".")
                        .source(relativePath, line)
                        .target(target)
                        .build());
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_WEAK_PASSWORD_HASH
    // ---------------------------------------------------------------------------

    private static final Set<String> WEAK_HASH_ALGORITHMS = Set.of("MD5", "SHA-1", "SHA-256");

    private void detectWeakPasswordHash(
            CompilationUnit cu, String relativePath, List<Finding> findings) {
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            if (!"getInstance".equals(call.getNameAsString())) {
                continue;
            }
            boolean scopeIsMessageDigest =
                    call.getScope()
                            .map(s -> simpleName(s.toString()).equals("MessageDigest"))
                            .orElse(false);
            if (!scopeIsMessageDigest) {
                continue;
            }
            call.getArguments().stream()
                    .filter(arg -> arg instanceof com.github.javaparser.ast.expr.StringLiteralExpr)
                    .map(arg -> ((com.github.javaparser.ast.expr.StringLiteralExpr) arg).asString())
                    .filter(WEAK_HASH_ALGORITHMS::contains)
                    .findFirst()
                    .ifPresent(
                            algo -> {
                                Integer line = call.getBegin().map(p -> p.line).orElse(null);
                                findings.add(
                                        FindingFactory.builder(
                                                        FindingRules.SPRING_WEAK_PASSWORD_HASH,
                                                        FindingConfidence.MEDIUM)
                                                .shortMessage(
                                                        "MessageDigest.getInstance(\""
                                                                + algo
                                                                + "\") used in "
                                                                + relativePath
                                                                + " — too fast for password"
                                                                + " hashing.")
                                                .whyBadPractice(
                                                        algo
                                                                + " is a general-purpose"
                                                                + " cryptographic hash function"
                                                                + " optimised for speed. Speed is"
                                                                + " exactly what you do not want"
                                                                + " for password hashing: a modern"
                                                                + " GPU can compute billions of MD5"
                                                                + " or SHA-256 hashes per second,"
                                                                + " making brute-force and"
                                                                + " rainbow-table attacks trivial"
                                                                + " against any leaked hash"
                                                                + " database.")
                                                .possibleImpact(
                                                        "In a credential breach, all stored"
                                                            + " passwords can be recovered within"
                                                            + " hours or days using commodity"
                                                            + " hardware. Credential stuffing"
                                                            + " attacks then compromise user"
                                                            + " accounts on other services.")
                                                .recommendation(
                                                        "Use BCryptPasswordEncoder,"
                                                            + " Argon2PasswordEncoder, or"
                                                            + " SCryptPasswordEncoder from Spring"
                                                            + " Security. These are slow by design"
                                                            + " (tunable work factor) and include a"
                                                            + " per-password salt automatically."
                                                            + " Never implement password hashing"
                                                            + " manually.")
                                                .limitations(
                                                        "Medium confidence — MessageDigest may be"
                                                            + " used for non-password purposes such"
                                                            + " as checksums, ETags, or content"
                                                            + " addressing. Review the context to"
                                                            + " confirm the hash result is used for"
                                                            + " credential storage.")
                                                .evidence(
                                                        "MessageDigest.getInstance(\""
                                                                + algo
                                                                + "\") found in "
                                                                + relativePath
                                                                + ".")
                                                .source(relativePath, line)
                                                .build());
                            });
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
