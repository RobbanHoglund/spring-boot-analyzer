package com.robbanhoglund.springbootanalyzer.analyzer;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
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
 *   <li>{@link FindingRules#SPRING_INSECURE_TRUST_MANAGER} — {@code X509TrustManager} with an
 *       empty {@code checkServerTrusted}/{@code checkClientTrusted} body, or {@code
 *       HostnameVerifier} whose {@code verify} returns {@code true} unconditionally.
 *   <li>{@link FindingRules#SPRING_XXE_VULNERABLE_PARSER} — XML parser factory created without
 *       any {@code setFeature}/{@code setProperty} call disabling external entities.
 *   <li>{@link FindingRules#SPRING_INSECURE_DESERIALIZATION} — Jackson default-typing,
 *       {@code new ObjectInputStream(...)}, or SnakeYAML's {@code new Yaml()} default
 *       constructor.
 *   <li>{@link FindingRules#SPRING_SECURITY_HEADERS_DISABLED} — Spring Security HTTP response
 *       headers explicitly disabled (full {@code .headers().disable()}, or any of
 *       {@code frameOptions/xssProtection/contentTypeOptions}).
 *   <li>{@link FindingRules#SPRING_PERMIT_ALL_ANY_REQUEST} — {@code anyRequest().permitAll()}
 *       or {@code requestMatchers("/**").permitAll()} in a {@code SecurityFilterChain}.
 *   <li>{@link FindingRules#SPRING_H2_CONSOLE_PERMITALL} — H2 console path
 *       ({@code /h2-console**}) granted {@code permitAll()} in a security configuration.
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
        detectXxeVulnerableParser(cu, relativePath, findings);
        detectInsecureDeserialization(cu, relativePath, findings);
        detectSecurityHeadersDisabled(cu, relativePath, findings);
        detectPermitAllAnyRequest(cu, relativePath, findings);
        detectH2ConsolePermitAll(cu, relativePath, findings);

        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            for (MethodDeclaration method : cls.getMethods()) {
                detectPreAuthorizeOnPrivateMethod(cls, method, relativePath, findings);
            }
            detectInsecureTrustManager(cls, relativePath, findings);
            detectInsecureHostnameVerifier(cls, relativePath, findings);
        }
        detectInsecureHostnameVerifierLambda(cu, relativePath, findings);
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
    // Rule: SPRING_INSECURE_TRUST_MANAGER (TrustManager half)
    // ---------------------------------------------------------------------------

    private void detectInsecureTrustManager(
            ClassOrInterfaceDeclaration cls, String relativePath, List<Finding> findings) {
        boolean implementsX509TrustManager =
                cls.getImplementedTypes().stream()
                        .anyMatch(t -> simpleName(t.getNameAsString()).equals("X509TrustManager"));
        if (!implementsX509TrustManager) {
            return;
        }
        for (MethodDeclaration method : cls.getMethods()) {
            String name = method.getNameAsString();
            if (!"checkServerTrusted".equals(name) && !"checkClientTrusted".equals(name)) {
                continue;
            }
            if (method.getBody().isEmpty()) {
                continue;
            }
            if (!method.getBody().get().getStatements().isEmpty()) {
                continue;
            }
            Integer line = method.getBegin().map(p -> p.line).orElse(null);
            String target = cls.getNameAsString() + "#" + name;
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_INSECURE_TRUST_MANAGER,
                                    FindingConfidence.HIGH)
                            .shortMessage(
                                    "X509TrustManager."
                                            + name
                                            + " has an empty body in "
                                            + relativePath
                                            + " — TLS certificate validation is disabled.")
                            .whyBadPractice(
                                    "An X509TrustManager whose check method does nothing and never"
                                        + " throws CertificateException accepts every server"
                                        + " certificate, including self-signed certificates from a"
                                        + " man-in-the-middle proxy.")
                            .possibleImpact(
                                    "All HTTPS traffic from this application can be silently"
                                            + " intercepted, allowing credential theft and data"
                                            + " tampering.")
                            .recommendation(
                                    "Remove the custom TrustManager and rely on the default JVM"
                                        + " trust store. If a self-signed certificate is required"
                                        + " for testing, install it in the JVM trust store via"
                                        + " keytool instead of bypassing validation.")
                            .limitations(
                                    "High confidence — an empty implementation has no legitimate"
                                            + " production purpose.")
                            .evidence(
                                    "X509TrustManager."
                                            + name
                                            + " with empty body found in "
                                            + relativePath
                                            + ".")
                            .source(relativePath, line)
                            .target(target)
                            .build());
        }
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_INSECURE_TRUST_MANAGER (HostnameVerifier half)
    // ---------------------------------------------------------------------------

    private void detectInsecureHostnameVerifier(
            ClassOrInterfaceDeclaration cls, String relativePath, List<Finding> findings) {
        boolean implementsHostnameVerifier =
                cls.getImplementedTypes().stream()
                        .anyMatch(t -> simpleName(t.getNameAsString()).equals("HostnameVerifier"));
        if (!implementsHostnameVerifier) {
            return;
        }
        for (MethodDeclaration method : cls.getMethods()) {
            if (!"verify".equals(method.getNameAsString())) {
                continue;
            }
            if (method.getBody().isEmpty()) {
                continue;
            }
            boolean returnsTrueUnconditionally =
                    method.getBody().get().getStatements().size() == 1
                            && method.getBody().get().getStatement(0) instanceof ReturnStmt ret
                            && ret.getExpression()
                                    .filter(e -> e instanceof BooleanLiteralExpr)
                                    .map(e -> ((BooleanLiteralExpr) e).getValue())
                                    .orElse(false);
            if (!returnsTrueUnconditionally) {
                continue;
            }
            Integer line = method.getBegin().map(p -> p.line).orElse(null);
            reportInsecureHostnameVerifier(
                    relativePath,
                    line,
                    cls.getNameAsString() + "#verify",
                    "HostnameVerifier.verify always returns true",
                    findings);
        }
    }

    private void detectInsecureHostnameVerifierLambda(
            CompilationUnit cu, String relativePath, List<Finding> findings) {
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            if (!"setHostnameVerifier".equals(call.getNameAsString())) {
                continue;
            }
            for (Expression arg : call.getArguments()) {
                if (!(arg instanceof LambdaExpr lambda)) {
                    continue;
                }
                Expression body = null;
                if (lambda.getBody().isExpressionStmt()) {
                    body = lambda.getBody().asExpressionStmt().getExpression();
                } else if (lambda.getExpressionBody().isPresent()) {
                    body = lambda.getExpressionBody().get();
                } else if (lambda.getBody().isBlockStmt()
                        && lambda.getBody().asBlockStmt().getStatements().size() == 1
                        && lambda.getBody().asBlockStmt().getStatement(0) instanceof ReturnStmt r) {
                    body = r.getExpression().orElse(null);
                }
                boolean unconditionalTrue =
                        body instanceof BooleanLiteralExpr lit && lit.getValue();
                if (!unconditionalTrue) {
                    continue;
                }
                Integer line = call.getBegin().map(p -> p.line).orElse(null);
                reportInsecureHostnameVerifier(
                        relativePath,
                        line,
                        null,
                        "setHostnameVerifier((h, s) -> true) accepts any hostname",
                        findings);
            }
        }
    }

    private void reportInsecureHostnameVerifier(
            String relativePath,
            Integer line,
            String target,
            String shortMessageDetail,
            List<Finding> findings) {
        var builder =
                FindingFactory.builder(
                                FindingRules.SPRING_INSECURE_TRUST_MANAGER, FindingConfidence.HIGH)
                        .shortMessage(shortMessageDetail + " in " + relativePath + ".")
                        .whyBadPractice(
                                "A HostnameVerifier that returns true for every host disables"
                                    + " hostname verification: the TLS connection completes even"
                                    + " when the certificate was issued to a different domain.")
                        .possibleImpact(
                                "A man-in-the-middle attacker presenting any valid-but-unrelated"
                                        + " certificate can intercept traffic that the application"
                                        + " believes is going to the intended host.")
                        .recommendation(
                                "Remove the custom HostnameVerifier and rely on the default."
                                        + " If a specific alternate hostname must be accepted (e.g."
                                        + " an internal CA), constrain the verifier to that exact"
                                        + " hostname rather than returning true unconditionally.")
                        .limitations("High confidence — verifier returns true unconditionally.")
                        .evidence(shortMessageDetail + " found in " + relativePath + ".")
                        .source(relativePath, line);
        if (target != null) {
            builder.target(target);
        }
        findings.add(builder.build());
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_XXE_VULNERABLE_PARSER
    // ---------------------------------------------------------------------------

    private static final Set<String> XXE_FACTORY_TYPES =
            Set.of(
                    "DocumentBuilderFactory",
                    "SAXParserFactory",
                    "XMLInputFactory",
                    "TransformerFactory");

    private void detectXxeVulnerableParser(
            CompilationUnit cu, String relativePath, List<Finding> findings) {
        // Skip files that look like XML security hardening helpers (e.g. test fixtures).
        boolean fileSetsFeature =
                cu.findAll(MethodCallExpr.class).stream()
                        .anyMatch(
                                m ->
                                        "setFeature".equals(m.getNameAsString())
                                                || "setProperty".equals(m.getNameAsString())
                                                || "setXIncludeAware".equals(m.getNameAsString())
                                                || "setExpandEntityReferences"
                                                        .equals(m.getNameAsString()));
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            String n = call.getNameAsString();
            if (!"newInstance".equals(n) && !"newFactory".equals(n)) {
                continue;
            }
            String scopeType = call.getScope().map(s -> simpleName(s.toString())).orElse("");
            if (!XXE_FACTORY_TYPES.contains(scopeType)) {
                continue;
            }
            if (fileSetsFeature) {
                continue; // Best-effort heuristic — assume hardening is in place.
            }
            Integer line = call.getBegin().map(p -> p.line).orElse(null);
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_XXE_VULNERABLE_PARSER,
                                    FindingConfidence.MEDIUM)
                            .shortMessage(
                                    scopeType
                                            + "."
                                            + n
                                            + "() in "
                                            + relativePath
                                            + " — no setFeature/setProperty call disables external"
                                            + " entities anywhere in this file.")
                            .whyBadPractice(
                                    "Java's default XML parsers expand external entities and honour"
                                        + " DOCTYPE declarations. A malicious XML document can read"
                                        + " arbitrary local files (file:///etc/passwd), trigger"
                                        + " internal-network SSRF, or cause denial of service via"
                                        + " billion-laughs expansion.")
                            .possibleImpact(
                                    "Any endpoint or job that parses externally supplied XML can be"
                                        + " coerced into reading local files, making outbound HTTP"
                                        + " requests, or exhausting CPU/memory.")
                            .recommendation(
                                    "Disable external entities and DOCTYPE before using the parser:"
                                        + " factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,"
                                        + " true);"
                                        + " factory.setFeature(\"http://apache.org/xml/features/disallow-doctype-decl\","
                                        + " true); factory.setXIncludeAware(false);"
                                        + " factory.setExpandEntityReferences(false). Or use"
                                        + " OWASP's safe XML parsing utility.")
                            .limitations(
                                    "Medium confidence — heuristic checks the same file only. If"
                                            + " hardening is performed in a helper class the parser"
                                            + " may still be safe.")
                            .evidence(
                                    scopeType
                                            + "."
                                            + n
                                            + "() found in "
                                            + relativePath
                                            + " with no setFeature/setProperty hardening in the"
                                            + " same file.")
                            .source(relativePath, line)
                            .build());
        }
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_INSECURE_DESERIALIZATION
    // ---------------------------------------------------------------------------

    private void detectInsecureDeserialization(
            CompilationUnit cu, String relativePath, List<Finding> findings) {
        // Jackson polymorphic typing: enableDefaultTyping / activateDefaultTyping
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            if (!"enableDefaultTyping".equals(name) && !"activateDefaultTyping".equals(name)) {
                continue;
            }
            Integer line = call.getBegin().map(p -> p.line).orElse(null);
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_INSECURE_DESERIALIZATION,
                                    FindingConfidence.HIGH)
                            .shortMessage(
                                    "Jackson polymorphic deserialization enabled via "
                                            + name
                                            + "() in "
                                            + relativePath
                                            + ".")
                            .whyBadPractice(
                                    "Jackson default typing instructs the deserializer to honour a"
                                        + " @class type hint in the JSON payload and instantiate"
                                        + " the named class. Many classes on a typical classpath"
                                        + " are known gadget chains that achieve remote code"
                                        + " execution when constructed with attacker-chosen state.")
                            .possibleImpact(
                                    "An attacker who can submit JSON to any deserializing endpoint"
                                            + " can execute arbitrary code on the server.")
                            .recommendation(
                                    "Do not enable default typing. If polymorphism is required, use"
                                        + " @JsonTypeInfo with an explicit, restrictive subtype"
                                        + " allowlist via @JsonSubTypes, or use a"
                                        + " BasicPolymorphicTypeValidator that only allows known"
                                        + " base types.")
                            .limitations(
                                    "High confidence — both APIs are widely flagged in CVE"
                                            + " advisories.")
                            .evidence(name + "() call found in " + relativePath + ".")
                            .source(relativePath, line)
                            .build());
        }

        // Java serialization: new ObjectInputStream(...) anywhere is risky for untrusted input.
        // SnakeYAML: new Yaml() (no-arg) uses an unsafe Constructor.
        for (ObjectCreationExpr creation : cu.findAll(ObjectCreationExpr.class)) {
            String typeName = simpleName(creation.getType().getNameAsString());
            if ("ObjectInputStream".equals(typeName)) {
                Integer line = creation.getBegin().map(p -> p.line).orElse(null);
                findings.add(
                        FindingFactory.builder(
                                        FindingRules.SPRING_INSECURE_DESERIALIZATION,
                                        FindingConfidence.MEDIUM)
                                .shortMessage(
                                        "new ObjectInputStream(...) in "
                                                + relativePath
                                                + " — Java serialization of untrusted input is a"
                                                + " known RCE vector.")
                                .whyBadPractice(
                                        "ObjectInputStream.readObject reconstructs arbitrary class"
                                            + " graphs and invokes readObject / readResolve on each"
                                            + " element. Many libraries on a normal classpath are"
                                            + " published gadget chains that achieve remote code"
                                            + " execution.")
                                .possibleImpact(
                                        "If the input stream is ever attacker-controlled (queue"
                                            + " message, cache value, uploaded file), the server"
                                            + " can be remotely compromised.")
                                .recommendation(
                                        "Replace Java serialization with a structured format such"
                                            + " as JSON or Protocol Buffers. If unavoidable,"
                                            + " install a strict ObjectInputFilter allowlist via"
                                            + " ObjectInputFilter.Config.")
                                .limitations(
                                        "Medium confidence — flagged based on type alone. Some uses"
                                                + " (deserializing trusted local files) are safe in"
                                                + " practice.")
                                .evidence(
                                        "new ObjectInputStream(...) found in " + relativePath + ".")
                                .source(relativePath, line)
                                .build());
            } else if ("Yaml".equals(typeName) && creation.getArguments().isEmpty()) {
                Integer line = creation.getBegin().map(p -> p.line).orElse(null);
                findings.add(
                        FindingFactory.builder(
                                        FindingRules.SPRING_INSECURE_DESERIALIZATION,
                                        FindingConfidence.MEDIUM)
                                .shortMessage(
                                        "new Yaml() with no-arg constructor in "
                                                + relativePath
                                                + " — SnakeYAML's default Constructor can"
                                                + " instantiate arbitrary classes.")
                                .whyBadPractice(
                                        "SnakeYAML's default Constructor honours the !!javaClass"
                                            + " tag in the document and reflectively instantiates"
                                            + " the named class. Several gadget chains on a typical"
                                            + " Spring Boot classpath escalate this to remote code"
                                            + " execution.")
                                .possibleImpact(
                                        "An attacker controlling the YAML input can execute"
                                                + " arbitrary code on the server.")
                                .recommendation(
                                        "Use new Yaml(new SafeConstructor(new LoaderOptions())) or"
                                            + " a Constructor configured with a restrictive type"
                                            + " allowlist. SnakeYAML 2.x defaults to"
                                            + " SafeConstructor for the no-arg path — verify the"
                                            + " version.")
                                .limitations(
                                        "Medium confidence — SnakeYAML 2.x changed the default to"
                                                + " be safe; the rule cannot determine the bundled"
                                                + " version statically.")
                                .evidence("new Yaml() call found in " + relativePath + ".")
                                .source(relativePath, line)
                                .build());
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_SECURITY_HEADERS_DISABLED
    // ---------------------------------------------------------------------------

    private static final Set<String> HEADER_CONFIG_NAMES =
            Set.of(
                    "headers",
                    "frameOptions",
                    "xssProtection",
                    "contentTypeOptions",
                    "httpStrictTransportSecurity",
                    "contentSecurityPolicy");

    private void detectSecurityHeadersDisabled(
            CompilationUnit cu, String relativePath, List<Finding> findings) {
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            if (!"disable".equals(call.getNameAsString())) {
                continue;
            }
            String scopeName =
                    call.getScope()
                            .filter(s -> s instanceof MethodCallExpr)
                            .map(s -> ((MethodCallExpr) s).getNameAsString())
                            .orElse("");
            if (!HEADER_CONFIG_NAMES.contains(scopeName)) {
                continue;
            }
            if ("headers".equals(scopeName) && !isInsideSpringSecurityConfig(call)) {
                // The bare name "headers" can collide with non-security DSLs; restrict to
                // contexts that look like a Spring Security SecurityFilterChain configuration.
                continue;
            }
            Integer line = call.getBegin().map(p -> p.line).orElse(null);
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_SECURITY_HEADERS_DISABLED,
                                    FindingConfidence.MEDIUM)
                            .shortMessage(
                                    "."
                                            + scopeName
                                            + "().disable() turns off a Spring Security HTTP header"
                                            + " in "
                                            + relativePath
                                            + ".")
                            .whyBadPractice(
                                    "Spring Security enables a small set of HTTP response headers"
                                        + " (X-Frame-Options, X-Content-Type-Options,"
                                        + " Strict-Transport-Security, an XSS reflection filter,"
                                        + " and an optional Content-Security-Policy) by default."
                                        + " They are browser-enforced defenses against"
                                        + " clickjacking, content sniffing, and TLS downgrade.")
                            .possibleImpact(
                                    "The application becomes vulnerable to clickjacking via"
                                        + " iframe-embedding, content-type confusion attacks, and"
                                        + " (for the HSTS variant) protocol-downgrade attacks.")
                            .recommendation(
                                    "Keep the default headers enabled. If a single header conflicts"
                                        + " with a specific use case (e.g. SAMEORIGIN frame"
                                        + " embedding from a known partner), configure that header"
                                        + " instead of disabling it entirely.")
                            .limitations(
                                    "Medium confidence — some applications legitimately disable a"
                                        + " single header (frameOptions for OAuth pop-ups,"
                                        + " httpStrictTransportSecurity behind a TLS-terminating"
                                        + " proxy). Review the surrounding context.")
                            .evidence(
                                    "." + scopeName + "().disable() found in " + relativePath + ".")
                            .source(relativePath, line)
                            .build());
        }
    }

    private static boolean isInsideSpringSecurityConfig(MethodCallExpr call) {
        // Walk up parents and look for a chain that includes a known security DSL anchor.
        var node = call.getParentNode();
        while (node.isPresent()) {
            var n = node.get();
            String repr = n.toString();
            if (repr.contains("HttpSecurity")
                    || repr.contains("SecurityFilterChain")
                    || repr.contains("authorizeHttpRequests")
                    || repr.contains("authorizeRequests")) {
                return true;
            }
            node = n.getParentNode();
        }
        return false;
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_PERMIT_ALL_ANY_REQUEST
    // ---------------------------------------------------------------------------

    private void detectPermitAllAnyRequest(
            CompilationUnit cu, String relativePath, List<Finding> findings) {
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            if (!"permitAll".equals(call.getNameAsString())) {
                continue;
            }
            var scope = call.getScope();
            if (scope.isEmpty() || !(scope.get() instanceof MethodCallExpr scopeCall)) {
                continue;
            }
            String scopeName = scopeCall.getNameAsString();
            boolean matchesAnyRequest = "anyRequest".equals(scopeName);
            boolean matchesWildcardMatcher =
                    ("requestMatchers".equals(scopeName) || "antMatchers".equals(scopeName))
                            && scopeCall.getArguments().stream()
                                    .filter(a -> a instanceof StringLiteralExpr)
                                    .map(a -> ((StringLiteralExpr) a).asString())
                                    .anyMatch(s -> "/**".equals(s) || "/*".equals(s));
            if (!matchesAnyRequest && !matchesWildcardMatcher) {
                continue;
            }
            Integer line = call.getBegin().map(p -> p.line).orElse(null);
            String shape =
                    matchesAnyRequest
                            ? "anyRequest().permitAll()"
                            : scopeName + "(\"/**\").permitAll()";
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_PERMIT_ALL_ANY_REQUEST,
                                    FindingConfidence.HIGH)
                            .shortMessage(
                                    shape
                                            + " grants unauthenticated access to every endpoint in "
                                            + relativePath
                                            + ".")
                            .whyBadPractice(
                                    "permitAll() on the catch-all matcher removes authentication"
                                        + " from the entire application. Because the rule sits at"
                                        + " the end of the chain, any more specific matchers above"
                                        + " it still apply — but anything that does not match a"
                                        + " preceding rule is now public.")
                            .possibleImpact(
                                    "Every endpoint that is not explicitly secured by an earlier"
                                            + " matcher is reachable without authentication,"
                                            + " including any controller a developer later adds.")
                            .recommendation(
                                    "Replace the catch-all permitAll() with"
                                        + " .anyRequest().authenticated() (or .denyAll() if the app"
                                        + " is purely public-content), and grant permitAll only to"
                                        + " specific whitelisted paths.")
                            .limitations(
                                    "High confidence for the exact patterns checked. Some apps"
                                        + " intentionally publish read-only public APIs; review the"
                                        + " controllers behind the chain.")
                            .evidence(shape + " found in " + relativePath + ".")
                            .source(relativePath, line)
                            .build());
        }
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_H2_CONSOLE_PERMITALL
    // ---------------------------------------------------------------------------

    private void detectH2ConsolePermitAll(
            CompilationUnit cu, String relativePath, List<Finding> findings) {
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            if (!"permitAll".equals(call.getNameAsString())) {
                continue;
            }
            var scope = call.getScope();
            if (scope.isEmpty() || !(scope.get() instanceof MethodCallExpr scopeCall)) {
                continue;
            }
            String scopeName = scopeCall.getNameAsString();
            if (!"requestMatchers".equals(scopeName) && !"antMatchers".equals(scopeName)) {
                continue;
            }
            boolean targetsH2Console =
                    scopeCall.getArguments().stream()
                            .filter(a -> a instanceof StringLiteralExpr)
                            .map(a -> ((StringLiteralExpr) a).asString())
                            .anyMatch(s -> s.contains("/h2-console") || s.contains("/h2"));
            if (!targetsH2Console) {
                continue;
            }
            Integer line = call.getBegin().map(p -> p.line).orElse(null);
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_H2_CONSOLE_PERMITALL,
                                    FindingConfidence.HIGH)
                            .shortMessage(
                                    "Security configuration permits unauthenticated access to the"
                                            + " H2 console path in "
                                            + relativePath
                                            + ".")
                            .whyBadPractice(
                                    "The H2 web console accepts arbitrary SQL through a JDBC"
                                        + " connection and can run Java stored procedures, making"
                                        + " it a remote-code-execution surface. permitAll() on the"
                                        + " /h2-console path removes the only access control in"
                                        + " front of it.")
                            .possibleImpact(
                                    "Any client that can reach the application port can read and"
                                            + " write every row in the database and, on most"
                                            + " configurations, execute arbitrary Java code on the"
                                            + " host.")
                            .recommendation(
                                    "Remove the permitAll() on /h2-console — require"
                                        + " authentication, restrict it to a developer-only role,"
                                        + " or remove the H2 dependency entirely from production"
                                        + " builds.")
                            .limitations(
                                    "High confidence — there is essentially no production reason"
                                            + " to expose the H2 console unauthenticated.")
                            .evidence(
                                    scopeName
                                            + "(\".../h2-console...\").permitAll() found in "
                                            + relativePath
                                            + ".")
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
