package com.robbanhoglund.springbootanalyzer.analyzer;

import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildInfo;
import com.robbanhoglund.springbootanalyzer.analyzer.model.DetectedClass;
import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingConfidence;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingFactory;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingRules;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingSeverity;
import com.robbanhoglund.springbootanalyzer.analyzer.model.HighlightRange;
import com.robbanhoglund.springbootanalyzer.analyzer.model.SourceLocation;
import com.robbanhoglund.springbootanalyzer.analyzer.model.SpringComponentType;
import com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.ApplicationProperty;
import com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.ConfigurationAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.PropertyReference;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleModelAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.http.HttpSurfaceAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.http.InboundEndpoint;
import com.robbanhoglund.springbootanalyzer.analyzer.model.http.OutboundEndpoint;
import com.robbanhoglund.springbootanalyzer.analyzer.model.runtime.RuntimeStackAnalysis;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LiteralStringValueExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.EmptyStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.UnionType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public class StaticPracticeFindingAnalyzer {

    private static final Set<String> PROD_PROFILES = Set.of("prod", "production", "staging");
    private static final Set<String> SENSITIVE_MARKERS = Set.of(
            "password", "passwd", "secret", "client-secret", "api-key", "apikey", "access-key", "private-key",
            "credential", "credentials", "authorization", "api-token", "access-token", "refresh-token", "bearer-token", "auth-token",
            "oauth-token", "github-token", "signing-key", "pat", "jwt-secret"
    );
    private static final Set<String> NON_SECRET_TOKEN_MARKERS = Set.of(
            "max-output-tokens", "max-tokens", "token-limit", "token-count", "token-budget", "tokens-per-minute",
            "tokens-per-request", "tokenizer", "token-window", "output-tokens", "input-tokens",
            "input-token-budget", "output-token-budget"
    );
    private static final Set<String> HTTP_WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> WRITE_CALL_MARKERS = Set.of(
            "save", "saveAll", "delete", "deleteAll", "update", "insert", "batchUpdate", "execute", "persist", "merge", "flush"
    );
    private static final Set<String> IGNORE_VARIABLE_NAMES = Set.of("ignored", "ignore", "expected", "intentionallyignored");
    private static final Set<String> BENIGN_IGNORE_COMMENT_MARKERS = Set.of(
            "best effort cleanup", "ignore close failure", "already closed", "not relevant in test",
            "safe to ignore", "cleanup only", "best effort", "close failure"
    );
    private static final Pattern FLYWAY_MIGRATION_PATTERN = Pattern.compile("V(?<version>[0-9][^_]+)__.+\\.sql", Pattern.CASE_INSENSITIVE);
    private static final Set<String> MESSAGING_LISTENER_ANNOTATIONS = Set.of("KafkaListener", "RabbitListener", "JmsListener", "SqsListener");
    private static final Set<String> SENSITIVE_PARAM_NAMES = Set.of(
            "password", "passwd", "secret", "token", "apikey", "api_key", "api-key",
            "credential", "credentials", "authorization", "private_key", "private-key",
            "access_token", "access-token", "refresh_token", "refresh-token",
            "client_secret", "client-secret", "jwt", "jwt_secret", "jwt-secret"
    );
    private static final Pattern VALUE_NO_DEFAULT_PATTERN = Pattern.compile("\\$\\{[^}:]+\\}");
    private final JavaParser javaParser;

    public StaticPracticeFindingAnalyzer() {
        this.javaParser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25)
                .setCharacterEncoding(StandardCharsets.UTF_8));
    }

    public List<Finding> analyze(
            Path repositoryRoot,
            BuildInfo buildInfo,
            ConfigurationAnalysis configurationAnalysis,
            GradleModelAnalysis gradleModelAnalysis,
            RuntimeStackAnalysis runtimeStackAnalysis,
            HttpSurfaceAnalysis httpSurfaceAnalysis,
            List<DetectedClass> detectedClasses
    ) {
        List<Finding> findings = new ArrayList<>();
        detectSensitiveProfileDuplication(configurationAnalysis, findings);
        detectAdditionalRiskyConfiguration(configurationAnalysis, findings);
        detectCrossProfileDrift(configurationAnalysis, findings);
        detectConditionalBeanMatrixIssues(configurationAnalysis, findings);
        detectFlywaySchemaRisks(repositoryRoot, buildInfo, configurationAnalysis, gradleModelAnalysis, findings);
        detectMissingSecurityStarter(buildInfo, findings);
        detectOpenInViewNotDisabled(configurationAnalysis, findings);
        detectSourcePractices(repositoryRoot, httpSurfaceAnalysis, detectedClasses, findings);
        detectRepeatedFallbackParsingPattern(findings);
        return dedupe(findings);
    }

    private void detectSensitiveProfileDuplication(ConfigurationAnalysis configurationAnalysis, List<Finding> findings) {
        Map<String, List<ApplicationProperty>> grouped = groupPropertiesByName(configurationAnalysis);
        for (Map.Entry<String, List<ApplicationProperty>> entry : grouped.entrySet()) {
            if (!isSensitivePropertyName(entry.getKey())) {
                continue;
            }
            List<ApplicationProperty> configured = entry.getValue().stream()
                    .filter(property -> property.sourceFile() != null)
                    .toList();
            if (configured.size() < 2) {
                continue;
            }
            String evidence = configured.stream()
                    .map(property -> property.sourceFile() + (property.profile() == null ? "" : " [" + property.profile() + "]"))
                    .collect(Collectors.joining(", "));
            boolean anyLiteralOrWeakDefault = configured.stream()
                    .anyMatch(property -> property.valueRedacted() && !property.placeholderValue());
            findings.add(FindingFactory.builder(
                            FindingRules.SPRING_SECRET_MULTI_PROFILE.ruleId(),
                            FindingRules.SPRING_SECRET_MULTI_PROFILE.title(),
                            anyLiteralOrWeakDefault ? com.robbanhoglund.springbootanalyzer.analyzer.model.FindingSeverity.WARNING : FindingRules.SPRING_SECRET_MULTI_PROFILE.defaultSeverity(),
                            anyLiteralOrWeakDefault ? com.robbanhoglund.springbootanalyzer.analyzer.model.FindingCategory.SECURITY : FindingRules.SPRING_SECRET_MULTI_PROFILE.category(),
                            FindingRules.SPRING_SECRET_MULTI_PROFILE.runtimeDetection(),
                            FindingConfidence.MEDIUM
                    )
                    .shortMessage("Sensitive property is configured in multiple profiles: " + entry.getKey())
                    .whyBadPractice("Keeping the same sensitive property across several profile files makes secrets harder to rotate consistently and easier to copy between environments.")
                    .possibleImpact("Different deployments may drift, stale secrets can survive in older profiles, and operational rotation becomes more error-prone.")
                    .recommendation("Centralize the secret in environment-specific secret storage and reference it from each profile rather than duplicating static values.")
                    .evidence("Sensitive property definitions were found in multiple configuration sources: " + evidence + ".")
                    .limitations("Static analysis cannot prove whether the values are identical because sensitive values are redacted before presentation.")
                    .target(entry.getKey())
                    .location("Configuration")
                    .build());
        }
    }

    private void detectAdditionalRiskyConfiguration(ConfigurationAnalysis configurationAnalysis, List<Finding> findings) {
        for (ApplicationProperty property : configurationAnalysis == null ? List.<ApplicationProperty>of() : configurationAnalysis.properties()) {
            if (property == null || property.name() == null || property.value() == null) {
                continue;
            }
            String profile = normalizedProfile(property.profile());
            boolean prodLike = isProdLikeProfile(profile);
            String name = property.name();
            String value = property.value();
            if (prodLike && "spring.flyway.clean-disabled".equals(name) && "false".equalsIgnoreCase(value)) {
                findings.add(configFinding(
                        "spring.flyway.clean-disabled=false can enable destructive Flyway clean operations in production.",
                        "Allowing Flyway clean in production weakens an important safety barrier around destructive schema operations.",
                        "An accidental clean call can wipe application schemas or make recovery much harder during an incident.",
                        "Keep spring.flyway.clean-disabled=true outside disposable environments and reserve clean operations for controlled maintenance workflows.",
                        property,
                        name,
                        FindingConfidence.HIGH
                ));
            } else if (prodLike && "debug".equals(name) && "true".equalsIgnoreCase(value)) {
                findings.add(configFinding(
                        "debug=true can expose verbose auto-configuration details in production-oriented configuration.",
                        "Spring debug mode is useful locally, but it increases startup verbosity and internal diagnostics in environments where minimal disclosure is safer.",
                        "Verbose debug output can leak more internal wiring details into logs and make production logging noisier during incidents.",
                        "Keep debug disabled in production profiles and enable targeted package-level logging only during troubleshooting windows.",
                        property,
                        name,
                        FindingConfidence.HIGH
                ));
            } else if (prodLike && "server.error.include-stacktrace".equals(name) && "always".equalsIgnoreCase(value)) {
                findings.add(configFinding(
                        "server.error.include-stacktrace=always can expose stack traces through HTTP error responses.",
                        "Stack traces are useful for debugging but they reveal internal implementation details when sent back to clients.",
                        "Unexpected stack traces can disclose package names, library versions, and code paths during failures.",
                        "Prefer never or on-param outside local development, and use structured server logs for detailed diagnostics.",
                        property,
                        name,
                        FindingConfidence.HIGH
                ));
            } else if (prodLike && "server.error.include-message".equals(name) && "always".equalsIgnoreCase(value)) {
                findings.add(configFinding(
                        "server.error.include-message=always can expose internal failure messages to clients.",
                        "Detailed error messages are useful for debugging but they increase how much internal application state is returned at the HTTP boundary.",
                        "Users and automated clients may receive messages that reveal validation internals, dependency failures, or operational context.",
                        "Prefer never or on-param outside development and keep detailed diagnostics in logs and observability tooling.",
                        property,
                        name,
                        FindingConfidence.HIGH
                ));
            } else if (prodLike && "logging.level.root".equals(name)
                    && (value.equalsIgnoreCase("debug") || value.equalsIgnoreCase("trace"))) {
                findings.add(configFinding(
                        "logging.level.root=" + value + " can make production logs excessively verbose.",
                        "Root-level DEBUG or TRACE logging trades signal for noise and often exposes far more framework internals than operators need by default.",
                        "Production logs can become noisy, expensive, and more likely to contain sensitive request or infrastructure details.",
                        "Keep root logging at INFO or WARN in production and enable targeted package logging during controlled investigations.",
                        property,
                        name,
                        FindingConfidence.HIGH
                ));
            } else if (prodLike && "spring.main.lazy-initialization".equals(name) && "true".equalsIgnoreCase(value)) {
                findings.add(configFinding(
                        "spring.main.lazy-initialization=true can defer production failures until first use.",
                        "Lazy initialization speeds startup, but it moves dependency and bean-creation failures away from deployment time and into runtime traffic paths.",
                        "Production may appear healthy at startup and then fail only when a less frequently used code path is exercised.",
                        "Use lazy initialization cautiously and prefer explicit performance measurements before enabling it in production profiles.",
                        property,
                        name,
                        FindingConfidence.MEDIUM
                ));
            } else if (prodLike && (name.equals("springdoc.api-docs.enabled") || name.equals("springdoc.swagger-ui.enabled"))
                    && "true".equalsIgnoreCase(value)) {
                findings.add(configFinding(
                        name + "=true exposes API documentation tooling in a production-oriented profile.",
                        "Swagger and API documentation are useful operationally, but they widen the visible API surface and make administrative endpoints easier to discover.",
                        "Publicly reachable documentation can make exploration of sensitive or admin-like endpoints easier than intended.",
                        "Restrict API documentation to trusted environments or secure it behind explicit authentication and profile controls.",
                        property,
                        name,
                        FindingConfidence.HIGH
                ));
            } else if (prodLike && "spring.jpa.hibernate.ddl-auto".equals(name)
                    && (value.equalsIgnoreCase("create") || value.equalsIgnoreCase("create-drop"))) {
                findings.add(FindingFactory.builder(FindingRules.SPRING_DDL_AUTO_DESTRUCTIVE_PROD, FindingConfidence.HIGH)
                        .shortMessage("spring.jpa.hibernate.ddl-auto=" + value + " can destroy the database schema on startup in a production-oriented profile.")
                        .whyBadPractice("create and create-drop both drop and recreate tables at application startup. In production this destroys all existing data unconditionally.")
                        .possibleImpact("Every deployment will wipe the database. This is almost certainly unintended in a production or staging environment and cannot be undone.")
                        .recommendation("Use validate or none in production profiles. Manage schema changes through Flyway or Liquibase migrations.")
                        .evidence("spring.jpa.hibernate.ddl-auto=" + value + " was found in " + property.sourceFile() + ".")
                        .limitations("Static analysis cannot determine whether the target database is disposable or whether the profile is always active in deployment.")
                        .source(property.sourceFile(), property.line())
                        .target(name)
                        .build());
            } else if (prodLike && "spring.jpa.show-sql".equals(name) && "true".equalsIgnoreCase(value)) {
                findings.add(configFinding(
                        "spring.jpa.show-sql=true prints all SQL statements to stdout in a production-oriented profile.",
                        "SQL logging at the JPA layer bypasses the application logging framework, writes directly to stdout, and cannot be controlled by log-level configuration.",
                        "Production logs become noisy, schema details and query structures are exposed in log aggregation systems, and performance overhead is added on every query.",
                        "Use logging.level.org.hibernate.SQL=DEBUG or a query profiling tool instead, and keep it disabled in production profiles.",
                        property,
                        name,
                        FindingConfidence.HIGH
                ));
            } else if ("spring.jpa.open-in-view".equals(name) && "true".equalsIgnoreCase(value)) {
                findings.add(FindingFactory.builder(FindingRules.SPRING_JPA_OPEN_IN_VIEW, FindingConfidence.HIGH)
                        .shortMessage("spring.jpa.open-in-view=true is explicitly enabled.")
                        .whyBadPractice("Open-session-in-view keeps the Hibernate session open through the entire HTTP request, including the view rendering phase. This silently enables lazy loading outside the service layer and masks N+1 query problems.")
                        .possibleImpact("Unexpected database queries fire during serialization or view rendering, making performance problems hard to diagnose and reproduce. Transactions may hold database connections longer than necessary.")
                        .recommendation("Set spring.jpa.open-in-view=false and load all required data explicitly in the service layer using DTO projections, JOIN FETCH, or entity graphs.")
                        .evidence("spring.jpa.open-in-view=true was found in " + property.sourceFile() + ".")
                        .limitations("Some applications intentionally use open-in-view for simplicity in read-heavy screens. Verify whether lazy loading outside the service layer is an intentional design choice.")
                        .source(property.sourceFile(), property.line())
                        .target(name)
                        .build());
            }
        }
    }

    private Finding configFinding(
            String shortMessage,
            String whyBadPractice,
            String possibleImpact,
            String recommendation,
            ApplicationProperty property,
            String target,
            FindingConfidence confidence
    ) {
        return FindingFactory.builder(FindingRules.SPRING_RISKY_PROD_CONFIG, confidence)
                .shortMessage(shortMessage)
                .whyBadPractice(whyBadPractice)
                .possibleImpact(possibleImpact)
                .recommendation(recommendation)
                .evidence(target + " was found in " + property.sourceFile() + ".")
                .limitations("Static analysis cannot prove whether this profile is always active in deployment, but the filename or profile marker indicates production-oriented usage.")
                .source(property.sourceFile(), property.line())
                .target(target)
                .build();
    }

    private void detectCrossProfileDrift(ConfigurationAnalysis configurationAnalysis, List<Finding> findings) {
        Map<String, List<ApplicationProperty>> grouped = groupPropertiesByName(configurationAnalysis);
        for (Map.Entry<String, List<ApplicationProperty>> entry : grouped.entrySet()) {
            List<ApplicationProperty> properties = entry.getValue();
            Set<String> profiles = properties.stream()
                    .map(ApplicationProperty::profile)
                    .filter(Objects::nonNull)
                    .map(this::normalizedProfile)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (profiles.size() < 2) {
                continue;
            }
            Set<String> distinctValues = properties.stream()
                    .map(ApplicationProperty::value)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (distinctValues.size() > 1 && isDriftRelevantProperty(entry.getKey())) {
                String evidence = properties.stream()
                        .map(property -> normalizedProfile(property.profile()) + "=" + renderedValue(property))
                        .collect(Collectors.joining(", "));
                findings.add(FindingFactory.builder(FindingRules.SPRING_PROFILE_DRIFT, FindingConfidence.HIGH)
                        .shortMessage("Configuration differs across profiles for " + entry.getKey())
                        .whyBadPractice("Spring evaluates only the active profile at runtime, so static drift between profile files is easy to miss during local startup checks.")
                        .possibleImpact("Different profiles may call different external services, expose different diagnostics, or enable different scheduling behavior after deployment.")
                        .recommendation("Review profile overrides together, document the intended environment-specific behavior, and add tests or smoke checks for critical profile combinations.")
                        .evidence("Profile values detected: " + evidence + ".")
                        .limitations("Static analysis cannot prove which profile is active in production or whether higher-precedence environment variables override these files.")
                        .target(entry.getKey())
                        .location("Configuration")
                        .build());
            }
        }
    }

    private void detectConditionalBeanMatrixIssues(ConfigurationAnalysis configurationAnalysis, List<Finding> findings) {
        if (configurationAnalysis == null || configurationAnalysis.codeReferences() == null) {
            return;
        }
        Map<String, List<PropertyReference>> grouped = configurationAnalysis.codeReferences().stream()
                .filter(reference -> "@ConditionalOnProperty".equals(reference.referenceType()))
                .collect(Collectors.groupingBy(PropertyReference::propertyName, LinkedHashMap::new, Collectors.toList()));
        Map<String, List<ApplicationProperty>> configuredByName = groupPropertiesByName(configurationAnalysis);
        for (Map.Entry<String, List<PropertyReference>> entry : grouped.entrySet()) {
            String propertyName = entry.getKey();
            List<PropertyReference> references = entry.getValue();
            long matchIfMissingCount = references.stream().filter(reference -> Boolean.TRUE.equals(reference.matchIfMissing())).count();
            if (matchIfMissingCount > 1) {
                PropertyReference reference = references.get(0);
                findings.add(FindingFactory.builder(FindingRules.SPRING_CONDITIONAL_MATCH_IF_MISSING_OVERLAP, FindingConfidence.HIGH)
                        .shortMessage("Multiple conditional beans use matchIfMissing=true for " + propertyName)
                        .whyBadPractice("Overlapping matchIfMissing defaults make the selected bean depend on classpath order and configuration gaps rather than explicit provider choices.")
                        .possibleImpact("Different environments may activate unexpected implementations when the controlling property is absent or misconfigured.")
                        .recommendation("Prefer explicit provider values, keep one default path at most, and add focused tests for each provider choice.")
                        .evidence("Conditional bean references for " + propertyName + " declared matchIfMissing=true in multiple locations.")
                        .limitations("Static analysis cannot fully simulate Spring condition ordering when additional custom conditions are involved.")
                        .source(reference.sourceFile(), null)
                        .target(propertyName)
                        .build());
            }
            List<ApplicationProperty> configured = configuredByName.getOrDefault(propertyName, List.of());
            Set<String> expectedValues = references.stream()
                    .map(PropertyReference::expectedValue)
                    .filter(value -> value != null && !value.isBlank())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            for (ApplicationProperty property : configured) {
                if (property.value() == null || property.placeholderValue() || expectedValues.isEmpty()) {
                    continue;
                }
                if (!expectedValues.contains(property.value())) {
                    findings.add(FindingFactory.builder(FindingRules.SPRING_CONDITIONAL_VALUE_MISMATCH, FindingConfidence.HIGH)
                            .shortMessage("Configured value for " + propertyName + " does not match any detected conditional bean.")
                            .whyBadPractice("Conditional bean matrices make runtime behavior depend on configuration values that may not be exercised in every environment.")
                            .possibleImpact("A profile can select no provider at all, or activate an unexpected fallback path that was not covered by tests.")
                            .recommendation("Document supported provider values, keep them explicit, and add focused tests for every expected value.")
                            .evidence(propertyName + "=" + property.value() + " was configured, while detected @ConditionalOnProperty values were " + String.join(", ", expectedValues) + ".")
                            .limitations("Static analysis cannot see custom condition logic, imported auto-configurations, or runtime overrides from environment variables.")
                            .source(property.sourceFile(), property.line())
                            .target(propertyName)
                            .build());
                }
            }
        }
    }

    private void detectFlywaySchemaRisks(
            Path repositoryRoot,
            BuildInfo buildInfo,
            ConfigurationAnalysis configurationAnalysis,
            GradleModelAnalysis gradleModelAnalysis,
            List<Finding> findings
    ) {
        boolean flywayPresent = dependencyPresent(buildInfo, gradleModelAnalysis, "org.flywaydb", "flyway-core")
                || hasProperty(configurationAnalysis, "spring.flyway.enabled");
        if (!flywayPresent) {
            return;
        }
        ApplicationProperty ddlAuto = findProperty(configurationAnalysis, "spring.jpa.hibernate.ddl-auto");
        if (ddlAuto != null && ddlAuto.value() != null
                && List.of("update", "create", "create-drop", "drop").contains(ddlAuto.value().toLowerCase(Locale.ROOT))) {
            findings.add(FindingFactory.builder(FindingRules.SPRING_FLYWAY_DDL_AUTO_MIX, FindingConfidence.HIGH)
                    .shortMessage("Flyway is enabled while Hibernate DDL auto-update is also configured.")
                    .whyBadPractice("Mixing migration-based schema management with automatic Hibernate schema mutation creates two competing sources of truth for database changes.")
                    .possibleImpact("Schema drift, unexpected startup mutations, and migration behavior that differs across environments become much harder to reason about.")
                    .recommendation("Use Flyway as the primary schema change mechanism and keep Hibernate DDL mutation disabled or limited to validate in shared environments.")
                    .evidence("Flyway was detected and " + ddlAuto.name() + "=" + ddlAuto.value() + " was found in " + ddlAuto.sourceFile() + ".")
                    .limitations("Static analysis cannot prove which schema tool wins in every environment, but the combination is operationally risky.")
                    .source(ddlAuto.sourceFile(), ddlAuto.line())
                    .target("schema management")
                    .build());
        }
        List<Path> migrations = migrationFiles(repositoryRoot);
        if (migrations.isEmpty()) {
            findings.add(FindingFactory.builder(FindingRules.SPRING_FLYWAY_MISSING_MIGRATIONS, FindingConfidence.MEDIUM)
                    .shortMessage("Flyway appears to be enabled, but no migration files were found under db/migration.")
                    .whyBadPractice("Schema migration tooling is most useful when migration files are versioned alongside the application.")
                    .possibleImpact("Deployments may rely on ad hoc schema changes or fail only after startup reaches database code paths.")
                    .recommendation("Check Flyway locations, commit reviewed migration files, and keep schema changes explicit in version control.")
                    .evidence("Flyway-related configuration or dependencies were detected, but no V__ migration files were found in src/main/resources/db/migration.")
                    .limitations("Static analysis looks in the conventional migration folder and may miss custom locations configured elsewhere.")
                    .location("db/migration")
                    .target("flyway migrations")
                    .build());
        } else {
            Map<String, List<Path>> byVersion = migrations.stream()
                    .map(path -> Map.entry(migrationVersion(path), path))
                    .filter(entry -> entry.getKey() != null)
                    .collect(Collectors.groupingBy(Map.Entry::getKey, LinkedHashMap::new, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
            byVersion.forEach((version, paths) -> {
                if (paths.size() > 1) {
                    findings.add(FindingFactory.builder(
                                    "SPRING_FLYWAY_DUPLICATE_VERSION",
                                    "Duplicate Flyway migration version detected",
                                    com.robbanhoglund.springbootanalyzer.analyzer.model.FindingSeverity.WARNING,
                                    com.robbanhoglund.springbootanalyzer.analyzer.model.FindingCategory.PERSISTENCE,
                                    com.robbanhoglund.springbootanalyzer.analyzer.model.FindingRuntimeDetection.NOT_NORMALLY_DETECTED,
                                    FindingConfidence.HIGH
                            )
                            .shortMessage("Flyway migration version " + version + " appears more than once.")
                            .whyBadPractice("Duplicate migration versions make migration ordering ambiguous and can fail at deployment time in ways that are hard to recover from quickly.")
                            .possibleImpact("Deployments may stop on migration validation errors or apply an unexpected migration ordering.")
                            .recommendation("Keep Flyway versions unique and monotonic, and review migration naming during code review.")
                            .evidence(paths.stream().map(path -> repositoryRoot.relativize(path).toString().replace('\\', '/')).collect(Collectors.joining(", ")))
                            .limitations("Static analysis checks file naming only and cannot validate migrations stored outside the repository.")
                            .target("Flyway migration version " + version)
                            .build());
                }
            });
        }
    }

    private void detectSourcePractices(
            Path repositoryRoot,
            HttpSurfaceAnalysis httpSurfaceAnalysis,
            List<DetectedClass> detectedClasses,
            List<Finding> findings
    ) {
        Path sourceRoot = repositoryRoot.resolve("src/main/java");
        if (Files.notExists(sourceRoot)) {
            return;
        }
        Map<String, List<OutboundEndpoint>> outboundByFile = (httpSurfaceAnalysis == null ? List.<OutboundEndpoint>of() : httpSurfaceAnalysis.outboundEndpoints()).stream()
                .collect(Collectors.groupingBy(endpoint -> endpoint.sourceFile() == null ? "" : endpoint.sourceFile(), LinkedHashMap::new, Collectors.toList()));
        Set<String> controllerClasses = detectedClasses.stream()
                .filter(item -> item.componentType() == SpringComponentType.REST_CONTROLLER || item.componentType() == SpringComponentType.CONTROLLER)
                .map(DetectedClass::fullyQualifiedClassName)
                .collect(Collectors.toSet());

        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path sourceFile : files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted(Comparator.naturalOrder())
                    .toList()) {
                parseSourcePractices(repositoryRoot, sourceFile, outboundByFile, controllerClasses, findings);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to scan Java sources for static practice findings", exception);
        }
    }

    private void parseSourcePractices(
            Path repositoryRoot,
            Path sourceFile,
            Map<String, List<OutboundEndpoint>> outboundByFile,
            Set<String> controllerClasses,
            List<Finding> findings
    ) throws IOException {
        var parseResult = javaParser.parse(sourceFile);
        if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
            return;
        }
        CompilationUnit compilationUnit = parseResult.getResult().orElseThrow();
        String relativePath = repositoryRoot.relativize(sourceFile).toString().replace('\\', '/');
        String fileContent = Files.readString(sourceFile, StandardCharsets.UTF_8);
        for (ClassOrInterfaceDeclaration declaration : compilationUnit.findAll(ClassOrInterfaceDeclaration.class)) {
            analyzeClassSourceSignals(declaration, relativePath, fileContent, outboundByFile.getOrDefault(relativePath, List.of()), controllerClasses, findings);
        }
    }

    private void analyzeClassSourceSignals(
            ClassOrInterfaceDeclaration declaration,
            String relativePath,
            String fileContent,
            List<OutboundEndpoint> outboundEndpoints,
            Set<String> controllerClasses,
            List<Finding> findings
    ) {
        if (isGeneratedSource(relativePath, declaration)) {
            return;
        }
        String packageName = declaration.findCompilationUnit()
                .flatMap(CompilationUnit::getPackageDeclaration)
                .map(value -> value.getNameAsString())
                .orElse("");
        String className = packageName.isBlank() ? declaration.getNameAsString() : packageName + "." + declaration.getNameAsString();
        boolean controllerLike = controllerClasses.contains(className)
                || hasAnyAnnotation(declaration.getAnnotations(), Set.of("RestController", "Controller", "ControllerAdvice", "RestControllerAdvice"));
        boolean serviceLike = hasAnyAnnotation(declaration.getAnnotations(), Set.of("Service", "Component"));
        boolean repositoryLike = hasAnyAnnotation(declaration.getAnnotations(), Set.of("Repository"));
        boolean entityLike = hasAnnotation(declaration.getAnnotations(), "Entity");
        boolean configurationLike = hasAnnotation(declaration.getAnnotations(), "Configuration");
        boolean configPropertiesLike = hasAnnotation(declaration.getAnnotations(), "ConfigurationProperties");
        boolean classTransactional = hasAnnotation(declaration.getAnnotations(), "Transactional");
        boolean startupInterface = implementsAny(declaration, Set.of("CommandLineRunner", "ApplicationRunner", "InitializingBean", "SmartLifecycle"));
        Set<String> transactionalMethods = declaration.getMethods().stream()
                .filter(method -> hasAnnotation(method.getAnnotations(), "Transactional") || classTransactional)
                .map(MethodDeclaration::getNameAsString)
                .collect(Collectors.toSet());

        if (!outboundEndpoints.isEmpty()) {
            detectHttpClientGaps(relativePath, fileContent, outboundEndpoints, findings);
        }

        for (FieldDeclaration field : declaration.getFields()) {
            if (hasAnnotation(field.getAnnotations(), "Autowired") && !field.isStatic()) {
                detectFieldInjection(relativePath, declaration, field, findings);
            }
            if (hasAnnotation(field.getAnnotations(), "Value")) {
                detectValueWithoutDefault(relativePath, declaration, field, findings);
            }
        }

        for (ConstructorDeclaration constructor : declaration.getConstructors()) {
            MethodSignals signals = methodSignals(constructor.getBody().toString(), constructor.getNameAsString());
            detectExceptionHandlingInConstructor(relativePath, className, declaration, constructor, controllerLike, serviceLike, repositoryLike, findings);
            if (signals.hasMeaningfulSideEffects()) {
                findings.add(FindingFactory.builder(FindingRules.SPRING_STARTUP_SIDE_EFFECT, FindingConfidence.MEDIUM)
                        .shortMessage("Constructor in " + declaration.getNameAsString() + " appears to perform side effects.")
                        .whyBadPractice("Constructors run during bean creation, so heavy or side-effecting work there makes object construction harder to reason about and harder to isolate in tests.")
                        .possibleImpact("Bean creation may trigger network calls, writes, or thread creation before the application is fully ready to handle failures safely.")
                        .recommendation("Keep constructors lightweight and move side effects behind explicit lifecycle hooks, background jobs, or service methods with clear error handling.")
                        .evidence("Constructor in " + relativePath + " performs " + signals.describe() + ".")
                        .limitations("Static analysis infers side effects from method calls and cannot prove runtime execution frequency.")
                        .source(relativePath, constructor.getBegin().map(position -> position.line).orElse(null))
                        .target(className)
                        .build());
            }
        }

        for (MethodDeclaration method : declaration.getMethods()) {
            MethodSignals signals = methodSignals(method.getBody().map(Object::toString).orElse(""), method.getNameAsString());
            boolean startupHook = isStartupHook(declaration, method, startupInterface);
            boolean scheduled = hasAnnotation(method.getAnnotations(), "Scheduled");
            detectExceptionHandlingInMethod(
                    relativePath,
                    className,
                    declaration,
                    method,
                    controllerLike,
                    startupHook,
                    scheduled,
                    serviceLike,
                    repositoryLike,
                    findings
            );

            if (startupHook && signals.hasMeaningfulSideEffects()) {
                findings.add(FindingFactory.builder(FindingRules.SPRING_STARTUP_SIDE_EFFECT, signals.directSignalConfidence())
                        .shortMessage("Startup hook " + declaration.getNameAsString() + "#" + method.getNameAsString() + " appears to perform side effects.")
                        .whyBadPractice("Startup hooks run as the application initializes. Heavy or side-effecting work there makes readiness depend on external systems and hidden background actions.")
                        .possibleImpact("Deployments can become slow or brittle, repeated restarts can replay work, and rollouts may fail for reasons unrelated to normal request handling.")
                        .recommendation("Move heavy work behind explicit admin actions, background jobs with idempotency, or controlled migration/backfill workflows.")
                        .evidence("Detected startup hook " + startupHookDescription(method, declaration) + " with " + signals.describe() + " in " + relativePath + ".")
                        .limitations("Static analysis cannot prove that every call is always executed, but the method is wired into startup lifecycle code.")
                        .source(relativePath, method.getBegin().map(position -> position.line).orElse(null))
                        .target(className + "#" + method.getNameAsString())
                        .build());
            }

            if (scheduled) {
                detectSchedulingRisks(relativePath, declaration, method, signals, findings);
            }

            if (serviceLike) {
                detectTransactionRisks(relativePath, declaration, method, transactionalMethods, repositoryLike, signals, findings);
            }

            if (controllerLike) {
                detectValidationGap(relativePath, declaration, method, signals, findings);
            }

            if (hasAnnotation(method.getAnnotations(), "Async")) {
                detectAsyncMethodRisks(relativePath, declaration, method, findings);
            }

            if (hasAnyAnnotation(method.getAnnotations(), MESSAGING_LISTENER_ANNOTATIONS)) {
                detectMessagingListenerRisks(relativePath, declaration, method, findings);
            }

            if (hasAnnotation(method.getAnnotations(), "Modifying")
                    && !hasAnnotation(method.getAnnotations(), "Transactional")
                    && !classTransactional) {
                detectModifyingNoTransaction(relativePath, declaration, method, findings);
            }

            if (scheduled && hasAnnotation(method.getAnnotations(), "Transactional")) {
                detectTransactionalOnScheduled(relativePath, declaration, method, findings);
            }

            if (controllerLike) {
                detectRequestMappingNoMethod(relativePath, declaration, method, findings);
                detectSensitiveRequestParams(relativePath, declaration, method, findings);
            }
        }

        if (entityLike) {
            detectJpaRelationshipRisks(relativePath, declaration, findings);
        }

        if (!configurationLike) {
            detectBeanInNonConfigurationClass(relativePath, declaration, findings);
        }

        if (configPropertiesLike && !hasAnnotation(declaration.getAnnotations(), "Validated")) {
            detectConfigPropertiesNotValidated(relativePath, declaration, findings);
        }

        detectCsrfDisabled(relativePath, declaration, findings);
        detectCorsAllowAll(relativePath, declaration, findings);
    }

    private void detectExceptionHandlingInConstructor(
            String relativePath,
            String className,
            ClassOrInterfaceDeclaration declaration,
            ConstructorDeclaration constructor,
            boolean controllerLike,
            boolean serviceLike,
            boolean repositoryLike,
            List<Finding> findings
    ) {
        ExceptionHandlingContext context = new ExceptionHandlingContext(
                relativePath,
                className,
                className + "#" + constructor.getNameAsString(),
                controllerLike,
                false,
                false,
                serviceLike,
                repositoryLike,
                false,
                true,
                false
        );
        detectPrintStackTrace(relativePath, context, constructor.findAll(MethodCallExpr.class), findings);
        for (CatchClause catchClause : constructor.findAll(CatchClause.class)) {
            analyzeCatchClause(context, catchClause, findings);
        }
    }

    private void detectExceptionHandlingInMethod(
            String relativePath,
            String className,
            ClassOrInterfaceDeclaration declaration,
            MethodDeclaration method,
            boolean controllerLike,
            boolean startupHook,
            boolean scheduled,
            boolean serviceLike,
            boolean repositoryLike,
            List<Finding> findings
    ) {
        boolean exceptionHandler = hasAnnotation(method.getAnnotations(), "ExceptionHandler");
        ExceptionHandlingContext context = new ExceptionHandlingContext(
                relativePath,
                className,
                className + "#" + method.getNameAsString(),
                controllerLike,
                startupHook,
                scheduled,
                serviceLike,
                repositoryLike,
                exceptionHandler,
                false,
                isTopLevelUncaughtHandler(declaration, method)
        );
        detectPrintStackTrace(relativePath, context, method.findAll(MethodCallExpr.class), findings);
        for (CatchClause catchClause : method.findAll(CatchClause.class)) {
            analyzeCatchClause(context, catchClause, findings);
        }
        if (exceptionHandler) {
            detectBroadSpringExceptionHandler(context, method, findings);
        }
    }

    private void analyzeCatchClause(
            ExceptionHandlingContext context,
            CatchClause catchClause,
            List<Finding> findings
    ) {
        CatchAnalysis analysis = analyzeCatchBody(catchClause.getBody(), catchClause.getParameter().getNameAsString());
        Set<String> caughtTypes = caughtTypeNames(catchClause);
        Integer line = catchClause.getBegin().map(position -> position.line).orElse(null);
        String primaryType = caughtTypes.isEmpty() ? catchClause.getParameter().getTypeAsString() : caughtTypes.iterator().next();
        String evidencePrefix = "Catch block for " + primaryType + " in " + context.target() + " (" + context.relativePath() + ":" + defaultString(line == null ? null : String.valueOf(line)) + ").";
        boolean specificRuleTriggered = false;
        SourceLocation catchLocation = catchLocation(context, catchClause);
        boolean broadCatch = caughtTypes.stream().anyMatch(this::isBroadCatchType);
        boolean likelyParserFallback = isLikelyParserFallback(context, caughtTypes, analysis);

        if (analysis.emptyLike()) {
            if (!analysis.intentionalIgnoreSafe()) {
                boolean testSource = context.relativePath().startsWith("src/test/");
                boolean commentOnly = analysis.commentOnly();
                FindingSeverity severity = (testSource || likelyParserFallback)
                        ? FindingSeverity.INFO
                        : FindingSeverity.WARNING;
                FindingConfidence confidence = (commentOnly || likelyParserFallback)
                        ? FindingConfidence.MEDIUM
                        : (broadCatch && !testSource ? FindingConfidence.HIGH : FindingConfidence.MEDIUM);
                findings.add(FindingFactory.builder(
                                FindingRules.JAVA_EMPTY_CATCH_BLOCK.ruleId(),
                                FindingRules.JAVA_EMPTY_CATCH_BLOCK.title(),
                                severity,
                                FindingRules.JAVA_EMPTY_CATCH_BLOCK.category(),
                                FindingRules.JAVA_EMPTY_CATCH_BLOCK.runtimeDetection(),
                                confidence
                        )
                        .shortMessage("Exception is caught but the catch block is empty.")
                        .whyBadPractice("An empty catch block silently discards failure information. The application may continue in an invalid state while the original cause is lost.")
                        .possibleImpact("Operators and developers may see missing data, partial processing, inconsistent state, or later failures without any useful log entry pointing to the original exception.")
                        .recommendation("Handle the exception intentionally, log it with useful context, rethrow/wrap it, or document and isolate the intentionally ignored case.")
                        .evidence(evidencePrefix + " Catch variable: " + catchClause.getParameter().getNameAsString() + ".")
                        .limitations("Static analysis cannot prove whether the exception is truly harmless, but an empty catch block hides the failure path from normal runtime diagnostics.")
                        .sourceLocation(catchLocation)
                        .highlightRange(new HighlightRange(catchLocation.startLine(), catchLocation.endLine(), null, null, "issue"))
                        .target(context.target())
                        .build());
                specificRuleTriggered = true;
            }
        }

        if (caughtTypes.stream().anyMatch(this::isInterruptedType) && !analysis.restoresInterrupt() && !analysis.rethrows()) {
            findings.add(FindingFactory.builder(FindingRules.SPRING_INTERRUPTED_EXCEPTION_SWALLOWED, FindingConfidence.HIGH)
                    .shortMessage("InterruptedException is caught without restoring the interrupt status or propagating the interruption.")
                    .whyBadPractice("InterruptedException is part of Java's cooperative cancellation mechanism. Swallowing it can prevent shutdown, cancellation, and thread-pool management from working correctly.")
                    .possibleImpact("Background jobs, scheduled tasks, or request processing may ignore shutdown signals and continue running longer than expected.")
                    .recommendation("Call Thread.currentThread().interrupt() and either return safely or rethrow/wrap the exception.")
                    .evidence(evidencePrefix + " No Thread.currentThread().interrupt() or rethrow was found.")
                    .limitations("Static analysis cannot prove the surrounding threading model, but swallowing interruption is usually unsafe in application code.")
                    .sourceLocation(catchLocation)
                    .highlightRange(new HighlightRange(catchLocation.startLine(), catchLocation.endLine(), null, null, "issue"))
                    .target(context.target())
                    .build());
            specificRuleTriggered = true;
        }

        if (caughtTypes.stream().anyMatch(this::isFatalCatchType)) {
            FindingSeverity severity = context.topLevelUncaughtHandler() || (analysis.hasStrongLogging() && analysis.rethrows())
                    ? FindingSeverity.INFO
                    : FindingRules.SPRING_BROAD_FATAL_ERROR_CATCH.defaultSeverity();
            findings.add(FindingFactory.builder(
                            FindingRules.SPRING_BROAD_FATAL_ERROR_CATCH.ruleId(),
                            FindingRules.SPRING_BROAD_FATAL_ERROR_CATCH.title(),
                            severity,
                            FindingRules.SPRING_BROAD_FATAL_ERROR_CATCH.category(),
                            FindingRules.SPRING_BROAD_FATAL_ERROR_CATCH.runtimeDetection(),
                            FindingConfidence.HIGH
                    )
                    .shortMessage("Code catches " + primaryType + ", which may include JVM-level failures that application code should normally not handle.")
                    .whyBadPractice("Catching Throwable or Error can intercept serious JVM or infrastructure failures that are not safely recoverable.")
                    .possibleImpact("The application may continue after a fatal condition, hide the real failure, or interfere with container and platform failure handling.")
                    .recommendation("Catch the narrowest expected exception type. Only catch Throwable at process boundaries where the error is logged and rethrown or the process is allowed to fail safely.")
                    .evidence(evidencePrefix + " Catch type(s): " + String.join(", ", caughtTypes) + ".")
                    .limitations("Static analysis cannot know whether this is an intentional top-level boundary, so review the surrounding code before changing it.")
                    .sourceLocation(catchLocation)
                    .highlightRange(new HighlightRange(catchLocation.startLine(), catchLocation.endLine(), null, null, "issue"))
                    .target(context.target())
                    .build());
            specificRuleTriggered = true;
        }

        if (context.controllerLike() || context.exceptionHandler()) {
            Optional<Node> rawExposureNode = findRawExceptionMessageExposureNode(
                    catchClause.getBody(),
                    catchClause.getParameter().getNameAsString()
            );
            if (rawExposureNode.isPresent()) {
                SourceLocation rawExposureLocation = nodeLocation(context.relativePath(), context.target(), rawExposureNode.get(), catchLocation);
                findings.add(FindingFactory.builder(FindingRules.SPRING_RAW_EXCEPTION_MESSAGE_HTTP, FindingConfidence.MEDIUM)
                        .shortMessage("HTTP response appears to include a raw exception message.")
                        .whyBadPractice("Exception messages can contain internal class names, SQL details, file paths, URLs, configuration names, or sensitive operational details.")
                        .possibleImpact("Clients may see internal implementation details that help attackers or confuse normal users.")
                        .recommendation("Return a sanitized client-facing error message and log the technical exception server-side with correlation information.")
                        .evidence(evidencePrefix + " Response construction uses " + summarizeNode(rawExposureNode.get()) + ".")
                        .limitations("Static analysis cannot prove whether the exception message is sanitized before this point.")
                        .sourceLocation(rawExposureLocation)
                        .highlightRange(highlightRangeFor(rawExposureLocation))
                        .target(context.target())
                        .build());
                specificRuleTriggered = true;
            }
        }

        if (analysis.usesFallbackWithoutVisibleHandling() && !analysis.hasStrongLogging() && !analysis.rethrows()) {
            boolean warningContext = broadCatch || context.productionLikeBoundary();
            FindingSeverity severity = likelyParserFallback && !warningContext
                    ? FindingSeverity.INFO
                    : (warningContext ? FindingSeverity.WARNING : FindingRules.SPRING_SWALLOWED_EXCEPTION_FALLBACK.defaultSeverity());
            String whyBadPractice = likelyParserFallback && !warningContext
                    ? "This may be intentional for best-effort parsing, but without logging or an explicit comment it is hard to distinguish expected parse failures from unexpected data loss."
                    : "Returning a fallback without recording the exception makes real failures look like valid empty or default results.";
            String possibleImpact = likelyParserFallback && !warningContext
                    ? "Unexpected input formats can be silently ignored, which may make later data quality issues harder to diagnose."
                    : "Data may silently disappear, processing may be skipped, or callers may make decisions based on incomplete information.";
            String recommendation = likelyParserFallback && !warningContext
                    ? "Keep the fallback if it is intentional, but consider adding a short comment, metric, debug log, or typed parse result when the failure is operationally relevant."
                    : "Log the exception with useful context, return a typed error result, rethrow a domain exception, or make the fallback behavior explicit and observable.";
            findings.add(FindingFactory.builder(
                            FindingRules.SPRING_SWALLOWED_EXCEPTION_FALLBACK.ruleId(),
                            FindingRules.SPRING_SWALLOWED_EXCEPTION_FALLBACK.title(),
                            severity,
                            FindingRules.SPRING_SWALLOWED_EXCEPTION_FALLBACK.category(),
                            FindingRules.SPRING_SWALLOWED_EXCEPTION_FALLBACK.runtimeDetection(),
                            FindingConfidence.MEDIUM
                    )
                    .shortMessage("Exception is caught and replaced with a fallback result without visible logging or propagation.")
                    .whyBadPractice(whyBadPractice)
                    .possibleImpact(possibleImpact)
                    .recommendation(recommendation)
                    .evidence(evidencePrefix + " Detected fallback-only handling: " + analysis.fallbackDescription() + ".")
                    .limitations("Static analysis cannot prove whether the fallback is intentional or safe for this business path.")
                    .sourceLocation(catchLocation)
                    .highlightRange(new HighlightRange(catchLocation.startLine(), catchLocation.endLine(), null, null, "issue"))
                    .target(context.target())
                    .build());
            specificRuleTriggered = true;
        }

        if (!specificRuleTriggered
                && caughtTypes.stream().anyMatch(type -> type.equals("Exception") || type.equals("RuntimeException"))
                && context.springBoundary()
                && !analysis.hasStrongLogging()
                && !analysis.rethrows()
                && !analysis.intentionalIgnoreSafe()) {
            findings.add(FindingFactory.builder(FindingRules.SPRING_BROAD_EXCEPTION_SPRING_BOUNDARY, FindingConfidence.MEDIUM)
                    .shortMessage("Spring boundary catches a broad exception type without a clearly actionable handling strategy.")
                    .whyBadPractice("Broad catch blocks at Spring boundaries can hide different failure modes behind the same behavior.")
                    .possibleImpact("Startup failures, scheduled job failures, or request failures may be harder to diagnose and may produce inconsistent operational behavior.")
                    .recommendation("Catch the expected exception types separately, add context-rich logging, or convert failures to a clear domain or application exception.")
                    .evidence(evidencePrefix + " Catch type " + primaryType + " uses weak handling in a Spring boundary.")
                    .limitations("Static analysis cannot prove whether all possible exceptions are equivalent in this context.")
                    .sourceLocation(catchLocation)
                    .highlightRange(new HighlightRange(catchLocation.startLine(), catchLocation.endLine(), null, null, "issue"))
                    .target(context.target())
                    .build());
        }
    }

    private SourceLocation catchLocation(ExceptionHandlingContext context, CatchClause catchClause) {
        int startLine = catchClause.getBegin().map(position -> position.line).orElse(1);
        int endLine = catchClause.getEnd().map(position -> position.line).orElse(startLine);
        Integer startColumn = catchClause.getBegin().map(position -> position.column).orElse(null);
        Integer endColumn = catchClause.getEnd().map(position -> position.column).orElse(null);
        return new SourceLocation(
                context.relativePath(),
                startLine,
                endLine,
                startColumn,
                endColumn,
                context.target(),
                "java",
                null
        );
    }

    private void detectBroadSpringExceptionHandler(
            ExceptionHandlingContext context,
            MethodDeclaration method,
            List<Finding> findings
    ) {
        if (!handlesBroadException(method)) {
            return;
        }
        Optional<Node> rawExposureNode = findRawExceptionMessageExposureNode(method);
        if (rawExposureNode.isPresent()) {
            SourceLocation location = nodeLocation(
                    context.relativePath(),
                    context.target(),
                    rawExposureNode.get(),
                    methodLocation(context, method)
            );
            findings.add(FindingFactory.builder(FindingRules.SPRING_RAW_EXCEPTION_MESSAGE_HTTP, FindingConfidence.MEDIUM)
                    .shortMessage("HTTP response appears to include a raw exception message.")
                    .whyBadPractice("Exception messages can contain internal class names, SQL details, file paths, URLs, configuration names, or sensitive operational details.")
                    .possibleImpact("Clients may see internal implementation details that help attackers or confuse normal users.")
                    .recommendation("Return a sanitized client-facing error message and log the technical exception server-side with correlation information.")
                    .evidence("@ExceptionHandler method " + context.target() + " returns " + summarizeNode(rawExposureNode.get()) + " to HTTP clients.")
                    .limitations("Static analysis cannot prove whether the exception message is sanitized before this point.")
                    .sourceLocation(location)
                    .highlightRange(highlightRangeFor(location))
                    .target(context.target())
                    .build());
            return;
        }
        String responseBehavior = broadExceptionHandlerResponseBehavior(method);
        FindingSeverity severity = responseBehavior == null
                ? FindingRules.SPRING_BROAD_EXCEPTION_HANDLER.defaultSeverity()
                : FindingSeverity.WARNING;
        findings.add(FindingFactory.builder(
                        FindingRules.SPRING_BROAD_EXCEPTION_HANDLER.ruleId(),
                        FindingRules.SPRING_BROAD_EXCEPTION_HANDLER.title(),
                        severity,
                        FindingRules.SPRING_BROAD_EXCEPTION_HANDLER.category(),
                        FindingRules.SPRING_BROAD_EXCEPTION_HANDLER.runtimeDetection(),
                        FindingConfidence.MEDIUM
                )
                .shortMessage("Spring exception handler catches a broad exception type.")
                .whyBadPractice("A catch-all exception handler can make unrelated failures look the same and can accidentally hide programming errors.")
                .possibleImpact("Operational failures, validation failures, and unexpected bugs may be mapped to the same HTTP response or log level.")
                .recommendation("Use narrower exception handlers for expected application errors and keep a final catch-all handler for sanitized 500 responses.")
                .evidence("@ExceptionHandler on " + context.target() + " catches Exception, RuntimeException, or Throwable."
                        + (responseBehavior == null ? "" : " Response behavior appears to map failures to " + responseBehavior + "."))
                .limitations("Static analysis cannot prove whether this is the intended global fallback handler.")
                .source(context.relativePath(), method.getBegin().map(position -> position.line).orElse(null))
                .target(context.target())
                .build());
    }

    private void detectPrintStackTrace(
            String relativePath,
            ExceptionHandlingContext context,
            List<MethodCallExpr> methodCalls,
            List<Finding> findings
    ) {
        for (MethodCallExpr call : methodCalls) {
            if (!"printStackTrace".equals(call.getNameAsString())) {
                continue;
            }
            findings.add(FindingFactory.builder(
                            FindingRules.SPRING_PRINT_STACK_TRACE.ruleId(),
                            FindingRules.SPRING_PRINT_STACK_TRACE.title(),
                            context.productionLikeBoundary() ? FindingSeverity.WARNING : FindingRules.SPRING_PRINT_STACK_TRACE.defaultSeverity(),
                            FindingRules.SPRING_PRINT_STACK_TRACE.category(),
                            FindingRules.SPRING_PRINT_STACK_TRACE.runtimeDetection(),
                            FindingConfidence.HIGH
                    )
                    .shortMessage("Exception is printed directly instead of using the application logger.")
                    .whyBadPractice("Direct stack-trace printing bypasses structured application logging, log levels, correlation IDs, and deployment logging conventions.")
                    .possibleImpact("Failures may be hard to search, correlate, redact, or route in production log systems.")
                    .recommendation("Use the application logger with useful context, for example log.warn(..., exception) or log.error(..., exception).")
                    .evidence("Detected printStackTrace() in " + relativePath + " within " + context.target() + ".")
                    .limitations("Static analysis cannot know the deployment logging setup, but Spring Boot applications should normally use the configured logging framework.")
                    .source(relativePath, call.getBegin().map(position -> position.line).orElse(null))
                    .target(context.target())
                    .build());
        }
    }

    private void detectHttpClientGaps(
            String relativePath,
            String fileContent,
            List<OutboundEndpoint> outboundEndpoints,
            List<Finding> findings
    ) {
        String normalized = fileContent.toLowerCase(Locale.ROOT);
        boolean timeoutConfigured = normalized.contains("setconnecttimeout")
                || normalized.contains("setreadtimeout")
                || normalized.contains("responsetimeout")
                || normalized.contains("readtimeout")
                || normalized.contains("connecttimeout")
                || normalized.contains("calltimeout")
                || normalized.contains("clienthttprequestfactory");
        boolean resilienceConfigured = normalized.contains("retry")
                || normalized.contains("circuitbreaker")
                || normalized.contains("resilience4j")
                || normalized.contains("retrytemplate")
                || normalized.contains("retrywhen")
                || normalized.contains("@retryable")
                || normalized.contains("backoff");
        OutboundEndpoint representative = outboundEndpoints.get(0);
        if (!timeoutConfigured) {
            findings.add(FindingFactory.builder(FindingRules.SPRING_HTTP_CLIENT_NO_TIMEOUT, FindingConfidence.MEDIUM)
                    .shortMessage("No visible timeout configuration was found for outbound HTTP client usage in " + relativePath)
                    .whyBadPractice("Outbound HTTP calls are production dependencies. Without explicit timeouts, a slow remote service can hold threads far longer than intended.")
                    .possibleImpact("Slow external APIs can delay startup hooks, stall scheduled jobs, or exhaust worker threads under production load.")
                    .recommendation("Configure connect, read, and response timeouts in the client bean or builder used for this integration.")
                    .evidence("Outbound HTTP client usage was detected in " + relativePath + " for host " + defaultString(representative.host(), representative.baseUrl(), representative.urlOrTemplate()) + ", but no obvious timeout configuration was found in the same source file.")
                    .limitations("Static analysis may miss timeouts configured in imported shared beans, external configuration classes, or auto-configured infrastructure.")
                    .source(relativePath, representative.line())
                    .target(representative.host() != null ? representative.host() : representative.clientType())
                    .build());
        }
        boolean hasWriteLikeOutbound = outboundEndpoints.stream().anyMatch(endpoint -> HTTP_WRITE_METHODS.contains(defaultString(endpoint.method()).toUpperCase(Locale.ROOT)));
        if (!resilienceConfigured && hasWriteLikeOutbound) {
            findings.add(FindingFactory.builder(FindingRules.SPRING_HTTP_CLIENT_NO_RESILIENCE, FindingConfidence.MEDIUM)
                    .shortMessage("No visible retry or circuit-breaker handling was found around outbound HTTP calls in " + relativePath)
                    .whyBadPractice("External integrations fail in partial ways. Without visible retry or backoff handling, write-like HTTP calls are more likely to fail noisily or be retried unsafely elsewhere.")
                    .possibleImpact("Operators may see flaky integrations, duplicate manual retries, or unstable job behavior when the external service is slow or intermittently unavailable.")
                    .recommendation("Review whether retries, backoff, idempotency, and circuit breakers are appropriate for this integration and make the choice explicit in code or client configuration.")
                    .evidence("Outbound HTTP calls including write-like methods were detected in " + relativePath + ", but no obvious retry or circuit-breaker configuration was found in the same source file.")
                    .limitations("Static analysis may miss resilience policies applied by shared client beans, infrastructure proxies, or external libraries.")
                    .source(relativePath, representative.line())
                    .target(representative.clientType())
                    .build());
        }
    }

    private void detectOpenInViewNotDisabled(ConfigurationAnalysis configurationAnalysis, List<Finding> findings) {
        if (configurationAnalysis == null) {
            return;
        }
        boolean jpaConfigured = configurationAnalysis.properties().stream()
                .anyMatch(p -> p.name() != null && p.name().startsWith("spring.datasource."));
        if (!jpaConfigured) {
            return;
        }
        boolean openInViewExplicit = configurationAnalysis.properties().stream()
                .anyMatch(p -> "spring.jpa.open-in-view".equals(p.name()));
        if (!openInViewExplicit) {
            findings.add(FindingFactory.builder(FindingRules.SPRING_JPA_OPEN_IN_VIEW, FindingConfidence.MEDIUM)
                    .shortMessage("spring.jpa.open-in-view is not explicitly set and defaults to true.")
                    .whyBadPractice("Spring Boot defaults open-in-view to true, keeping the Hibernate session open across the entire HTTP request including serialization. This silently enables lazy loading outside the service layer and masks N+1 query problems.")
                    .possibleImpact("Unexpected queries fire during JSON serialization or view rendering. Transactions hold connections longer than needed. Performance problems are hard to diagnose.")
                    .recommendation("Add spring.jpa.open-in-view=false to your application.properties or application.yml and load all required data explicitly in the service layer.")
                    .evidence("A datasource configuration was detected but spring.jpa.open-in-view was not explicitly set, leaving it at the Spring Boot default of true.")
                    .limitations("If the project uses Spring Data REST or a view layer that depends on lazy loading, disabling open-in-view requires explicit fetch strategies to be added.")
                    .location("Configuration")
                    .build());
        }
    }

    private void detectMissingSecurityStarter(BuildInfo buildInfo, List<Finding> findings) {
        if (buildInfo == null || buildInfo.dependencies() == null) {
            return;
        }
        boolean hasWebStarter = buildInfo.dependencies().stream()
                .anyMatch(dep -> dep.contains("spring-boot-starter-web") || dep.contains("spring-boot-starter-webflux"));
        boolean hasSecurityStarter = buildInfo.dependencies().stream()
                .anyMatch(dep -> dep.contains("spring-boot-starter-security") || dep.contains("spring-security-core") || dep.contains("spring-security-web"));
        if (hasWebStarter && !hasSecurityStarter) {
            findings.add(FindingFactory.builder(FindingRules.SPRING_SECURITY_STARTER_MISSING, FindingConfidence.MEDIUM)
                    .shortMessage("Web application has no Spring Security dependency.")
                    .whyBadPractice("Web applications without a security dependency have no authentication or authorization protection enforced by the framework by default.")
                    .possibleImpact("All endpoints are publicly accessible unless secured by an external gateway or custom filter not visible in the build file.")
                    .recommendation("Add spring-boot-starter-security and configure appropriate authentication and authorization rules.")
                    .evidence("spring-boot-starter-web or spring-boot-starter-webflux was detected but no Spring Security dependency was found in the build file.")
                    .limitations("Security may be provided by an API gateway, service mesh, or custom filter chain not declared in the build file.")
                    .location("Build configuration")
                    .build());
        }
    }

    private void detectAsyncMethodRisks(
            String relativePath,
            ClassOrInterfaceDeclaration declaration,
            MethodDeclaration method,
            List<Finding> findings
    ) {
        Integer line = method.getBegin().map(position -> position.line).orElse(null);
        String target = declaration.getNameAsString() + "#" + method.getNameAsString();
        if (method.isPrivate()) {
            findings.add(FindingFactory.builder(FindingRules.SPRING_ASYNC_PROXY_BYPASS, FindingConfidence.HIGH)
                    .shortMessage("@Async on private method " + target + " will not be intercepted by the proxy.")
                    .whyBadPractice("Spring @Async relies on proxy interception. Private methods are not visible to the proxy, so the async behaviour is silently dropped.")
                    .possibleImpact("The method executes synchronously on the calling thread instead of asynchronously, potentially blocking callers and causing unexpected behaviour.")
                    .recommendation("Make the method public or package-protected. If it must stay private, submit work explicitly via an ExecutorService instead.")
                    .evidence("@Async was found on private method " + method.getNameAsString() + " in " + relativePath + ".")
                    .limitations("Static analysis cannot determine whether AspectJ compile-time weaving is used instead of proxy-based interception.")
                    .source(relativePath, line)
                    .target(target)
                    .build());
        }
        boolean returnsVoid = method.getType().asString().equals("void");
        boolean hasExceptionHandling = !method.findAll(CatchClause.class).isEmpty();
        if (returnsVoid && !hasExceptionHandling) {
            findings.add(FindingFactory.builder(FindingRules.SPRING_ASYNC_VOID_SWALLOWED_EXCEPTION, FindingConfidence.MEDIUM)
                    .shortMessage("@Async void method " + target + " has no exception handling.")
                    .whyBadPractice("Exceptions thrown by @Async void methods are routed to AsyncUncaughtExceptionHandler, which by default only logs them. Callers have no way to observe failures.")
                    .possibleImpact("Failures in async operations are silently lost unless a custom AsyncUncaughtExceptionHandler is configured, making the system appear healthy when it is not.")
                    .recommendation("Add try/catch handling within the method, return CompletableFuture so callers can react to failures, or register a custom AsyncUncaughtExceptionHandler.")
                    .evidence("@Async void method " + method.getNameAsString() + " found without exception handling in " + relativePath + ".")
                    .limitations("Static analysis cannot verify whether a global AsyncUncaughtExceptionHandler is configured elsewhere in the application.")
                    .source(relativePath, line)
                    .target(target)
                    .build());
        }
    }

    private void detectMessagingListenerRisks(
            String relativePath,
            ClassOrInterfaceDeclaration declaration,
            MethodDeclaration method,
            List<Finding> findings
    ) {
        String presentAnnotation = MESSAGING_LISTENER_ANNOTATIONS.stream()
                .filter(name -> hasAnnotation(method.getAnnotations(), name))
                .findFirst()
                .orElse(null);
        if (presentAnnotation == null) {
            return;
        }
        boolean hasExceptionHandling = !method.findAll(CatchClause.class).isEmpty();
        if (!hasExceptionHandling) {
            Integer line = method.getBegin().map(position -> position.line).orElse(null);
            String target = declaration.getNameAsString() + "#" + method.getNameAsString();
            findings.add(FindingFactory.builder(FindingRules.SPRING_MESSAGING_LISTENER_NO_ERROR_HANDLER, FindingConfidence.MEDIUM)
                    .shortMessage("@" + presentAnnotation + " method " + target + " has no visible exception handling.")
                    .whyBadPractice("Unhandled exceptions in messaging listeners cause message redelivery or dead-letter routing depending on broker configuration. Without explicit handling, errors can cause repeated processing or silent message loss.")
                    .possibleImpact("Poison messages can block consumption or flood the dead-letter queue. Retry storms may amplify load on downstream services.")
                    .recommendation("Add try/catch to handle expected failures explicitly, configure a dead-letter topic or queue for unrecoverable messages, and log errors with enough context for investigation.")
                    .evidence("@" + presentAnnotation + " method " + method.getNameAsString() + " found without exception handling in " + relativePath + ".")
                    .limitations("Static analysis cannot verify whether a container-level error handler or retry policy is configured for this listener.")
                    .source(relativePath, line)
                    .target(target)
                    .build());
        }
    }

    private void detectJpaRelationshipRisks(
            String relativePath,
            ClassOrInterfaceDeclaration declaration,
            List<Finding> findings
    ) {
        String className = declaration.getNameAsString();
        for (FieldDeclaration field : declaration.getFields()) {
            for (AnnotationExpr annotation : field.getAnnotations()) {
                String annotationName = simpleName(annotation.getNameAsString());
                if (!Set.of("OneToMany", "ManyToOne", "OneToOne", "ManyToMany").contains(annotationName)) {
                    continue;
                }
                String fieldName = field.getVariables().isEmpty() ? "?" : field.getVariables().get(0).getNameAsString();
                Integer line = field.getBegin().map(position -> position.line).orElse(null);
                String target = className + "." + fieldName;
                if (annotationName.equals("OneToMany") || annotationName.equals("ManyToMany")) {
                    boolean hasMappedBy = annotation.isNormalAnnotationExpr()
                            && annotation.asNormalAnnotationExpr().getPairs().stream()
                                    .anyMatch(pair -> pair.getNameAsString().equals("mappedBy"));
                    if (!hasMappedBy) {
                        findings.add(FindingFactory.builder(FindingRules.SPRING_JPA_ONETOMANY_MISSING_MAPPED_BY, FindingConfidence.MEDIUM)
                                .shortMessage("@" + annotationName + " on " + target + " has no mappedBy attribute.")
                                .whyBadPractice("Without mappedBy, JPA treats this side as the owning side and creates an additional join table even when a foreign key column would suffice.")
                                .possibleImpact("The schema contains an unexpected join table, resulting in extra writes on every save and a data model that is harder to query and maintain.")
                                .recommendation("Add mappedBy referencing the owning side field to make the relationship bidirectional and avoid the unintended join table.")
                                .evidence("@" + annotationName + " on field " + fieldName + " in " + relativePath + " has no mappedBy attribute.")
                                .limitations("Static analysis cannot determine whether a unidirectional relationship and join table are intentional design choices.")
                                .source(relativePath, line)
                                .target(target)
                                .build());
                    }
                } else {
                    boolean hasFetchType = annotation.isNormalAnnotationExpr()
                            && annotation.asNormalAnnotationExpr().getPairs().stream()
                                    .anyMatch(pair -> pair.getNameAsString().equals("fetch"));
                    if (!hasFetchType) {
                        findings.add(FindingFactory.builder(FindingRules.SPRING_JPA_MANYTOONE_EAGER_DEFAULT, FindingConfidence.HIGH)
                                .shortMessage("@" + annotationName + " on " + target + " uses eager loading by default.")
                                .whyBadPractice("@ManyToOne and @OneToOne load the related entity eagerly by default, meaning every query for the owning entity also fetches the related entity even when it is not needed.")
                                .possibleImpact("Unnecessary queries on every load can cause performance problems, especially when fetching collections of entities.")
                                .recommendation("Add fetch = FetchType.LAZY explicitly and use JOIN FETCH in queries or entity graphs when the related entity is needed.")
                                .evidence("@" + annotationName + " on field " + fieldName + " in " + relativePath + " has no fetch attribute.")
                                .limitations("Static analysis cannot determine actual query patterns or whether eager loading is intentionally desired for this relationship.")
                                .source(relativePath, line)
                                .target(target)
                                .build());
                    }
                }
            }
        }
    }

    private void detectBeanInNonConfigurationClass(
            String relativePath,
            ClassOrInterfaceDeclaration declaration,
            List<Finding> findings
    ) {
        for (MethodDeclaration method : declaration.getMethods()) {
            if (!hasAnnotation(method.getAnnotations(), "Bean")) {
                continue;
            }
            Integer line = method.getBegin().map(position -> position.line).orElse(null);
            String target = declaration.getNameAsString() + "#" + method.getNameAsString();
            findings.add(FindingFactory.builder(FindingRules.SPRING_BEAN_ON_NON_CONFIGURATION, FindingConfidence.HIGH)
                    .shortMessage("@Bean method " + target + " is in a class without @Configuration (lite mode).")
                    .whyBadPractice("In lite mode, Spring does not apply CGLIB proxying to the class. Direct calls to @Bean methods from within the same class create new instances rather than returning the managed singleton from the container.")
                    .possibleImpact("Dependencies between beans defined in the same class can receive different instances than the Spring container manages, causing subtle wiring bugs that are hard to diagnose.")
                    .recommendation("Annotate the class with @Configuration to enable full CGLIB proxy mode, or move the @Bean method to a dedicated @Configuration class.")
                    .evidence("@Bean method " + method.getNameAsString() + " found in class " + declaration.getNameAsString() + " without @Configuration in " + relativePath + ".")
                    .limitations("Lite mode may be intentional for simple factory methods that are never called directly from within the same class.")
                    .source(relativePath, line)
                    .target(target)
                    .build());
        }
    }

    private void detectFieldInjection(
            String relativePath,
            ClassOrInterfaceDeclaration declaration,
            FieldDeclaration field,
            List<Finding> findings
    ) {
        String fieldName = field.getVariables().isEmpty() ? "?" : field.getVariables().get(0).getNameAsString();
        String target = declaration.getNameAsString() + "." + fieldName;
        Integer line = field.getBegin().map(position -> position.line).orElse(null);
        findings.add(FindingFactory.builder(FindingRules.SPRING_FIELD_INJECTION, FindingConfidence.HIGH)
                .shortMessage("Field injection via @Autowired in " + target + ".")
                .whyBadPractice("Field injection hides dependencies from the class API, makes the class harder to instantiate in tests without a Spring context, and can enable circular dependency wiring that would fail with constructor injection.")
                .possibleImpact("Tests require a full Spring context or reflection tricks to inject mocks. Circular dependencies may be silently resolved in an order that is hard to reason about.")
                .recommendation("Use constructor injection instead. Declare dependencies as final fields and inject them via a constructor. This makes dependencies explicit, enables immutability, and fails fast on circular dependencies.")
                .evidence("@Autowired found on field " + fieldName + " in " + relativePath + ".")
                .limitations("Field injection is sometimes intentional in test code or legacy classes. Some Spring-specific injection points such as @Value on fields require field access.")
                .source(relativePath, line)
                .target(target)
                .build());
    }

    private void detectValueWithoutDefault(
            String relativePath,
            ClassOrInterfaceDeclaration declaration,
            FieldDeclaration field,
            List<Finding> findings
    ) {
        field.getAnnotationByName("Value").ifPresent(annotation -> {
            String expr = annotation.isSingleMemberAnnotationExpr()
                    ? annotation.asSingleMemberAnnotationExpr().getMemberValue().toString()
                    : annotation.isNormalAnnotationExpr()
                            ? annotation.asNormalAnnotationExpr().getPairs().stream()
                                    .filter(p -> "value".equals(p.getNameAsString()))
                                    .map(p -> p.getValue().toString())
                                    .findFirst().orElse("")
                            : "";
            if (expr.contains("${") && VALUE_NO_DEFAULT_PATTERN.matcher(expr).find()) {
                String fieldName = field.getVariables().isEmpty() ? "?" : field.getVariables().get(0).getNameAsString();
                String target = declaration.getNameAsString() + "." + fieldName;
                Integer line = field.getBegin().map(position -> position.line).orElse(null);
                findings.add(FindingFactory.builder(FindingRules.SPRING_VALUE_NO_DEFAULT, FindingConfidence.MEDIUM)
                        .shortMessage("@Value(\"" + expr.replace("\"", "") + "\") on " + target + " has no default value.")
                        .whyBadPractice("@Value expressions without a default cause an immediate startup failure with a BeanCreationException if the property is not present in the environment, regardless of whether the bean is actually used.")
                        .possibleImpact("A missing property in any environment causes a hard startup failure. This is unforgiving in environments where not all properties are always provided.")
                        .recommendation("Add a default with the colon syntax: @Value(\"${property.name:defaultValue}\"). Use an empty string or null default only if the absent case is handled explicitly in the code.")
                        .evidence("@Value without default found on " + fieldName + " in " + relativePath + ".")
                        .limitations("Static analysis cannot determine whether the property is guaranteed to be present in all target environments.")
                        .source(relativePath, line)
                        .target(target)
                        .build());
            }
        });
    }

    private void detectModifyingNoTransaction(
            String relativePath,
            ClassOrInterfaceDeclaration declaration,
            MethodDeclaration method,
            List<Finding> findings
    ) {
        Integer line = method.getBegin().map(position -> position.line).orElse(null);
        String target = declaration.getNameAsString() + "#" + method.getNameAsString();
        findings.add(FindingFactory.builder(FindingRules.SPRING_MODIFYING_NO_TRANSACTION, FindingConfidence.HIGH)
                .shortMessage("@Modifying query " + target + " has no @Transactional boundary.")
                .whyBadPractice("Spring Data JPA requires a transaction for @Modifying queries. Without one, the repository throws TransactionRequiredException at runtime on every invocation.")
                .possibleImpact("Every call to this method fails with a runtime exception. The absence of a transaction is not detectable at compile time or startup.")
                .recommendation("Add @Transactional to the repository method or to the service method that calls it.")
                .evidence("@Modifying found without @Transactional on " + method.getNameAsString() + " in " + relativePath + ".")
                .limitations("Static analysis cannot track whether the calling service supplies a transaction boundary, but the @Modifying method itself must be within a transaction context.")
                .source(relativePath, line)
                .target(target)
                .build());
    }

    private void detectTransactionalOnScheduled(
            String relativePath,
            ClassOrInterfaceDeclaration declaration,
            MethodDeclaration method,
            List<Finding> findings
    ) {
        Integer line = method.getBegin().map(position -> position.line).orElse(null);
        String target = declaration.getNameAsString() + "#" + method.getNameAsString();
        findings.add(FindingFactory.builder(FindingRules.SPRING_TRANSACTIONAL_ON_SCHEDULED, FindingConfidence.HIGH)
                .shortMessage("@Transactional and @Scheduled are both present on " + target + ".")
                .whyBadPractice("@Scheduled methods run in a dedicated scheduler thread that has no existing transaction. @Transactional on the same method may create a transaction, but it cannot be propagated or rolled back by an outer caller because there is none.")
                .possibleImpact("Transaction behaviour becomes implicit and hard to reason about. Failures in the scheduled method may not roll back as expected, and long transactions in the scheduler thread can hold database connections for the full scheduled interval.")
                .recommendation("Extract the transactional work into a separate service method annotated with @Transactional, and call it from the @Scheduled method. This makes the transaction boundary explicit and keeps the scheduler method a thin orchestration layer.")
                .evidence("Both @Transactional and @Scheduled found on method " + method.getNameAsString() + " in " + relativePath + ".")
                .limitations("Static analysis cannot determine whether the transaction actually causes problems in the specific scheduler thread pool configuration used at runtime.")
                .source(relativePath, line)
                .target(target)
                .build());
    }

    private void detectRequestMappingNoMethod(
            String relativePath,
            ClassOrInterfaceDeclaration declaration,
            MethodDeclaration method,
            List<Finding> findings
    ) {
        method.getAnnotationByName("RequestMapping").ifPresent(annotation -> {
            boolean hasMethodAttr = annotation.isNormalAnnotationExpr()
                    && annotation.asNormalAnnotationExpr().getPairs().stream()
                            .anyMatch(pair -> "method".equals(pair.getNameAsString()));
            if (!hasMethodAttr) {
                Integer line = method.getBegin().map(position -> position.line).orElse(null);
                String target = declaration.getNameAsString() + "#" + method.getNameAsString();
                findings.add(FindingFactory.builder(FindingRules.SPRING_REQUEST_MAPPING_NO_METHOD, FindingConfidence.HIGH)
                        .shortMessage("@RequestMapping on " + target + " has no HTTP method constraint.")
                        .whyBadPractice("@RequestMapping without a method attribute matches all HTTP verbs (GET, POST, PUT, DELETE, PATCH, etc.). This is broader than almost any endpoint actually needs.")
                        .possibleImpact("Mutation endpoints can be called with GET (and thus by browsers navigating a URL). Read endpoints can receive POSTs with bodies. This makes the API surface wider than intended.")
                        .recommendation("Replace @RequestMapping with a specific annotation such as @GetMapping, @PostMapping, @PutMapping, @PatchMapping, or @DeleteMapping, or add method = RequestMethod.GET to the existing annotation.")
                        .evidence("@RequestMapping with no method attribute found on " + method.getNameAsString() + " in " + relativePath + ".")
                        .limitations("Static analysis cannot determine whether the broad method mapping is intentional, for example for CORS preflight or protocol negotiation.")
                        .source(relativePath, line)
                        .target(target)
                        .build());
            }
        });
    }

    private void detectSensitiveRequestParams(
            String relativePath,
            ClassOrInterfaceDeclaration declaration,
            MethodDeclaration method,
            List<Finding> findings
    ) {
        for (Parameter parameter : method.getParameters()) {
            String sensitiveAnnotation = null;
            String paramValue = null;
            for (AnnotationExpr annotation : parameter.getAnnotations()) {
                String annotationName = simpleName(annotation.getNameAsString());
                if (!annotationName.equals("RequestParam") && !annotationName.equals("PathVariable")) {
                    continue;
                }
                String nameValue = annotation.isSingleMemberAnnotationExpr()
                        ? annotation.asSingleMemberAnnotationExpr().getMemberValue().toString().replace("\"", "")
                        : annotation.isNormalAnnotationExpr()
                                ? annotation.asNormalAnnotationExpr().getPairs().stream()
                                        .filter(p -> "value".equals(p.getNameAsString()) || "name".equals(p.getNameAsString()))
                                        .map(p -> p.getValue().toString().replace("\"", ""))
                                        .findFirst().orElse(parameter.getNameAsString())
                                : parameter.getNameAsString();
                if (SENSITIVE_PARAM_NAMES.contains(nameValue.toLowerCase(Locale.ROOT))) {
                    sensitiveAnnotation = annotationName;
                    paramValue = nameValue;
                    break;
                }
            }
            if (sensitiveAnnotation != null) {
                Integer line = method.getBegin().map(position -> position.line).orElse(null);
                String target = declaration.getNameAsString() + "#" + method.getNameAsString();
                findings.add(FindingFactory.builder(FindingRules.SPRING_REQUEST_PARAM_SENSITIVE_NAME, FindingConfidence.HIGH)
                        .shortMessage("Sensitive value '" + paramValue + "' passed as @" + sensitiveAnnotation + " in " + target + ".")
                        .whyBadPractice("Passwords, tokens, and secrets passed as URL parameters or path variables appear in server access logs, browser history, proxy logs, and referrer headers in plaintext.")
                        .possibleImpact("Credentials are exposed in any log aggregation system that captures request URLs, making them visible to operators and making log-based security audits harder.")
                        .recommendation("Pass sensitive values in the request body (POST/PUT) or in an Authorization or custom header, never in the URL.")
                        .evidence("@" + sensitiveAnnotation + "(\"" + paramValue + "\") found in " + method.getNameAsString() + " in " + relativePath + ".")
                        .limitations("Static analysis cannot determine whether the URL is only ever called over HTTPS, but even encrypted URLs are logged in plaintext on the server side.")
                        .source(relativePath, line)
                        .target(target)
                        .build());
            }
        }
    }

    private void detectConfigPropertiesNotValidated(
            String relativePath,
            ClassOrInterfaceDeclaration declaration,
            List<Finding> findings
    ) {
        String className = declaration.getNameAsString();
        Integer line = declaration.getBegin().map(position -> position.line).orElse(null);
        findings.add(FindingFactory.builder(FindingRules.SPRING_CONFIGURATION_PROPERTIES_NOT_VALIDATED, FindingConfidence.HIGH)
                .shortMessage("@ConfigurationProperties class " + className + " has no @Validated annotation.")
                .whyBadPractice("Without @Validated, constraint annotations such as @NotNull, @Min, @Max, and @Pattern on the properties class fields are silently ignored. Invalid configuration is not caught at startup.")
                .possibleImpact("A misconfigured value (null, out of range, wrong format) reaches the application logic instead of failing fast at startup, potentially causing hard-to-diagnose runtime errors.")
                .recommendation("Add @Validated to the @ConfigurationProperties class and annotate fields with appropriate Bean Validation constraints.")
                .evidence("@ConfigurationProperties without @Validated found on class " + className + " in " + relativePath + ".")
                .limitations("Static analysis cannot determine whether validation is performed elsewhere, or whether the configuration is always guaranteed to be valid by deployment tooling.")
                .source(relativePath, line)
                .target(className)
                .build());
    }

    private void detectCsrfDisabled(
            String relativePath,
            ClassOrInterfaceDeclaration declaration,
            List<Finding> findings
    ) {
        for (MethodDeclaration method : declaration.getMethods()) {
            List<MethodCallExpr> allCalls = method.findAll(MethodCallExpr.class);
            for (MethodCallExpr call : allCalls) {
                boolean isDisableCall = "disable".equals(call.getNameAsString())
                        && call.getScope().map(Object::toString).orElse("").contains("csrf");
                boolean isCsrfLambdaDisable = "csrf".equals(call.getNameAsString())
                        && call.getArguments().stream().anyMatch(arg -> arg.toString().contains("disable"));
                if (isDisableCall || isCsrfLambdaDisable) {
                    Integer line = call.getName().getBegin().map(position -> position.line).orElse(null);
                    findings.add(FindingFactory.builder(FindingRules.SPRING_CSRF_DISABLED, FindingConfidence.HIGH)
                            .shortMessage("CSRF protection is disabled in " + declaration.getNameAsString() + "#" + method.getNameAsString() + ".")
                            .whyBadPractice("CSRF protection prevents forged cross-origin requests from tricking authenticated users into performing unintended actions. Disabling it removes this protection for all browser-based clients.")
                            .possibleImpact("State-changing endpoints can be invoked by malicious sites using an authenticated user's session without their knowledge.")
                            .recommendation("Keep CSRF enabled. If the application is a stateless REST API using token-based authentication (JWT/Bearer) and has no browser session, CSRF protection is unnecessary — but document that decision explicitly.")
                            .evidence("csrf().disable() or equivalent pattern found in " + relativePath + ".")
                            .limitations("Static analysis cannot determine whether the application uses stateless token authentication that makes CSRF irrelevant.")
                            .source(relativePath, line)
                            .target(declaration.getNameAsString() + "#" + method.getNameAsString())
                            .build());
                    return;
                }
            }
        }
    }

    private void detectCorsAllowAll(
            String relativePath,
            ClassOrInterfaceDeclaration declaration,
            List<Finding> findings
    ) {
        for (MethodDeclaration method : declaration.getMethods()) {
            for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                boolean isAllowedOrigins = call.getNameAsString().equals("allowedOrigins")
                        || call.getNameAsString().equals("setAllowedOrigins")
                        || call.getNameAsString().equals("allowedOriginPatterns");
                if (!isAllowedOrigins) {
                    continue;
                }
                boolean hasWildcard = call.getArguments().stream()
                        .anyMatch(arg -> arg.toString().contains("\"*\""));
                if (hasWildcard) {
                    Integer line = call.getName().getBegin().map(position -> position.line).orElse(null);
                    findings.add(FindingFactory.builder(FindingRules.SPRING_CORS_ALLOW_ALL, FindingConfidence.HIGH)
                            .shortMessage("CORS wildcard allowedOrigins(\"*\") found in " + declaration.getNameAsString() + "#" + method.getNameAsString() + ".")
                            .whyBadPractice("Allowing all origins removes the same-origin protection that browsers enforce by default. Any website can make cross-origin requests to the API on behalf of a user.")
                            .possibleImpact("Browser-based attacks can read API responses from any origin. Combined with cookie-based authentication, this can expose user data to third-party sites.")
                            .recommendation("Restrict allowedOrigins to an explicit allowlist of trusted domains. If the API is public and stateless, a wildcard may be acceptable but should be an explicit decision.")
                            .evidence("allowedOrigins(\"*\") or equivalent found in " + relativePath + ".")
                            .limitations("Static analysis cannot determine whether the API uses stateless authentication that makes the wildcard safe, or whether this is an internal-only endpoint.")
                            .source(relativePath, line)
                            .target(declaration.getNameAsString() + "#" + method.getNameAsString())
                            .build());
                    return;
                }
            }
        }
    }

    private void detectSchedulingRisks(
            String relativePath,
            ClassOrInterfaceDeclaration declaration,
            MethodDeclaration method,
            MethodSignals signals,
            List<Finding> findings
    ) {
        method.getAnnotationByName("Scheduled").ifPresent(annotation -> {
            Integer line = method.getBegin().map(position -> position.line).orElse(null);
            annotation.ifNormalAnnotationExpr(expr -> {
                String cron = valueFor(expr.getPairs(), "cron");
                String zone = valueFor(expr.getPairs(), "zone");
                String fixedRate = valueFor(expr.getPairs(), "fixedRate");
                String fixedRateString = valueFor(expr.getPairs(), "fixedRateString");
                String fixedDelay = valueFor(expr.getPairs(), "fixedDelay");
                String fixedDelayString = valueFor(expr.getPairs(), "fixedDelayString");
                Duration interval = parseInterval(defaultString(fixedRateString, fixedRate, fixedDelayString, fixedDelay));
                if (interval != null && interval.compareTo(Duration.ofMinutes(1)) < 0) {
                    findings.add(FindingFactory.builder(FindingRules.SPRING_SCHEDULED_SHORT_INTERVAL, FindingConfidence.HIGH)
                            .shortMessage("Scheduled method " + declaration.getNameAsString() + "#" + method.getNameAsString() + " runs on a short interval.")
                            .whyBadPractice("Very short schedules increase the chance of overlap, backlog, and accidental load amplification in multi-instance deployments.")
                            .possibleImpact("Background jobs can re-enter before previous work finishes, compete for resources, or put avoidable pressure on external APIs and databases.")
                            .recommendation("Use a longer interval, prefer fixedDelay when non-overlap is desired, and add explicit coordination if the job may run on multiple instances.")
                            .evidence("Detected @Scheduled interval " + interval + " in " + relativePath + ".")
                            .limitations("Static analysis cannot measure real execution time or whether another scheduler layer prevents overlap.")
                            .source(relativePath, line)
                            .target(declaration.getNameAsString() + "#" + method.getNameAsString())
                            .build());
                }
                if (cron != null && !cron.isBlank() && (zone == null || zone.isBlank())) {
                    findings.add(FindingFactory.builder(FindingRules.SPRING_SCHEDULED_CRON_NO_ZONE, FindingConfidence.MEDIUM)
                            .shortMessage("Scheduled cron expression has no explicit zone: " + declaration.getNameAsString() + "#" + method.getNameAsString())
                            .whyBadPractice("Cron schedules without an explicit zone inherit the JVM or container default time zone, which can vary across environments.")
                            .possibleImpact("Jobs may run at different wall-clock times after deployment, daylight saving changes, or infrastructure moves.")
                            .recommendation("Set the zone attribute explicitly for cron-based jobs whose timing matters across environments.")
                            .evidence("@Scheduled cron was found in " + relativePath + " without a zone attribute.")
                            .limitations("Static analysis cannot determine whether the deployment environment already pins a consistent default time zone.")
                            .source(relativePath, line)
                            .target(declaration.getNameAsString() + "#" + method.getNameAsString())
                            .build());
                }
            });
            if (signals.hasHttpCalls() || signals.hasDatabaseWrites()) {
                findings.add(FindingFactory.builder(FindingRules.SPRING_SCHEDULED_SIDE_EFFECT, signals.directSignalConfidence())
                        .shortMessage("Scheduled method " + declaration.getNameAsString() + "#" + method.getNameAsString() + " appears to perform side effects.")
                        .whyBadPractice("Scheduled methods are background side effects. In multi-instance deployments they can run once per instance unless coordination is made explicit.")
                        .possibleImpact("Duplicate writes, overlapping API calls, or hidden background pressure can appear only in production when multiple instances run the same job.")
                        .recommendation("Add explicit enable flags, timeouts, error handling, and distributed coordination when scheduled work changes state or calls external systems.")
                        .evidence("Detected @Scheduled method with " + signals.describe() + " in " + relativePath + ".")
                        .limitations("Static analysis infers behavior from method calls and cannot prove runtime deployment topology or distributed lock usage elsewhere.")
                        .source(relativePath, line)
                        .target(declaration.getNameAsString() + "#" + method.getNameAsString())
                        .build());
            }
        });
    }

    private void detectTransactionRisks(
            String relativePath,
            ClassOrInterfaceDeclaration declaration,
            MethodDeclaration method,
            Set<String> transactionalMethods,
            boolean repositoryLike,
            MethodSignals signals,
            List<Finding> findings
    ) {
        boolean transactional = hasAnnotation(method.getAnnotations(), "Transactional") || hasAnnotation(declaration.getAnnotations(), "Transactional");
        Integer line = method.getBegin().map(position -> position.line).orElse(null);
        if (hasAnnotation(method.getAnnotations(), "Transactional") && method.isPrivate()) {
            findings.add(FindingFactory.builder(FindingRules.SPRING_TRANSACTION_PRIVATE_METHOD, FindingConfidence.MEDIUM)
                    .shortMessage("@Transactional was found on a private method: " + declaration.getNameAsString() + "#" + method.getNameAsString())
                    .whyBadPractice("Spring transaction boundaries are normally applied through proxies around eligible methods. Private methods are a common place where that expectation becomes ineffective.")
                    .possibleImpact("Writes may happen without the transaction semantics the code appears to request, leading to partial updates or inconsistent rollback behavior.")
                    .recommendation("Move the transaction boundary to a public service method or use TransactionTemplate when an explicit local boundary is required.")
                    .evidence("@Transactional was found on private method " + method.getNameAsString() + " in " + relativePath + ".")
                    .limitations("Static analysis cannot prove the exact proxying strategy or whether AspectJ weaving is used instead of proxy-based interception.")
                    .source(relativePath, line)
                    .target(declaration.getNameAsString() + "#" + method.getNameAsString())
                    .build());
        }
        if (!transactionalMethods.isEmpty()) {
            method.findAll(MethodCallExpr.class).forEach(call -> {
                if (!transactionalMethods.contains(call.getNameAsString())) {
                    return;
                }
                if (!(call.getScope().orElse(null) instanceof ThisExpr) && call.getScope().orElse(null) != null && !(call.getScope().orElse(null) instanceof NameExpr)) {
                    return;
                }
                findings.add(FindingFactory.builder(FindingRules.SPRING_TRANSACTION_SELF_INVOCATION, FindingConfidence.MEDIUM)
                        .shortMessage("Transactional method appears to be called from the same class: " + declaration.getNameAsString() + "#" + call.getNameAsString())
                        .whyBadPractice("Self-invocation bypasses the usual Spring proxy boundary, so the callee may not receive the transaction semantics its annotation suggests.")
                        .possibleImpact("Code may appear transactional in reviews while still executing without the expected rollback or propagation behavior at runtime.")
                        .recommendation("Call the transactional method through another Spring bean or move the transaction boundary to the public entry point that is invoked externally.")
                        .evidence("Method " + method.getNameAsString() + " calls " + call + " inside " + relativePath + ".")
                        .limitations("Static analysis cannot prove whether the call path is routed through a proxy by another mechanism, but same-class invocation is a common transactional pitfall.")
                        .source(relativePath, call.getBegin().map(position -> position.line).orElse(line))
                        .target(declaration.getNameAsString() + "#" + call.getNameAsString())
                        .build());
            });
        }
        if (method.isPrivate()) {
            return;
        }
        if (method.toString().toLowerCase(Locale.ROOT).contains("transactiontemplate")) {
            return;
        }
        boolean multiWrite = signals.writeCallCount() >= 2;
        boolean mixedSideEffects = signals.hasPotentialWriteOperations() && (signals.hasHttpCalls() || signals.hasMessagingCalls());
        if (transactional || (!multiWrite && !mixedSideEffects)) {
            return;
        }
        if (signals.hasDatabaseWrites() || repositoryLike) {
            findings.add(FindingFactory.builder(
                            FindingRules.SPRING_TRANSACTION_MISSING_BOUNDARY.ruleId(),
                            FindingRules.SPRING_TRANSACTION_MISSING_BOUNDARY.title(),
                            com.robbanhoglund.springbootanalyzer.analyzer.model.FindingSeverity.INFO,
                            FindingRules.SPRING_TRANSACTION_MISSING_BOUNDARY.category(),
                            FindingRules.SPRING_TRANSACTION_MISSING_BOUNDARY.runtimeDetection(),
                            FindingConfidence.MEDIUM
                    )
                    .shortMessage("Write-heavy method has no visible transaction boundary: " + declaration.getNameAsString() + "#" + method.getNameAsString())
                    .whyBadPractice("Multiple write operations in one service method often rely on a transaction boundary to keep state changes consistent when one step fails.")
                    .possibleImpact("Partial writes, inconsistent state, or retry behavior that replays only part of the method can appear under failure conditions.")
                    .recommendation("Review whether the method should be wrapped in a public @Transactional boundary or use an explicit TransactionTemplate.")
                    .evidence("Detected " + signals.describe() + " in " + relativePath + " without a visible @Transactional annotation on the method or class.")
                    .limitations("Static analysis cannot prove whether an outer caller already supplies the transaction boundary or whether all detected write-like calls are truly mutating operations.")
                    .source(relativePath, line)
                    .target(declaration.getNameAsString() + "#" + method.getNameAsString())
                    .build());
            return;
        }
        findings.add(FindingFactory.builder(FindingRules.SPRING_SIDE_EFFECT_ORCHESTRATION_NO_BOUNDARY, FindingConfidence.MEDIUM)
                .shortMessage("Potential side-effect orchestration without explicit consistency boundary: " + declaration.getNameAsString() + "#" + method.getNameAsString())
                .whyBadPractice("Methods that coordinate several write-like or external side effects can be hard to reason about when one step fails partway through the workflow.")
                .possibleImpact("Retries or partial failures can leave downstream systems out of sync even when no single database transaction applies.")
                .recommendation("Review whether the workflow should use an explicit consistency strategy, idempotency guard, compensating action, or a clearer orchestration boundary.")
                .evidence("Detected " + signals.describe() + " in " + relativePath + " without a visible consistency boundary, but the code did not show clear persistence infrastructure signals.")
                .limitations("Static analysis cannot prove whether the detected write-like calls mutate shared state or whether another consistency boundary exists outside this method.")
                .source(relativePath, line)
                .target(declaration.getNameAsString() + "#" + method.getNameAsString())
                .build());
    }

    private void detectValidationGap(
            String relativePath,
            ClassOrInterfaceDeclaration declaration,
            MethodDeclaration method,
            MethodSignals signals,
            List<Finding> findings
    ) {
        if (!isWriteLikeEndpoint(method) && !signals.hasDatabaseWrites() && !signals.hasHttpCalls() && !signals.hasMessagingCalls()) {
            return;
        }
        for (Parameter parameter : method.getParameters()) {
            if (!hasAnnotation(parameter.getAnnotations(), "RequestBody")) {
                continue;
            }
            if (hasAnnotation(parameter.getAnnotations(), "Valid") || hasAnnotation(parameter.getAnnotations(), "Validated")) {
                continue;
            }
            if (!isValidationCandidateType(parameter)) {
                continue;
            }
            ValidationSignals validationSignals = validationSignals(parameter, declaration);
            if (!validationSignals.shouldFlag()) {
                continue;
            }
            findings.add(FindingFactory.builder(
                            FindingRules.SPRING_REQUEST_BODY_NO_VALID.ruleId(),
                            FindingRules.SPRING_REQUEST_BODY_NO_VALID.title(),
                            com.robbanhoglund.springbootanalyzer.analyzer.model.FindingSeverity.INFO,
                            FindingRules.SPRING_REQUEST_BODY_NO_VALID.category(),
                            FindingRules.SPRING_REQUEST_BODY_NO_VALID.runtimeDetection(),
                            FindingConfidence.MEDIUM
                    )
                    .shortMessage("@RequestBody parameter is missing @Valid: " + declaration.getNameAsString() + "#" + method.getNameAsString())
                    .whyBadPractice("Spring can bind request payloads successfully even when business-critical fields are missing, out of range, or structurally inconsistent.")
                    .possibleImpact("Invalid input can travel deeper into service logic before being rejected, which makes failure handling and client error reporting less predictable.")
                    .recommendation("Add @Valid or @Validated at the request boundary and place validation annotations on the DTO fields that must satisfy business constraints.")
                    .evidence("Parameter " + parameter.getNameAsString() + " of type " + parameter.getTypeAsString() + " in " + relativePath
                            + " is annotated with @RequestBody without a local @Valid or @Validated annotation. DTO validation annotations detected: "
                            + (validationSignals.hasValidationAnnotations() ? "yes" : "no") + ".")
                    .limitations("Static analysis cannot prove whether validation occurs in a custom argument resolver, service layer, or downstream pipeline.")
                    .source(relativePath, parameter.getBegin().map(position -> position.line).orElse(method.getBegin().map(position -> position.line).orElse(null)))
                    .target(declaration.getNameAsString() + "#" + method.getNameAsString())
                    .build());
        }
    }

    private CatchAnalysis analyzeCatchBody(BlockStmt body, String variableName) {
        List<Statement> statements = body.getStatements();
        boolean emptyLike = statements.isEmpty() || statements.stream().allMatch(EmptyStmt.class::isInstance);
        String comments = allCommentText(body);
        boolean commentOnly = emptyLike && !comments.isBlank();
        boolean hasStrongLogging = hasVisibleLogging(body, Set.of("warn", "error"));
        boolean hasWeakLogging = hasVisibleLogging(body, Set.of("debug", "trace", "info"));
        boolean rethrows = !body.findAll(ThrowStmt.class).isEmpty();
        boolean restoresInterrupt = body.findAll(MethodCallExpr.class).stream().anyMatch(this::isInterruptRestoreCall);
        boolean fallbackReturn = body.findAll(ReturnStmt.class).stream().anyMatch(this::isFallbackReturn);
        boolean fallbackLoopControl = !body.findAll(BreakStmt.class).isEmpty() || !body.findAll(ContinueStmt.class).isEmpty();
        boolean fallbackAssignment = hasFallbackAssignmentOnly(statements);
        boolean intentionalIgnoreSafe = emptyLike
                && IGNORE_VARIABLE_NAMES.contains(defaultString(variableName).toLowerCase(Locale.ROOT))
                && hasBenignIgnoreComment(comments);
        String fallbackDescription = describeFallback(statements);
        return new CatchAnalysis(
                emptyLike,
                hasStrongLogging,
                hasWeakLogging,
                rethrows,
                restoresInterrupt,
                fallbackReturn || fallbackLoopControl || fallbackAssignment,
                intentionalIgnoreSafe,
                commentOnly,
                fallbackDescription
        );
    }

    private boolean hasFallbackAssignmentOnly(List<Statement> statements) {
        if (statements.isEmpty()) {
            return false;
        }
        return statements.stream().allMatch(statement -> {
            if (statement instanceof ExpressionStmt expressionStmt) {
                Expression expression = expressionStmt.getExpression();
                if (expression instanceof AssignExpr assignExpr) {
                    return isFallbackExpression(assignExpr.getValue());
                }
            }
            return false;
        });
    }

    private String describeFallback(List<Statement> statements) {
        for (Statement statement : statements) {
            if (statement instanceof ReturnStmt returnStmt) {
                return "return " + returnStmt.getExpression().map(Expression::toString).orElse("");
            }
            if (statement instanceof BreakStmt) {
                return "break";
            }
            if (statement instanceof ContinueStmt) {
                return "continue";
            }
            if (statement instanceof ExpressionStmt expressionStmt && expressionStmt.getExpression() instanceof AssignExpr assignExpr) {
                return "assignment to fallback value " + assignExpr.getTarget();
            }
        }
        return "fallback control flow";
    }

    private boolean isFallbackReturn(ReturnStmt returnStmt) {
        return returnStmt.getExpression().map(this::isFallbackExpression).orElse(false);
    }

    private boolean isFallbackExpression(Expression expression) {
        if (expression instanceof NullLiteralExpr || expression instanceof BooleanLiteralExpr) {
            return true;
        }
        if (expression instanceof LiteralStringValueExpr literalStringValueExpr) {
            return literalStringValueExpr.getValue().isBlank();
        }
        String rendered = expression.toString();
        return rendered.equals("Optional.empty()")
                || rendered.equals("List.of()")
                || rendered.equals("Map.of()")
                || rendered.equals("Set.of()")
                || rendered.startsWith("Collections.empty")
                || rendered.equals("false")
                || rendered.equals("true");
    }

    private boolean hasVisibleLogging(BlockStmt body, Set<String> levels) {
        return body.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> levels.contains(call.getNameAsString())
                        && call.getScope().map(scope -> {
                            String rendered = scope.toString();
                            return rendered.equals("log")
                                    || rendered.equals("logger")
                                    || rendered.equals("LOGGER")
                                    || rendered.equals("LOG");
                        }).orElse(false));
    }

    private boolean isInterruptRestoreCall(MethodCallExpr call) {
        return "interrupt".equals(call.getNameAsString())
                && call.getScope().map(Expression::toString).orElse("").equals("Thread.currentThread()");
    }

    private Optional<Node> findRawExceptionMessageExposureNode(BlockStmt body, String exceptionVariableName) {
        String needle = exceptionVariableName + ".getMessage()";
        Optional<Node> returnNode = body.findAll(ReturnStmt.class).stream()
                .filter(statement -> statement.getExpression()
                        .map(Expression::toString)
                        .filter(text -> text.contains(needle))
                        .isPresent())
                .map(statement -> (Node) statement)
                .findFirst();
        if (returnNode.isPresent()) {
            return returnNode;
        }
        return body.findAll(MethodCallExpr.class).stream().filter(call -> {
            if (!Set.of("body", "put", "setDetail").contains(call.getNameAsString())) {
                return false;
            }
            return call.getArguments().stream().map(Expression::toString).anyMatch(text -> text.contains(needle));
        }).map(call -> (Node) call).findFirst();
    }

    private Optional<Node> findRawExceptionMessageExposureNode(MethodDeclaration method) {
        Set<String> exceptionParameters = method.getParameters().stream()
                .filter(parameter -> parameter.getTypeAsString().endsWith("Exception")
                        || parameter.getTypeAsString().endsWith("Throwable")
                        || parameter.getTypeAsString().endsWith("RuntimeException"))
                .map(Parameter::getNameAsString)
                .collect(Collectors.toSet());
        if (exceptionParameters.isEmpty()) {
            return Optional.empty();
        }
        for (String name : exceptionParameters) {
            Optional<Node> exposure = method.getBody().flatMap(body -> findRawExceptionMessageExposureNode(body, name));
            if (exposure.isPresent()) {
                return exposure;
            }
        }
        return Optional.empty();
    }

    private boolean handlesBroadException(MethodDeclaration method) {
        return method.getAnnotationByName("ExceptionHandler")
                .map(AnnotationExpr::toString)
                .map(annotation -> annotation.contains("Exception.class")
                        || annotation.contains("RuntimeException.class")
                        || annotation.contains("Throwable.class"))
                .orElse(false);
    }

    private String broadExceptionHandlerResponseBehavior(MethodDeclaration method) {
        String normalized = method.toString().toLowerCase(Locale.ROOT);
        if (normalized.contains("badrequest(") || normalized.contains("status(400)") || normalized.contains("httpstatus.bad_request")) {
            return "HTTP 400-style response";
        }
        if (normalized.contains("ok(") || normalized.contains("status(200)") || normalized.contains("httpstatus.ok")) {
            return "HTTP 200-style response";
        }
        return null;
    }

    private SourceLocation methodLocation(
            ExceptionHandlingContext context,
            MethodDeclaration method
    ) {
        Integer startLine = method.getBegin().map(position -> position.line).orElse(null);
        Integer endLine = method.getEnd().map(position -> position.line).orElse(startLine);
        if (startLine == null) {
            return null;
        }
        return new SourceLocation(
                context.relativePath(),
                startLine,
                endLine == null ? startLine : endLine,
                method.getBegin().map(position -> position.column).orElse(null),
                method.getEnd().map(position -> position.column).orElse(null),
                context.target(),
                "java",
                null
        );
    }

    private SourceLocation nodeLocation(String relativePath, String target, Node node, SourceLocation fallback) {
        Integer startLine = node.getBegin().map(position -> position.line).orElse(null);
        if (startLine == null) {
            return fallback;
        }
        int endLine = node.getEnd().map(position -> position.line).orElse(startLine);
        Integer startColumn = node.getBegin().map(position -> position.column).orElse(null);
        Integer endColumn = node.getEnd().map(position -> position.column).orElse(null);
        return new SourceLocation(relativePath, startLine, endLine, startColumn, endColumn, target, "java", null);
    }

    private HighlightRange highlightRangeFor(SourceLocation location) {
        return new HighlightRange(location.startLine(), location.endLine(), location.startColumn(), location.endColumn(), "issue");
    }

    private String summarizeNode(Node node) {
        String compact = node.toString().replaceAll("\\s+", " ").trim();
        return compact.length() > 160 ? compact.substring(0, 157) + "..." : compact;
    }

    private boolean isInterruptedType(String typeName) {
        return "InterruptedException".equals(simpleName(typeName));
    }

    private boolean isBroadCatchType(String typeName) {
        String simple = simpleName(typeName);
        return simple.equals("Exception") || simple.equals("RuntimeException") || simple.equals("Throwable");
    }

    private boolean isFatalCatchType(String typeName) {
        String simple = simpleName(typeName);
        return simple.equals("Throwable")
                || simple.equals("Error")
                || simple.equals("VirtualMachineError")
                || simple.equals("OutOfMemoryError")
                || simple.equals("StackOverflowError");
    }

    private Set<String> caughtTypeNames(CatchClause catchClause) {
        if (catchClause.getParameter().getType() instanceof UnionType unionType) {
            return unionType.getElements().stream()
                    .map(type -> simpleName(type.asString()))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        return Set.of(simpleName(catchClause.getParameter().getTypeAsString()));
    }

    private boolean isTopLevelUncaughtHandler(ClassOrInterfaceDeclaration declaration, MethodDeclaration method) {
        return implementsAny(declaration, Set.of("UncaughtExceptionHandler")) && "uncaughtException".equals(method.getNameAsString());
    }

    private boolean isGeneratedSource(String relativePath, ClassOrInterfaceDeclaration declaration) {
        String normalizedPath = relativePath.toLowerCase(Locale.ROOT);
        return normalizedPath.contains("/generated/")
                || hasAnnotation(declaration.getAnnotations(), "Generated");
    }

    private String allCommentText(BlockStmt body) {
        return body.getAllContainedComments().stream()
                .map(comment -> comment.getContent().toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(" "));
    }

    private boolean hasBenignIgnoreComment(String comments) {
        if (comments == null || comments.isBlank()) {
            return false;
        }
        return BENIGN_IGNORE_COMMENT_MARKERS.stream().anyMatch(comments::contains);
    }

    private boolean isLikelyParserFallback(
            ExceptionHandlingContext context,
            Set<String> caughtTypes,
            CatchAnalysis analysis
    ) {
        if (context.productionLikeBoundary()) {
            return false;
        }
        if (!analysis.usesFallbackWithoutVisibleHandling() && !analysis.emptyLike()) {
            return false;
        }
        String normalizedTarget = defaultString(context.target()).toLowerCase(Locale.ROOT);
        boolean parserLikeName = normalizedTarget.contains("#parse")
                || normalizedTarget.contains("#tryparse")
                || normalizedTarget.contains("#extract")
                || normalizedTarget.contains("#decode")
                || normalizedTarget.contains("#convert")
                || normalizedTarget.contains("#normalize");
        if (!parserLikeName) {
            return false;
        }
        return caughtTypes.stream().map(this::simpleName).anyMatch(type ->
                type.equals("NumberFormatException")
                        || type.equals("DateTimeParseException")
                        || type.equals("ParseException")
                        || type.equals("IllegalArgumentException")
        );
    }

    private void detectRepeatedFallbackParsingPattern(List<Finding> findings) {
        List<Finding> parserFallbacks = findings.stream()
                .filter(Objects::nonNull)
                .filter(finding -> FindingRules.SPRING_SWALLOWED_EXCEPTION_FALLBACK.ruleId().equals(finding.ruleId())
                        || FindingRules.JAVA_EMPTY_CATCH_BLOCK.ruleId().equals(finding.ruleId()))
                .filter(this::isParserLikeFallbackFinding)
                .toList();
        if (parserFallbacks.size() < 3) {
            return;
        }
        Set<String> classes = parserFallbacks.stream()
                .map(Finding::target)
                .filter(Objects::nonNull)
                .map(target -> target.contains("#") ? target.substring(0, target.indexOf('#')) : target)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (classes.size() < 2) {
            return;
        }
        String evidence = parserFallbacks.stream()
                .map(finding -> defaultString(finding.target(), finding.sourceFile()))
                .filter(value -> !value.isBlank())
                .limit(6)
                .collect(Collectors.joining(", "));
        if (parserFallbacks.size() > 6) {
            evidence = evidence + ", ...";
        }
        findings.add(FindingFactory.builder(FindingRules.SPRING_REPEATED_FALLBACK_PARSING_PATTERN, FindingConfidence.MEDIUM)
                .shortMessage("Similar parse/fallback exception handling appears in multiple classes.")
                .whyBadPractice("Repeated parser helpers that silently fall back on exceptions spread data-loss behavior across the codebase and make failure handling harder to reason about consistently.")
                .possibleImpact("Unexpected input can be dropped in slightly different ways across parsing paths, which makes data quality issues harder to diagnose and operational behavior harder to compare.")
                .recommendation("Consider centralizing parsing rules and making fallback behavior explicit with typed parse results, comments, metrics, or targeted debug logging where the behavior matters operationally.")
                .evidence("Parser-like fallback handling was detected in multiple locations: " + evidence + ".")
                .limitations("Static analysis cannot prove whether every fallback is wrong, but repeated silent parsing fallbacks are worth reviewing together.")
                .target("Multiple parsing helpers")
                .location("Exception handling")
                .build());
    }

    private boolean isParserLikeFallbackFinding(Finding finding) {
        String target = defaultString(finding.target()).toLowerCase(Locale.ROOT);
        String why = defaultString(finding.whyBadPractice()).toLowerCase(Locale.ROOT);
        return target.contains("#parse")
                || target.contains("#tryparse")
                || target.contains("#extract")
                || target.contains("#decode")
                || target.contains("#convert")
                || target.contains("#normalize")
                || why.contains("best-effort parsing");
    }

    private MethodSignals methodSignals(String body, String methodName) {
        String normalized = body.toLowerCase(Locale.ROOT);
        boolean httpCalls = normalized.contains(".retrieve(")
                || normalized.contains(".exchange(")
                || normalized.contains(".exchangetomono(")
                || normalized.contains(".exchangetoflux(")
                || normalized.contains("getforobject(")
                || normalized.contains("getforentity(")
                || normalized.contains("postforobject(")
                || normalized.contains("postforentity(")
                || normalized.contains(".execute(")
                || normalized.contains(".block()")
                || normalized.contains(".toentity(")
                || normalized.contains(".bodytomono(")
                || normalized.contains(".bodytoflux(");
        int writeCalls = 0;
        for (String marker : WRITE_CALL_MARKERS) {
            writeCalls += countOccurrences(normalized, "." + marker.toLowerCase(Locale.ROOT) + "(");
        }
        boolean persistenceSignals = normalized.contains("repository.")
                || normalized.contains("jdbctemplate.")
                || normalized.contains("namedparameterjdbctemplate.")
                || normalized.contains("entitymanager.")
                || normalized.contains("crudrepository")
                || normalized.contains("springdata")
                || normalized.contains("hibernate");
        boolean fileOps = normalized.contains("files.writestring")
                || normalized.contains("files.write(")
                || normalized.contains("deleteifexists")
                || normalized.contains("files.delete(")
                || normalized.contains("fileoutputstream(");
        boolean threadCreation = (normalized.contains("new thread(") && normalized.contains(".start("))
                || normalized.contains("executor.submit(")
                || normalized.contains("executor.execute(")
                || normalized.contains("taskscheduler.schedule(")
                || normalized.contains("scheduler.schedule(");
        boolean messagingCalls = normalized.contains(".publish(")
                || normalized.contains(".send(")
                || normalized.contains(".convertandsend(")
                || normalized.contains("kafkatemplate.send(")
                || normalized.contains("rabbittemplate.convertandsend(");
        boolean repositoryLoop = normalized.contains("for (")
                || normalized.contains("foreach")
                || normalized.contains("while (");
        boolean tryCatch = normalized.contains("try {");
        return new MethodSignals(httpCalls, writeCalls, persistenceSignals, fileOps, threadCreation, messagingCalls, repositoryLoop, tryCatch, methodName);
    }

    private boolean isWriteLikeEndpoint(MethodDeclaration method) {
        return method.getAnnotations().stream()
                .map(annotation -> simpleName(annotation.getNameAsString()))
                .anyMatch(name -> Set.of("PostMapping", "PutMapping", "PatchMapping", "DeleteMapping").contains(name));
    }

    private boolean isValidationCandidateType(Parameter parameter) {
        String type = parameter.getTypeAsString();
        String simpleType = simpleName(type);
        return !parameter.getType().isPrimitiveType()
                && !simpleType.equals("String")
                && !simpleType.equals("Map")
                && !simpleType.equals("JsonNode")
                && !simpleType.equals("MultipartFile")
                && !type.equals("byte[]")
                && !simpleType.equals("Object");
    }

    private ValidationSignals validationSignals(Parameter parameter, ClassOrInterfaceDeclaration declaration) {
        String typeName = simpleName(parameter.getTypeAsString());
        if (typeName.isBlank()) {
            return new ValidationSignals(false, false);
        }
        CompilationUnit compilationUnit = declaration.findCompilationUnit().orElse(null);
        if (compilationUnit == null) {
            return new ValidationSignals(false, typeName.endsWith("Request") || typeName.endsWith("Dto") || typeName.endsWith("Command"));
        }
        for (RecordDeclaration recordDeclaration : compilationUnit.findAll(RecordDeclaration.class)) {
            if (!recordDeclaration.getNameAsString().equals(typeName)) {
                continue;
            }
            boolean hasValidationAnnotations = recordDeclaration.getAnnotations().stream().anyMatch(this::looksLikeValidationAnnotation)
                    || recordDeclaration.getParameters().stream().flatMap(recordParameter -> recordParameter.getAnnotations().stream())
                    .anyMatch(this::looksLikeValidationAnnotation);
            boolean looksCritical = recordDeclaration.getParameters().stream()
                    .map(parameterNode -> parameterNode.getNameAsString())
                    .anyMatch(this::looksBusinessCriticalField);
            return new ValidationSignals(hasValidationAnnotations, looksCritical);
        }
        for (ClassOrInterfaceDeclaration candidate : compilationUnit.findAll(ClassOrInterfaceDeclaration.class)) {
            if (!candidate.getNameAsString().equals(typeName)) {
                continue;
            }
            boolean hasValidationAnnotations = candidate.getAnnotations().stream().anyMatch(this::looksLikeValidationAnnotation)
                    || candidate.findAll(FieldDeclaration.class).stream()
                    .flatMap(field -> field.getAnnotations().stream())
                    .anyMatch(this::looksLikeValidationAnnotation);
            boolean looksCritical = candidate.findAll(VariableDeclarator.class).stream()
                    .map(VariableDeclarator::getNameAsString)
                    .anyMatch(this::looksBusinessCriticalField);
            return new ValidationSignals(hasValidationAnnotations, looksCritical);
        }
        return new ValidationSignals(false, typeName.endsWith("Request") || typeName.endsWith("Dto") || typeName.endsWith("Command"));
    }

    private boolean looksLikeValidationAnnotation(AnnotationExpr annotation) {
        String name = simpleName(annotation.getNameAsString());
        return name.startsWith("Not")
                || name.equals("Valid")
                || name.equals("Validated")
                || name.equals("Size")
                || name.equals("Min")
                || name.equals("Max")
                || name.equals("DecimalMin")
                || name.equals("DecimalMax")
                || name.equals("Email")
                || name.equals("Pattern");
    }

    private boolean looksBusinessCriticalField(String fieldName) {
        String normalized = fieldName.toLowerCase(Locale.ROOT);
        return normalized.contains("amount")
                || normalized.contains("quantity")
                || normalized.contains("limit")
                || normalized.contains("days")
                || normalized.contains("percent")
                || normalized.contains("email")
                || normalized.contains("symbol")
                || normalized.contains("interval")
                || normalized.contains("price")
                || normalized.contains("id");
    }

    private boolean isStartupHook(ClassOrInterfaceDeclaration declaration, MethodDeclaration method, boolean startupInterface) {
        if (hasAnnotation(method.getAnnotations(), "PostConstruct")) {
            return true;
        }
        if (hasAnnotation(method.getAnnotations(), "EventListener") && method.getAnnotationByName("EventListener")
                .map(AnnotationExpr::toString)
                .map(text -> text.contains("ApplicationReadyEvent"))
                .orElse(false)) {
            return true;
        }
        if (startupInterface && "run".equals(method.getNameAsString())) {
            return true;
        }
        return implementsAny(declaration, Set.of("InitializingBean")) && "afterPropertiesSet".equals(method.getNameAsString())
                || implementsAny(declaration, Set.of("SmartLifecycle")) && "start".equals(method.getNameAsString());
    }

    private String startupHookDescription(MethodDeclaration method, ClassOrInterfaceDeclaration declaration) {
        if (hasAnnotation(method.getAnnotations(), "PostConstruct")) {
            return "@PostConstruct";
        }
        if (hasAnnotation(method.getAnnotations(), "EventListener")) {
            return "@EventListener(ApplicationReadyEvent.class)";
        }
        if (implementsAny(declaration, Set.of("CommandLineRunner"))) {
            return "CommandLineRunner#run";
        }
        if (implementsAny(declaration, Set.of("ApplicationRunner"))) {
            return "ApplicationRunner#run";
        }
        if (implementsAny(declaration, Set.of("InitializingBean"))) {
            return "InitializingBean#afterPropertiesSet";
        }
        if (implementsAny(declaration, Set.of("SmartLifecycle"))) {
            return "SmartLifecycle#start";
        }
        return method.getNameAsString();
    }

    private boolean implementsAny(ClassOrInterfaceDeclaration declaration, Set<String> typeNames) {
        return declaration.getImplementedTypes().stream()
                .map(ClassOrInterfaceType::getNameAsString)
                .map(this::simpleName)
                .anyMatch(typeNames::contains);
    }

    private boolean hasAnyAnnotation(NodeList<AnnotationExpr> annotations, Set<String> names) {
        return annotations.stream().map(annotation -> simpleName(annotation.getNameAsString())).anyMatch(names::contains);
    }

    private boolean hasAnnotation(NodeList<AnnotationExpr> annotations, String name) {
        return annotations.stream().map(annotation -> simpleName(annotation.getNameAsString())).anyMatch(name::equals);
    }

    private Map<String, List<ApplicationProperty>> groupPropertiesByName(ConfigurationAnalysis configurationAnalysis) {
        if (configurationAnalysis == null || configurationAnalysis.properties() == null) {
            return Map.of();
        }
        return configurationAnalysis.properties().stream()
                .filter(property -> property != null && property.name() != null)
                .collect(Collectors.groupingBy(ApplicationProperty::name, LinkedHashMap::new, Collectors.toList()));
    }

    private boolean isSensitivePropertyName(String propertyName) {
        String normalized = propertyName == null ? "" : propertyName.toLowerCase(Locale.ROOT);
        if (NON_SECRET_TOKEN_MARKERS.stream().anyMatch(normalized::contains)) {
            return false;
        }
        if (SENSITIVE_MARKERS.stream().anyMatch(normalized::contains)) {
            return true;
        }
        return normalized.endsWith(".token")
                || normalized.endsWith("-token")
                || normalized.contains(".token.")
                || normalized.contains("-token-");
    }

    private boolean isDriftRelevantProperty(String propertyName) {
        String normalized = propertyName.toLowerCase(Locale.ROOT);
        return normalized.contains("provider")
                || normalized.endsWith(".url")
                || normalized.endsWith(".base-url")
                || normalized.endsWith(".endpoint")
                || normalized.contains("scheduler")
                || normalized.contains("scheduled")
                || normalized.contains("management.endpoint")
                || normalized.contains("management.endpoints")
                || normalized.contains("swagger")
                || normalized.contains("springdoc")
                || normalized.contains("security");
    }

    private boolean isProdLikeProfile(String profile) {
        return PROD_PROFILES.contains(profile);
    }

    private String normalizedProfile(String profile) {
        if (profile == null || profile.isBlank()) {
            return "default";
        }
        return profile.toLowerCase(Locale.ROOT);
    }

    private String renderedValue(ApplicationProperty property) {
        if (property.valueRedacted()) {
            return property.placeholderValue() ? "placeholder" : "[redacted]";
        }
        return defaultString(property.value(), "<empty>");
    }

    private boolean hasProperty(ConfigurationAnalysis configurationAnalysis, String name) {
        return findProperty(configurationAnalysis, name) != null;
    }

    private ApplicationProperty findProperty(ConfigurationAnalysis configurationAnalysis, String name) {
        if (configurationAnalysis == null || configurationAnalysis.properties() == null) {
            return null;
        }
        return configurationAnalysis.properties().stream()
                .filter(property -> name.equals(property.name()))
                .findFirst()
                .orElse(null);
    }

    private boolean dependencyPresent(BuildInfo buildInfo, GradleModelAnalysis gradleModelAnalysis, String group, String artifact) {
        boolean inBuildInfo = buildInfo.dependencies().stream()
                .anyMatch(value -> value.toLowerCase(Locale.ROOT).contains((group + ":" + artifact).toLowerCase(Locale.ROOT)));
        boolean inGradleModel = gradleModelAnalysis != null
                && gradleModelAnalysis.resolvedDependencies() != null
                && gradleModelAnalysis.resolvedDependencies().stream()
                .anyMatch(dependency -> group.equals(dependency.group()) && artifact.equals(dependency.artifact()));
        return inBuildInfo || inGradleModel;
    }

    private List<Path> migrationFiles(Path repositoryRoot) {
        Path migrationRoot = repositoryRoot.resolve("src/main/resources/db/migration");
        if (Files.notExists(migrationRoot)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(migrationRoot)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> FLYWAY_MIGRATION_PATTERN.matcher(path.getFileName().toString()).matches())
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    private String migrationVersion(Path path) {
        Matcher matcher = FLYWAY_MIGRATION_PATTERN.matcher(path.getFileName().toString());
        return matcher.matches() ? matcher.group("version") : null;
    }

    private Duration parseInterval(String value) {
        if (value == null || value.isBlank() || value.startsWith("${")) {
            return null;
        }
        String trimmed = value.trim();
        try {
            if (trimmed.startsWith("PT")) {
                return Duration.parse(trimmed);
            }
            if (trimmed.chars().allMatch(Character::isDigit)) {
                return Duration.ofMillis(Long.parseLong(trimmed));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String valueFor(Collection<MemberValuePair> pairs, String name) {
        return pairs.stream()
                .filter(pair -> name.equals(pair.getNameAsString()))
                .map(pair -> pair.getValue().isStringLiteralExpr()
                        ? pair.getValue().asStringLiteralExpr().asString()
                        : pair.getValue().toString().replace("\"", ""))
                .findFirst()
                .orElse(null);
    }

    private int countOccurrences(String value, String needle) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private String simpleName(String value) {
        int separator = value.lastIndexOf('.');
        return separator < 0 ? value : value.substring(separator + 1);
    }

    private String defaultString(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "";
    }

    private List<Finding> dedupe(List<Finding> findings) {
        Map<String, Finding> deduped = new LinkedHashMap<>();
        for (Finding finding : findings) {
            String key = String.join("|",
                    defaultString(finding.ruleId()),
                    defaultString(finding.sourceFile()),
                    String.valueOf(finding.line()),
                    defaultString(finding.target()),
                    defaultString(finding.message())
            );
            deduped.putIfAbsent(key, finding);
        }
        return List.copyOf(deduped.values());
    }

    private record MethodSignals(
            boolean httpCalls,
            int writeCallCount,
            boolean persistenceSignals,
            boolean fileOperations,
            boolean threadCreation,
            boolean messagingCalls,
            boolean loopDetected,
            boolean localTryCatch,
            String methodName
    ) {
        boolean hasMeaningfulSideEffects() {
            return httpCalls || writeCallCount > 0 || fileOperations || threadCreation || messagingCalls;
        }

        boolean hasHttpCalls() {
            return httpCalls;
        }

        boolean hasDatabaseWrites() {
            return writeCallCount > 0 && persistenceSignals;
        }

        boolean hasPotentialWriteOperations() {
            return writeCallCount > 0;
        }

        boolean hasMessagingCalls() {
            return messagingCalls;
        }

        FindingConfidence directSignalConfidence() {
            return (httpCalls || writeCallCount > 0 || fileOperations || threadCreation || messagingCalls)
                    ? FindingConfidence.HIGH
                    : FindingConfidence.MEDIUM;
        }

        String describe() {
            List<String> parts = new ArrayList<>();
            if (httpCalls) {
                parts.add("outbound HTTP execution");
            }
            if (writeCallCount > 0) {
                parts.add(persistenceSignals ? "write-like persistence calls" : "write-like side effects");
            }
            if (fileOperations) {
                parts.add("file system operations");
            }
            if (threadCreation) {
                parts.add("manual thread or task execution");
            }
            if (messagingCalls) {
                parts.add("message publishing or send operations");
            }
            if (loopDetected) {
                parts.add("looping control flow");
            }
            if (parts.isEmpty()) {
                return "side-effecting work";
            }
            return String.join(", ", parts);
        }
    }

    private record ValidationSignals(
            boolean hasValidationAnnotations,
            boolean looksBusinessCritical
    ) {
        boolean shouldFlag() {
            return hasValidationAnnotations || looksBusinessCritical;
        }
    }

    private record ExceptionHandlingContext(
            String relativePath,
            String className,
            String target,
            boolean controllerLike,
            boolean startupHook,
            boolean scheduled,
            boolean serviceLike,
            boolean repositoryLike,
            boolean exceptionHandler,
            boolean constructorBoundary,
            boolean topLevelUncaughtHandler
    ) {
        boolean springBoundary() {
            return controllerLike || startupHook || scheduled || exceptionHandler || constructorBoundary;
        }

        boolean productionLikeBoundary() {
            return springBoundary() || serviceLike || repositoryLike;
        }
    }

    private record CatchAnalysis(
            boolean emptyLike,
            boolean hasStrongLogging,
            boolean hasWeakLogging,
            boolean rethrows,
            boolean restoresInterrupt,
            boolean usesFallbackWithoutVisibleHandling,
            boolean intentionalIgnoreSafe,
            boolean commentOnly,
            String fallbackDescription
    ) {
    }
}
