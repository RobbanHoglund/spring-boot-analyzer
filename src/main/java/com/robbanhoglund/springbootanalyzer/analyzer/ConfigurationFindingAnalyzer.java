package com.robbanhoglund.springbootanalyzer.analyzer;

import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildInfo;
import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingConfidence;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingFactory;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingOccurrence;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingRules;
import com.robbanhoglund.springbootanalyzer.analyzer.model.SourceLocation;
import com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.ApplicationProperty;
import com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.ConfigurationAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.PropertyReference;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleModelAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleResolvedDependencyModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Detects configuration-based findings: risky properties, secret duplication, profile drift,
 * conditional bean issues, Flyway risks, actuator exposure, and connection pool problems.
 * Does not require source parsing — works purely from configuration and build metadata.
 */
@Component
public class ConfigurationFindingAnalyzer {

    private static final Set<String> PROD_PROFILES = Set.of("prod", "production", "staging");
    private static final Set<String> TEST_PROFILES = Set.of("test", "ci", "it", "integration-test");
    private static final Set<String> TEST_OR_LOCAL_PROFILES =
            Set.of(
                    "test",
                    "ci",
                    "it",
                    "integration-test",
                    "local",
                    "dev",
                    "development",
                    "qa",
                    "uat");
    private static final Set<String> SECURITY_AUTO_CONFIG_CLASSES =
            Set.of(
                    "SecurityAutoConfiguration",
                    "UserDetailsServiceAutoConfiguration",
                    "ManagementWebSecurityAutoConfiguration");
    private static final Set<String> SCHEDULING_DISABLE_PROPERTIES =
            Set.of("spring.task.scheduling.enabled", "spring.quartz.auto-startup");
    private static final Set<String> SENSITIVE_MARKERS =
            Set.of(
                    "password",
                    "passwd",
                    "secret",
                    "client-secret",
                    "api-key",
                    "apikey",
                    "access-key",
                    "private-key",
                    "credential",
                    "credentials",
                    "authorization",
                    "api-token",
                    "access-token",
                    "refresh-token",
                    "bearer-token",
                    "auth-token",
                    "oauth-token",
                    "github-token",
                    "signing-key",
                    "pat",
                    "jwt-secret");
    private static final Set<String> NON_SECRET_TOKEN_MARKERS =
            Set.of(
                    "max-output-tokens",
                    "max-tokens",
                    "token-limit",
                    "token-count",
                    "token-budget",
                    "tokens-per-minute",
                    "tokens-per-request",
                    "tokenizer",
                    "token-window",
                    "output-tokens",
                    "input-tokens",
                    "input-token-budget",
                    "output-token-budget");
    // Flyway versioned migration filename: V<version>__<description>.sql, where <version> is one or
    // more numeric segments separated by '.' or '_' (e.g. V1, V1.0, V2_1, V20230101). The version
    // is intentionally allowed to be a single digit — the common V1__init.sql form.
    private static final Pattern FLYWAY_MIGRATION_PATTERN =
            Pattern.compile(
                    "V(?<version>[0-9]+(?:[._][0-9]+)*)__.+\\.sql", Pattern.CASE_INSENSITIVE);

    /**
     * Runs all configuration-based detections and returns the combined findings list.
     *
     * <p>The detections performed, in order:
     * <ol>
     *   <li>Sensitive property values duplicated across Spring profiles</li>
     *   <li>Risky production configuration (open-in-view, H2 console, debug flags, etc.)</li>
     *   <li>Cross-profile property value drift for security-sensitive keys</li>
     *   <li>Conditional bean matrix issues (missing {@code @ConditionalOnMissingBean} pairs)</li>
     *   <li>Flyway migration file schema risks</li>
     *   <li>Missing Spring Security starter</li>
     *   <li>Actuator endpoint exposure via {@code management.endpoints.web.exposure.include}</li>
     *   <li>Connection pool misconfiguration (pool size vs. max connections)</li>
     * </ol>
     *
     * @param repositoryRoot       root directory of the project (used for Flyway file scanning)
     * @param buildInfo            build-level metadata including detected dependencies
     * @param configurationAnalysis parsed properties and YAML configuration across all profiles
     * @param gradleModelAnalysis  resolved Gradle model (may be an empty/unavailable result)
     * @return all detected findings; never null, may be empty
     */
    public List<Finding> analyze(
            Path repositoryRoot,
            BuildInfo buildInfo,
            ConfigurationAnalysis configurationAnalysis,
            GradleModelAnalysis gradleModelAnalysis) {
        List<Finding> findings = new ArrayList<>();
        detectSensitiveProfileDuplication(configurationAnalysis, findings);
        detectAdditionalRiskyConfiguration(configurationAnalysis, findings);
        detectCrossProfileDrift(configurationAnalysis, findings);
        detectHibernateVersionMismatch(buildInfo, gradleModelAnalysis, findings);
        detectSecurityAutoconfigureExcluded(configurationAnalysis, findings);
        detectDatasourceNoTestOverride(configurationAnalysis, findings);
        detectH2InNonTestProfile(configurationAnalysis, findings);
        detectFlywayDisabledInTest(configurationAnalysis, findings);
        detectSchedulingDisabledInTest(configurationAnalysis, findings);
        detectConditionalBeanMatrixIssues(configurationAnalysis, findings);
        detectFlywaySchemaRisks(
                repositoryRoot, buildInfo, configurationAnalysis, gradleModelAnalysis, findings);
        detectMissingSecurityStarter(buildInfo, findings);
        detectOpenInViewNotDisabled(configurationAnalysis, findings);
        detectJpaDdlAutoDangerous(configurationAnalysis, findings);
        detectActuatorExposure(configurationAnalysis, findings);
        detectConnectionPoolMisconfiguration(configurationAnalysis, findings);
        detectDevToolsInProduction(buildInfo, findings);
        detectAsyncSecurityContextLost(repositoryRoot, buildInfo, findings);
        detectMultipartUnlimitedSize(configurationAnalysis, findings);
        detectJdbcUrlEmbeddedCredentials(configurationAnalysis, findings);
        detectDefaultUserPasswordLiteral(configurationAnalysis, findings);
        detectDeprecatedSpringProfiles(configurationAnalysis, findings);
        detectActuatorHttptraceRenamed(configurationAnalysis, findings);
        return findings;
    }

    private void detectJdbcUrlEmbeddedCredentials(
            ConfigurationAnalysis configurationAnalysis, List<Finding> findings) {
        if (configurationAnalysis == null || configurationAnalysis.properties() == null) {
            return;
        }
        for (ApplicationProperty property : configurationAnalysis.properties()) {
            if (property == null || property.name() == null || property.value() == null) {
                continue;
            }
            String name = property.name();
            boolean isJdbcUrl =
                    name.endsWith("datasource.url")
                            || name.endsWith("datasource.jdbc-url")
                            || name.endsWith("datasource.jdbcUrl");
            if (!isJdbcUrl) {
                continue;
            }
            String value = property.value().toLowerCase(Locale.ROOT);
            if (!value.contains("password=") && !value.contains("user=")) {
                continue;
            }
            String profileLabel = property.profile() != null ? " [" + property.profile() + "]" : "";
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_JDBC_URL_EMBEDDED_CREDENTIALS,
                                    FindingConfidence.MEDIUM)
                            .shortMessage(
                                    name
                                            + profileLabel
                                            + " embeds credentials directly in the JDBC connection"
                                            + " string.")
                            .whyBadPractice(
                                    "Putting user=/password= in the JDBC URL stores the database"
                                        + " credentials in plain text in configuration. The full"
                                        + " URL — including the password — is also written to"
                                        + " connection pool logs and exception messages.")
                            .possibleImpact(
                                    "The database password is exposed to anyone with access to the"
                                        + " config files, the build artifact, or the application"
                                        + " logs, and cannot be rotated without a redeploy.")
                            .recommendation(
                                    "Move the credentials to spring.datasource.username and"
                                        + " spring.datasource.password backed by environment"
                                        + " variables or a secrets manager, and keep only host/db"
                                        + " in the URL.")
                            .evidence(
                                    name
                                            + " contains user/password query parameters in "
                                            + property.sourceFile()
                                            + ".")
                            .limitations(
                                    "Medium confidence — the URL may point at a disposable local"
                                            + " database. Review whether the credentials are"
                                            + " sensitive.")
                            .source(property.sourceFile(), property.line())
                            .target(name)
                            .build());
        }
    }

    private void detectDefaultUserPasswordLiteral(
            ConfigurationAnalysis configurationAnalysis, List<Finding> findings) {
        if (configurationAnalysis == null || configurationAnalysis.properties() == null) {
            return;
        }
        for (ApplicationProperty property : configurationAnalysis.properties()) {
            if (property == null || !"spring.security.user.password".equals(property.name())) {
                continue;
            }
            if (property.placeholderValue()) {
                continue; // ${...} reference — not a literal.
            }
            String profileLabel = property.profile() != null ? " [" + property.profile() + "]" : "";
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_DEFAULT_USER_PASSWORD_LITERAL,
                                    FindingConfidence.HIGH)
                            .shortMessage(
                                    "spring.security.user.password"
                                            + profileLabel
                                            + " is set to a literal value.")
                            .whyBadPractice(
                                    "spring.security.user.password configures the default in-memory"
                                        + " user. A literal value is committed to version control"
                                        + " and shipped in the build, so the credential is known to"
                                        + " anyone with repository or artifact access.")
                            .possibleImpact(
                                    "If this default user is active in a deployed environment, the"
                                            + " hardcoded password grants access to whatever it is"
                                            + " authorised for.")
                            .recommendation(
                                    "Reference an environment variable or secret"
                                        + " (spring.security.user.password=${ADMIN_PASSWORD}), or"
                                        + " replace the default user with a real UserDetailsService"
                                        + " backed by a secured credential store.")
                            .evidence(
                                    "spring.security.user.password set to a literal in "
                                            + property.sourceFile()
                                            + ".")
                            .limitations(
                                    "The default user is often used only for local development;"
                                        + " confirm whether this profile is active in deployment.")
                            .source(property.sourceFile(), property.line())
                            .target("spring.security.user.password")
                            .build());
        }
    }

    private void detectDeprecatedSpringProfiles(
            ConfigurationAnalysis configurationAnalysis, List<Finding> findings) {
        if (configurationAnalysis == null || configurationAnalysis.properties() == null) {
            return;
        }
        for (ApplicationProperty property : configurationAnalysis.properties()) {
            if (property == null || !"spring.profiles".equals(property.name())) {
                continue;
            }
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_PROFILES_PROPERTY_DEPRECATED,
                                    FindingConfidence.HIGH)
                            .shortMessage(
                                    "The deprecated spring.profiles property is used in "
                                            + property.sourceFile()
                                            + ".")
                            .whyBadPractice(
                                    "spring.profiles (used to bind a document to a profile) was"
                                        + " deprecated in Spring Boot 2.4 and removed in Spring"
                                        + " Boot 3. It is silently ignored there, so the document"
                                        + " is applied unconditionally instead of only for the"
                                        + " intended profile.")
                            .possibleImpact(
                                    "After upgrading to Spring Boot 3 the profile guard no longer"
                                        + " applies, which can activate the wrong configuration in"
                                        + " every environment.")
                            .recommendation(
                                    "Replace spring.profiles with spring.config.activate.on-profile"
                                            + " (to guard a document) or spring.profiles.group (to"
                                            + " compose profiles).")
                            .evidence("spring.profiles found in " + property.sourceFile() + ".")
                            .limitations(
                                    "Does not apply to spring.profiles.active/include/group, which"
                                            + " remain valid.")
                            .source(property.sourceFile(), property.line())
                            .target("spring.profiles")
                            .build());
        }
    }

    private void detectActuatorHttptraceRenamed(
            ConfigurationAnalysis configurationAnalysis, List<Finding> findings) {
        if (configurationAnalysis == null || configurationAnalysis.properties() == null) {
            return;
        }
        for (ApplicationProperty property : configurationAnalysis.properties()) {
            if (property == null || property.name() == null) {
                continue;
            }
            String name = property.name();
            String value =
                    property.value() == null ? "" : property.value().toLowerCase(Locale.ROOT);
            boolean dedicatedHttptraceProperty = name.contains("endpoint.httptrace");
            boolean exposedInList =
                    ("management.endpoints.web.exposure.include".equals(name)
                                    || "management.endpoints.web.exposure.exclude".equals(name))
                            && Stream.of(value.split(","))
                                    .map(String::trim)
                                    .anyMatch("httptrace"::equals);
            if (!dedicatedHttptraceProperty && !exposedInList) {
                continue;
            }
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_ACTUATOR_HTTPTRACE_RENAMED,
                                    FindingConfidence.HIGH)
                            .shortMessage(
                                    "The actuator 'httptrace' endpoint referenced in "
                                            + property.sourceFile()
                                            + " was renamed to 'httpexchanges' in Spring Boot 3.")
                            .whyBadPractice(
                                    "Spring Boot 3 renamed the httptrace actuator endpoint to"
                                        + " httpexchanges (backed by HttpExchangeRepository). The"
                                        + " old id no longer maps to anything, so the property has"
                                        + " no effect after upgrading.")
                            .possibleImpact(
                                    "After upgrading to Spring Boot 3 the endpoint is silently not"
                                            + " exposed/configured, and any dashboards or scripts"
                                            + " pointing at /actuator/httptrace return 404.")
                            .recommendation(
                                    "Rename httptrace to httpexchanges and provide an"
                                        + " HttpExchangeRepository bean (the in-memory repository"
                                        + " is no longer auto-configured by default).")
                            .evidence(
                                    name
                                            + (property.value() == null
                                                    ? ""
                                                    : "=" + property.value())
                                            + " found in "
                                            + property.sourceFile()
                                            + ".")
                            .limitations(
                                    "Informational — only relevant when targeting Spring Boot 3 or"
                                            + " later.")
                            .source(property.sourceFile(), property.line())
                            .target(name)
                            .build());
        }
    }

    private void detectMultipartUnlimitedSize(
            ConfigurationAnalysis configurationAnalysis, List<Finding> findings) {
        if (configurationAnalysis == null || configurationAnalysis.properties() == null) {
            return;
        }
        for (ApplicationProperty property : configurationAnalysis.properties()) {
            if (property == null || property.name() == null || property.value() == null) {
                continue;
            }
            String name = property.name();
            boolean isMultipartSize =
                    "spring.servlet.multipart.max-file-size".equals(name)
                            || "spring.servlet.multipart.max-request-size".equals(name);
            if (!isMultipartSize) {
                continue;
            }
            if (!"-1".equals(property.value().trim())) {
                continue;
            }
            String profileLabel = property.profile() != null ? " [" + property.profile() + "]" : "";
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_MULTIPART_NO_MAX_SIZE,
                                    FindingConfidence.HIGH)
                            .shortMessage(
                                    name
                                            + "=-1"
                                            + profileLabel
                                            + " removes the multipart upload size limit.")
                            .whyBadPractice(
                                    "Spring Boot caps uploads by default (1MB per file, 10MB per"
                                        + " request). Setting the limit to -1 makes it unlimited,"
                                        + " so a single request can stream gigabytes into the"
                                        + " configured temp directory or heap.")
                            .possibleImpact(
                                    "An unauthenticated or low-privilege client can fill the disk"
                                            + " or exhaust memory with one or a few large uploads,"
                                            + " taking the instance down (denial of service).")
                            .recommendation(
                                    "Set spring.servlet.multipart.max-file-size and"
                                        + " max-request-size to concrete limits sized for your use"
                                        + " case (e.g. 5MB / 20MB). Enforce the same limit at the"
                                        + " reverse proxy or load balancer as defense in depth.")
                            .limitations(
                                    "High confidence — the value is read directly as -1"
                                        + " (unlimited). A deployment that genuinely needs"
                                        + " unbounded uploads should rely on streaming to object"
                                        + " storage instead.")
                            .evidence(name + "=-1 found" + profileLabel + ".")
                            .source(property.sourceFile(), property.line())
                            .build());
        }
    }

    private void detectSensitiveProfileDuplication(
            ConfigurationAnalysis configurationAnalysis, List<Finding> findings) {
        Map<String, List<ApplicationProperty>> grouped =
                groupPropertiesByName(configurationAnalysis);
        for (Map.Entry<String, List<ApplicationProperty>> entry : grouped.entrySet()) {
            if (!isSensitivePropertyName(entry.getKey())) {
                continue;
            }
            List<ApplicationProperty> configured =
                    entry.getValue().stream()
                            .filter(property -> property.sourceFile() != null)
                            .toList();
            if (configured.size() < 2) {
                continue;
            }
            String evidence =
                    configured.stream()
                            .map(
                                    property ->
                                            property.sourceFile()
                                                    + (property.profile() == null
                                                            ? ""
                                                            : " [" + property.profile() + "]"))
                            .collect(Collectors.joining(", "));
            boolean anyLiteralOrWeakDefault =
                    configured.stream()
                            .anyMatch(
                                    property ->
                                            property.valueRedacted()
                                                    && !property.placeholderValue());
            ApplicationProperty primary =
                    configured.stream()
                            .filter(p -> p.valueRedacted() && !p.placeholderValue())
                            .findFirst()
                            .orElse(configured.get(0));
            FindingFactory.Builder builder =
                    FindingFactory.builder(
                                    FindingRules.SPRING_SECRET_MULTI_PROFILE.ruleId(),
                                    FindingRules.SPRING_SECRET_MULTI_PROFILE.title(),
                                    anyLiteralOrWeakDefault
                                            ? com.robbanhoglund.springbootanalyzer.analyzer.model
                                                    .FindingSeverity.WARNING
                                            : FindingRules.SPRING_SECRET_MULTI_PROFILE
                                                    .defaultSeverity(),
                                    anyLiteralOrWeakDefault
                                            ? com.robbanhoglund.springbootanalyzer.analyzer.model
                                                    .FindingCategory.SECURITY
                                            : FindingRules.SPRING_SECRET_MULTI_PROFILE.category(),
                                    FindingRules.SPRING_SECRET_MULTI_PROFILE.runtimeDetection(),
                                    FindingConfidence.MEDIUM)
                            .shortMessage(
                                    "Sensitive property is configured in multiple profiles: "
                                            + entry.getKey())
                            .whyBadPractice(
                                    "Keeping the same sensitive property across several profile"
                                        + " files makes secrets harder to rotate consistently and"
                                        + " easier to copy between environments.")
                            .possibleImpact(
                                    "Different deployments may drift, stale secrets can survive in"
                                        + " older profiles, and operational rotation becomes more"
                                        + " error-prone.")
                            .recommendation(
                                    "Centralize the secret in environment-specific secret storage"
                                            + " and reference it from each profile rather than"
                                            + " duplicating static values.")
                            .evidence(
                                    "Sensitive property definitions were found in multiple"
                                            + " configuration sources: "
                                            + evidence
                                            + ".")
                            .limitations(
                                    "Static analysis cannot prove whether the values are identical"
                                            + " because sensitive values are redacted before"
                                            + " presentation.")
                            .target(entry.getKey())
                            .source(primary.sourceFile(), primary.line());
            for (ApplicationProperty prop : configured) {
                int lineNum = prop.line() != null ? prop.line() : 0;
                String profileLabel = prop.profile() != null ? " [" + prop.profile() + "]" : "";
                builder.addOccurrence(
                        new FindingOccurrence(
                                prop.sourceFile() + profileLabel,
                                new SourceLocation(
                                        prop.sourceFile(),
                                        lineNum,
                                        lineNum,
                                        null,
                                        null,
                                        entry.getKey(),
                                        null,
                                        null),
                                null));
            }
            findings.add(builder.build());
        }
    }

    private void detectAdditionalRiskyConfiguration(
            ConfigurationAnalysis configurationAnalysis, List<Finding> findings) {
        for (ApplicationProperty property :
                configurationAnalysis == null
                        ? List.<ApplicationProperty>of()
                        : configurationAnalysis.properties()) {
            if (property == null || property.name() == null || property.value() == null) {
                continue;
            }
            String profile = normalizedProfile(property.profile());
            boolean prodLike = isProdLikeProfile(profile);
            String name = property.name();
            String value = property.value();
            if (prodLike
                    && "spring.flyway.clean-disabled".equals(name)
                    && "false".equalsIgnoreCase(value)) {
                findings.add(
                        configFinding(
                                "spring.flyway.clean-disabled=false can enable destructive Flyway"
                                        + " clean operations in production.",
                                "Allowing Flyway clean in production weakens an important safety"
                                        + " barrier around destructive schema operations.",
                                "An accidental clean call can wipe application schemas or make"
                                        + " recovery much harder during an incident.",
                                "Keep spring.flyway.clean-disabled=true outside disposable"
                                    + " environments and reserve clean operations for controlled"
                                    + " maintenance workflows.",
                                property,
                                name,
                                FindingConfidence.HIGH));
            } else if (prodLike && "debug".equals(name) && "true".equalsIgnoreCase(value)) {
                findings.add(
                        configFinding(
                                "debug=true can expose verbose auto-configuration details in"
                                        + " production-oriented configuration.",
                                "Spring debug mode is useful locally, but it increases startup"
                                    + " verbosity and internal diagnostics in environments where"
                                    + " minimal disclosure is safer.",
                                "Verbose debug output can leak more internal wiring details into"
                                    + " logs and make production logging noisier during incidents.",
                                "Keep debug disabled in production profiles and enable targeted"
                                    + " package-level logging only during troubleshooting windows.",
                                property,
                                name,
                                FindingConfidence.HIGH));
            } else if (prodLike
                    && "server.error.include-stacktrace".equals(name)
                    && "always".equalsIgnoreCase(value)) {
                findings.add(
                        configFinding(
                                "server.error.include-stacktrace=always can expose stack traces"
                                        + " through HTTP error responses.",
                                "Stack traces are useful for debugging but they reveal internal"
                                        + " implementation details when sent back to clients.",
                                "Unexpected stack traces can disclose package names, library"
                                        + " versions, and code paths during failures.",
                                "Prefer never or on-param outside local development, and use"
                                        + " structured server logs for detailed diagnostics.",
                                property,
                                name,
                                FindingConfidence.HIGH));
            } else if (prodLike
                    && "server.error.include-message".equals(name)
                    && "always".equalsIgnoreCase(value)) {
                findings.add(
                        configFinding(
                                "server.error.include-message=always can expose internal failure"
                                        + " messages to clients.",
                                "Detailed error messages are useful for debugging but they increase"
                                    + " how much internal application state is returned at the HTTP"
                                    + " boundary.",
                                "Users and automated clients may receive messages that reveal"
                                    + " validation internals, dependency failures, or operational"
                                    + " context.",
                                "Prefer never or on-param outside development and keep detailed"
                                        + " diagnostics in logs and observability tooling.",
                                property,
                                name,
                                FindingConfidence.HIGH));
            } else if (prodLike
                    && "logging.level.root".equals(name)
                    && (value.equalsIgnoreCase("debug") || value.equalsIgnoreCase("trace"))) {
                findings.add(
                        configFinding(
                                "logging.level.root="
                                        + value
                                        + " can make production logs excessively verbose.",
                                "Root-level DEBUG or TRACE logging trades signal for noise and"
                                    + " often exposes far more framework internals than operators"
                                    + " need by default.",
                                "Production logs can become noisy, expensive, and more likely to"
                                        + " contain sensitive request or infrastructure details.",
                                "Keep root logging at INFO or WARN in production and enable"
                                    + " targeted package logging during controlled investigations.",
                                property,
                                name,
                                FindingConfidence.HIGH));
            } else if (prodLike
                    && "spring.main.lazy-initialization".equals(name)
                    && "true".equalsIgnoreCase(value)) {
                findings.add(
                        configFinding(
                                "spring.main.lazy-initialization=true can defer production failures"
                                        + " until first use.",
                                "Lazy initialization speeds startup, but it moves dependency and"
                                    + " bean-creation failures away from deployment time and into"
                                    + " runtime traffic paths.",
                                "Production may appear healthy at startup and then fail only when a"
                                        + " less frequently used code path is exercised.",
                                "Use lazy initialization cautiously and prefer explicit performance"
                                    + " measurements before enabling it in production profiles.",
                                property,
                                name,
                                FindingConfidence.MEDIUM));
            } else if (prodLike
                    && (name.equals("springdoc.api-docs.enabled")
                            || name.equals("springdoc.swagger-ui.enabled"))
                    && "true".equalsIgnoreCase(value)) {
                findings.add(
                        configFinding(
                                name
                                        + "=true exposes API documentation tooling in a"
                                        + " production-oriented profile.",
                                "Swagger and API documentation are useful operationally, but they"
                                        + " widen the visible API surface and make administrative"
                                        + " endpoints easier to discover.",
                                "Publicly reachable documentation can make exploration of sensitive"
                                        + " or admin-like endpoints easier than intended.",
                                "Restrict API documentation to trusted environments or secure it"
                                        + " behind explicit authentication and profile controls.",
                                property,
                                name,
                                FindingConfidence.HIGH));
            } else if (prodLike
                    && "spring.jpa.hibernate.ddl-auto".equals(name)
                    && (value.equalsIgnoreCase("create")
                            || value.equalsIgnoreCase("create-drop"))) {
                findings.add(
                        FindingFactory.builder(
                                        FindingRules.SPRING_DDL_AUTO_DESTRUCTIVE_PROD,
                                        FindingConfidence.HIGH)
                                .shortMessage(
                                        "spring.jpa.hibernate.ddl-auto="
                                                + value
                                                + " can destroy the database schema on startup in a"
                                                + " production-oriented profile.")
                                .whyBadPractice(
                                        "create and create-drop both drop and recreate tables at"
                                            + " application startup. In production this destroys"
                                            + " all existing data unconditionally.")
                                .possibleImpact(
                                        "Every deployment will wipe the database. This is almost"
                                                + " certainly unintended in a production or staging"
                                                + " environment and cannot be undone.")
                                .recommendation(
                                        "Use validate or none in production profiles. Manage schema"
                                            + " changes through Flyway or Liquibase migrations.")
                                .evidence(
                                        "spring.jpa.hibernate.ddl-auto="
                                                + value
                                                + " was found in "
                                                + property.sourceFile()
                                                + ".")
                                .limitations(
                                        "Static analysis cannot determine whether the target"
                                            + " database is disposable or whether the profile is"
                                            + " always active in deployment.")
                                .source(property.sourceFile(), property.line())
                                .target(name)
                                .build());
            } else if (prodLike
                    && "spring.jpa.show-sql".equals(name)
                    && "true".equalsIgnoreCase(value)) {
                findings.add(
                        FindingFactory.builder(
                                        FindingRules.SPRING_JPA_SHOW_SQL_PROD,
                                        FindingConfidence.HIGH)
                                .shortMessage(
                                        "spring.jpa.show-sql=true prints all SQL statements to"
                                                + " stdout in a production-oriented profile.")
                                .whyBadPractice(
                                        "SQL logging at the JPA layer bypasses the application"
                                            + " logging framework, writes directly to stdout, and"
                                            + " cannot be controlled by log-level configuration.")
                                .possibleImpact(
                                        "Production logs become noisy, schema details and query"
                                            + " structures are exposed in log aggregation systems,"
                                            + " and performance overhead is added on every query.")
                                .recommendation(
                                        "Use logging.level.org.hibernate.SQL=DEBUG or a query"
                                                + " profiling tool instead, and keep it disabled in"
                                                + " production profiles.")
                                .evidence(
                                        "spring.jpa.show-sql=true was found in "
                                                + property.sourceFile()
                                                + ".")
                                .limitations(
                                        "Static analysis cannot prove whether this profile is"
                                            + " always active in deployment, but the filename or"
                                            + " profile marker indicates production-oriented"
                                            + " usage.")
                                .source(property.sourceFile(), property.line())
                                .target(name)
                                .build());
            } else if (prodLike
                    && "spring.h2.console.enabled".equals(name)
                    && "true".equalsIgnoreCase(value)) {
                findings.add(
                        FindingFactory.builder(
                                        FindingRules.SPRING_H2_CONSOLE_ENABLED_PROD,
                                        FindingConfidence.HIGH)
                                .shortMessage(
                                        "spring.h2.console.enabled=true exposes the H2 web console"
                                                + " in a production-oriented profile.")
                                .whyBadPractice(
                                        "The H2 web console accepts arbitrary SQL through a JDBC"
                                            + " connection and can invoke Java stored procedures"
                                            + " via CREATE ALIAS, RUNSCRIPT, or SCRIPT. When"
                                            + " reachable it is a direct remote-code-execution"
                                            + " vector. Even when bound to localhost it is"
                                            + " routinely exposed by reverse proxies, port"
                                            + " forwarding, or Spring Security misconfiguration.")
                                .possibleImpact(
                                        "An attacker who reaches the console URL can read and write"
                                            + " every row in the database, execute arbitrary Java"
                                            + " code on the host, and pivot from there into the"
                                            + " rest of the network.")
                                .recommendation(
                                        "Set spring.h2.console.enabled=false in production-oriented"
                                            + " profiles, or remove the H2 dependency entirely and"
                                            + " use a managed database. Restrict the H2 console to"
                                            + " local development profiles only.")
                                .evidence(
                                        "spring.h2.console.enabled=true was found in "
                                                + property.sourceFile()
                                                + ".")
                                .limitations(
                                        "Static analysis cannot prove whether this profile is"
                                            + " actually active in deployment, but the filename or"
                                            + " profile marker indicates production-oriented"
                                            + " usage.")
                                .source(property.sourceFile(), property.line())
                                .target(name)
                                .build());
            } else if ("spring.jpa.open-in-view".equals(name) && "true".equalsIgnoreCase(value)) {
                findings.add(
                        FindingFactory.builder(
                                        FindingRules.SPRING_JPA_OPEN_IN_VIEW,
                                        FindingConfidence.HIGH)
                                .shortMessage("spring.jpa.open-in-view=true is explicitly enabled.")
                                .whyBadPractice(
                                        "Open-session-in-view keeps the Hibernate session open"
                                            + " through the entire HTTP request, including the view"
                                            + " rendering phase. This silently enables lazy loading"
                                            + " outside the service layer and masks N+1 query"
                                            + " problems.")
                                .possibleImpact(
                                        "Unexpected database queries fire during serialization or"
                                            + " view rendering, making performance problems hard to"
                                            + " diagnose and reproduce. Transactions may hold"
                                            + " database connections longer than necessary.")
                                .recommendation(
                                        "Set spring.jpa.open-in-view=false and load all required"
                                                + " data explicitly in the service layer using DTO"
                                                + " projections, JOIN FETCH, or entity graphs.")
                                .evidence(
                                        "spring.jpa.open-in-view=true was found in "
                                                + property.sourceFile()
                                                + ".")
                                .limitations(
                                        "Some applications intentionally use open-in-view for"
                                            + " simplicity in read-heavy screens. Verify whether"
                                            + " lazy loading outside the service layer is an"
                                            + " intentional design choice.")
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
            FindingConfidence confidence) {
        return FindingFactory.builder(FindingRules.SPRING_RISKY_PROD_CONFIG, confidence)
                .shortMessage(shortMessage)
                .whyBadPractice(whyBadPractice)
                .possibleImpact(possibleImpact)
                .recommendation(recommendation)
                .evidence(target + " was found in " + property.sourceFile() + ".")
                .limitations(
                        "Static analysis cannot prove whether this profile is always active in"
                                + " deployment, but the filename or profile marker indicates"
                                + " production-oriented usage.")
                .source(property.sourceFile(), property.line())
                .target(target)
                .build();
    }

    private void detectCrossProfileDrift(
            ConfigurationAnalysis configurationAnalysis, List<Finding> findings) {
        Map<String, List<ApplicationProperty>> grouped =
                groupPropertiesByName(configurationAnalysis);
        for (Map.Entry<String, List<ApplicationProperty>> entry : grouped.entrySet()) {
            List<ApplicationProperty> properties = entry.getValue();
            Set<String> profiles =
                    properties.stream()
                            .map(ApplicationProperty::profile)
                            .filter(Objects::nonNull)
                            .map(this::normalizedProfile)
                            .collect(Collectors.toCollection(LinkedHashSet::new));
            if (profiles.size() < 2) {
                continue;
            }
            Set<String> distinctValues =
                    properties.stream()
                            .map(ApplicationProperty::value)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toCollection(LinkedHashSet::new));
            if (distinctValues.size() > 1 && isDriftRelevantProperty(entry.getKey())) {
                String evidence =
                        properties.stream()
                                .map(
                                        property ->
                                                normalizedProfile(property.profile())
                                                        + "="
                                                        + renderedValue(property))
                                .collect(Collectors.joining(", "));
                ApplicationProperty driftPrimary =
                        properties.stream()
                                .filter(p -> p.sourceFile() != null)
                                .findFirst()
                                .orElse(null);
                FindingFactory.Builder driftBuilder =
                        FindingFactory.builder(
                                        FindingRules.SPRING_PROFILE_DRIFT, FindingConfidence.HIGH)
                                .shortMessage(
                                        "Configuration differs across profiles for "
                                                + entry.getKey())
                                .whyBadPractice(
                                        "Spring evaluates only the active profile at runtime, so"
                                            + " static drift between profile files is easy to miss"
                                            + " during local startup checks.")
                                .possibleImpact(
                                        "Different profiles may call different external services,"
                                            + " expose different diagnostics, or enable different"
                                            + " scheduling behavior after deployment.")
                                .recommendation(
                                        "Review profile overrides together, document the intended"
                                            + " environment-specific behavior, and add tests or"
                                            + " smoke checks for critical profile combinations.")
                                .evidence("Profile values detected: " + evidence + ".")
                                .limitations(
                                        "Static analysis cannot prove which profile is active in"
                                            + " production or whether higher-precedence environment"
                                            + " variables override these files.")
                                .target(entry.getKey());
                if (driftPrimary != null) {
                    driftBuilder.source(driftPrimary.sourceFile(), driftPrimary.line());
                } else {
                    driftBuilder.location("Configuration");
                }
                for (ApplicationProperty prop : properties) {
                    if (prop.sourceFile() != null) {
                        int lineNum = prop.line() != null ? prop.line() : 0;
                        String label =
                                normalizedProfile(prop.profile()) + "=" + renderedValue(prop);
                        driftBuilder.addOccurrence(
                                new FindingOccurrence(
                                        label,
                                        new SourceLocation(
                                                prop.sourceFile(),
                                                lineNum,
                                                lineNum,
                                                null,
                                                null,
                                                entry.getKey(),
                                                null,
                                                null),
                                        null));
                    }
                }
                findings.add(driftBuilder.build());
            }
        }
    }

    private void detectConditionalBeanMatrixIssues(
            ConfigurationAnalysis configurationAnalysis, List<Finding> findings) {
        if (configurationAnalysis == null || configurationAnalysis.codeReferences() == null) {
            return;
        }
        Map<String, List<PropertyReference>> grouped =
                configurationAnalysis.codeReferences().stream()
                        .filter(
                                reference ->
                                        "@ConditionalOnProperty".equals(reference.referenceType()))
                        .collect(
                                Collectors.groupingBy(
                                        PropertyReference::propertyName,
                                        LinkedHashMap::new,
                                        Collectors.toList()));
        Map<String, List<ApplicationProperty>> configuredByName =
                groupPropertiesByName(configurationAnalysis);
        for (Map.Entry<String, List<PropertyReference>> entry : grouped.entrySet()) {
            String propertyName = entry.getKey();
            List<PropertyReference> references = entry.getValue();
            long matchIfMissingCount =
                    references.stream()
                            .filter(reference -> Boolean.TRUE.equals(reference.matchIfMissing()))
                            .count();
            if (matchIfMissingCount > 1) {
                PropertyReference reference = references.get(0);
                findings.add(
                        FindingFactory.builder(
                                        FindingRules.SPRING_CONDITIONAL_MATCH_IF_MISSING_OVERLAP,
                                        FindingConfidence.HIGH)
                                .shortMessage(
                                        "Multiple conditional beans use matchIfMissing=true for "
                                                + propertyName)
                                .whyBadPractice(
                                        "Overlapping matchIfMissing defaults make the selected bean"
                                            + " depend on classpath order and configuration gaps"
                                            + " rather than explicit provider choices.")
                                .possibleImpact(
                                        "Different environments may activate unexpected"
                                            + " implementations when the controlling property is"
                                            + " absent or misconfigured.")
                                .recommendation(
                                        "Prefer explicit provider values, keep one default path at"
                                                + " most, and add focused tests for each provider"
                                                + " choice.")
                                .evidence(
                                        "Conditional bean references for "
                                                + propertyName
                                                + " declared matchIfMissing=true in multiple"
                                                + " locations.")
                                .limitations(
                                        "Static analysis cannot fully simulate Spring condition"
                                                + " ordering when additional custom conditions are"
                                                + " involved.")
                                .source(reference.sourceFile(), null)
                                .target(propertyName)
                                .build());
            }
            List<ApplicationProperty> configured =
                    configuredByName.getOrDefault(propertyName, List.of());
            Set<String> expectedValues =
                    references.stream()
                            .map(PropertyReference::expectedValue)
                            .filter(value -> value != null && !value.isBlank())
                            .collect(Collectors.toCollection(LinkedHashSet::new));
            for (ApplicationProperty property : configured) {
                if (property.value() == null
                        || property.placeholderValue()
                        || expectedValues.isEmpty()) {
                    continue;
                }
                if (!expectedValues.contains(property.value())) {
                    findings.add(
                            FindingFactory.builder(
                                            FindingRules.SPRING_CONDITIONAL_VALUE_MISMATCH,
                                            FindingConfidence.HIGH)
                                    .shortMessage(
                                            "Configured value for "
                                                    + propertyName
                                                    + " does not match any detected conditional"
                                                    + " bean.")
                                    .whyBadPractice(
                                            "Conditional bean matrices make runtime behavior depend"
                                                    + " on configuration values that may not be"
                                                    + " exercised in every environment.")
                                    .possibleImpact(
                                            "A profile can select no provider at all, or activate"
                                                    + " an unexpected fallback path that was not"
                                                    + " covered by tests.")
                                    .recommendation(
                                            "Document supported provider values, keep them"
                                                    + " explicit, and add focused tests for every"
                                                    + " expected value.")
                                    .evidence(
                                            propertyName
                                                    + "="
                                                    + property.value()
                                                    + " was configured, while detected"
                                                    + " @ConditionalOnProperty values were "
                                                    + String.join(", ", expectedValues)
                                                    + ".")
                                    .limitations(
                                            "Static analysis cannot see custom condition logic,"
                                                    + " imported auto-configurations, or runtime"
                                                    + " overrides from environment variables.")
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
            List<Finding> findings) {
        boolean flywayPresent =
                dependencyPresent(buildInfo, gradleModelAnalysis, "org.flywaydb", "flyway-core")
                        || hasProperty(configurationAnalysis, "spring.flyway.enabled");
        if (!flywayPresent) {
            return;
        }
        // Include the resolved Flyway version in evidence when the Gradle model is available.
        String flywayVersion = resolvedVersion("org.flywaydb", "flyway-core", gradleModelAnalysis);
        String flywayLabel = flywayVersion != null ? "Flyway " + flywayVersion : "Flyway";
        ApplicationProperty ddlAuto =
                findProperty(configurationAnalysis, "spring.jpa.hibernate.ddl-auto");
        if (ddlAuto != null
                && ddlAuto.value() != null
                && List.of("update", "create", "create-drop", "drop")
                        .contains(ddlAuto.value().toLowerCase(Locale.ROOT))) {
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_FLYWAY_DDL_AUTO_MIX, FindingConfidence.HIGH)
                            .shortMessage(
                                    "Flyway is enabled while Hibernate DDL auto-update is also"
                                            + " configured.")
                            .whyBadPractice(
                                    "Mixing migration-based schema management with automatic"
                                        + " Hibernate schema mutation creates two competing sources"
                                        + " of truth for database changes.")
                            .possibleImpact(
                                    "Schema drift, unexpected startup mutations, and migration"
                                        + " behavior that differs across environments become much"
                                        + " harder to reason about.")
                            .recommendation(
                                    "Use Flyway as the primary schema change mechanism and keep"
                                        + " Hibernate DDL mutation disabled or limited to validate"
                                        + " in shared environments.")
                            .evidence(
                                    flywayLabel
                                            + " was detected and "
                                            + ddlAuto.name()
                                            + "="
                                            + ddlAuto.value()
                                            + " was found in "
                                            + ddlAuto.sourceFile()
                                            + ".")
                            .limitations(
                                    "Static analysis cannot prove which schema tool wins in every"
                                            + " environment, but the combination is operationally"
                                            + " risky.")
                            .source(ddlAuto.sourceFile(), ddlAuto.line())
                            .target("schema management")
                            .build());
        }
        List<Path> migrations = migrationFiles(repositoryRoot);
        if (migrations.isEmpty()) {
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_FLYWAY_MISSING_MIGRATIONS,
                                    FindingConfidence.MEDIUM)
                            .shortMessage(
                                    "Flyway appears to be enabled, but no migration files were"
                                            + " found under db/migration.")
                            .whyBadPractice(
                                    "Schema migration tooling is most useful when migration files"
                                            + " are versioned alongside the application.")
                            .possibleImpact(
                                    "Deployments may rely on ad hoc schema changes or fail only"
                                            + " after startup reaches database code paths.")
                            .recommendation(
                                    "Check Flyway locations, commit reviewed migration files, and"
                                            + " keep schema changes explicit in version control.")
                            .evidence(
                                    flywayLabel
                                            + " configuration or dependencies were detected,"
                                            + " but no V__ migration files were found in"
                                            + " src/main/resources/db/migration.")
                            .limitations(
                                    "Static analysis looks in the conventional migration folder and"
                                            + " may miss custom locations configured elsewhere.")
                            .location("db/migration")
                            .target("flyway migrations")
                            .build());
        } else {
            Map<String, List<Path>> byVersion =
                    migrations.stream()
                            .map(path -> Map.entry(migrationVersion(path), path))
                            .filter(entry -> entry.getKey() != null)
                            .collect(
                                    Collectors.groupingBy(
                                            Map.Entry::getKey,
                                            LinkedHashMap::new,
                                            Collectors.mapping(
                                                    Map.Entry::getValue, Collectors.toList())));
            byVersion.forEach(
                    (version, paths) -> {
                        if (paths.size() > 1) {
                            findings.add(
                                    FindingFactory.builder(
                                                    "SPRING_FLYWAY_DUPLICATE_VERSION",
                                                    "Duplicate Flyway migration version detected",
                                                    com.robbanhoglund.springbootanalyzer.analyzer
                                                            .model.FindingSeverity.WARNING,
                                                    com.robbanhoglund.springbootanalyzer.analyzer
                                                            .model.FindingCategory.PERSISTENCE,
                                                    com.robbanhoglund.springbootanalyzer.analyzer
                                                            .model.FindingRuntimeDetection
                                                            .NOT_NORMALLY_DETECTED,
                                                    FindingConfidence.HIGH)
                                            .shortMessage(
                                                    "Flyway migration version "
                                                            + version
                                                            + " appears more than once.")
                                            .whyBadPractice(
                                                    "Duplicate migration versions make migration"
                                                        + " ordering ambiguous and can fail at"
                                                        + " deployment time in ways that are hard"
                                                        + " to recover from quickly.")
                                            .possibleImpact(
                                                    "Deployments may stop on migration validation"
                                                        + " errors or apply an unexpected migration"
                                                        + " ordering.")
                                            .recommendation(
                                                    "Keep Flyway versions unique and monotonic, and"
                                                            + " review migration naming during code"
                                                            + " review.")
                                            .evidence(
                                                    paths.stream()
                                                            .map(
                                                                    path ->
                                                                            repositoryRoot
                                                                                    .relativize(
                                                                                            path)
                                                                                    .toString()
                                                                                    .replace(
                                                                                            '\\',
                                                                                            '/'))
                                                            .collect(Collectors.joining(", ")))
                                            .limitations(
                                                    "Static analysis checks file naming only and"
                                                            + " cannot validate migrations stored"
                                                            + " outside the repository.")
                                            .target("Flyway migration version " + version)
                                            .build());
                        }
                    });
        }
    }

    private void detectOpenInViewNotDisabled(
            ConfigurationAnalysis configurationAnalysis, List<Finding> findings) {
        if (configurationAnalysis == null) {
            return;
        }
        boolean jpaConfigured =
                configurationAnalysis.properties().stream()
                        .anyMatch(
                                p -> p.name() != null && p.name().startsWith("spring.datasource."));
        if (!jpaConfigured) {
            return;
        }
        boolean openInViewExplicit =
                configurationAnalysis.properties().stream()
                        .anyMatch(p -> "spring.jpa.open-in-view".equals(p.name()));
        if (!openInViewExplicit) {
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_JPA_OPEN_IN_VIEW, FindingConfidence.MEDIUM)
                            .shortMessage(
                                    "spring.jpa.open-in-view is not explicitly set and defaults to"
                                            + " true.")
                            .whyBadPractice(
                                    "Spring Boot defaults open-in-view to true, keeping the"
                                        + " Hibernate session open across the entire HTTP request"
                                        + " including serialization. This silently enables lazy"
                                        + " loading outside the service layer and masks N+1 query"
                                        + " problems.")
                            .possibleImpact(
                                    "Unexpected queries fire during JSON serialization or view"
                                        + " rendering. Transactions hold connections longer than"
                                        + " needed. Performance problems are hard to diagnose.")
                            .recommendation(
                                    "Add spring.jpa.open-in-view=false to your"
                                        + " application.properties or application.yml and load all"
                                        + " required data explicitly in the service layer.")
                            .evidence(
                                    "A datasource configuration was detected but"
                                        + " spring.jpa.open-in-view was not explicitly set, leaving"
                                        + " it at the Spring Boot default of true.")
                            .limitations(
                                    "If the project uses Spring Data REST or a view layer that"
                                            + " depends on lazy loading, disabling open-in-view"
                                            + " requires explicit fetch strategies to be added.")
                            .location("Configuration")
                            .build());
        }
    }

    private void detectJpaDdlAutoDangerous(
            ConfigurationAnalysis configurationAnalysis, List<Finding> findings) {
        if (configurationAnalysis == null || configurationAnalysis.properties() == null) {
            return;
        }
        Set<String> dangerousValues = Set.of("create", "create-drop");
        Set<String> targetProperties =
                Set.of("spring.jpa.hibernate.ddl-auto", "spring.datasource.initialization-mode");
        for (ApplicationProperty property : configurationAnalysis.properties()) {
            if (property == null || property.name() == null || property.value() == null) {
                continue;
            }
            if (!targetProperties.contains(property.name())) {
                continue;
            }
            if (!dangerousValues.contains(property.value().toLowerCase(Locale.ROOT))) {
                continue;
            }
            String profile = normalizedProfile(property.profile());
            if (!isProdLikeProfile(profile)) {
                continue;
            }
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_JPA_DDL_AUTO_DANGEROUS,
                                    FindingConfidence.HIGH)
                            .shortMessage(
                                    property.name()
                                            + "="
                                            + property.value()
                                            + " drops and recreates the schema on every startup in"
                                            + " a production-oriented profile.")
                            .whyBadPractice(
                                    "The values 'create' and 'create-drop' drop all tables and"
                                        + " recreate them from scratch on every application"
                                        + " startup. In a production or staging environment this"
                                        + " destroys all existing data unconditionally.")
                            .possibleImpact(
                                    "Every deployment wipes the database. This is almost certainly"
                                        + " unintentional in a production environment and the data"
                                        + " loss cannot be undone.")
                            .recommendation(
                                    "Set "
                                            + property.name()
                                            + "=validate or none in production profiles. Manage"
                                            + " schema changes through Flyway or Liquibase"
                                            + " migrations.")
                            .evidence(
                                    property.name()
                                            + "="
                                            + property.value()
                                            + " was found in "
                                            + property.sourceFile()
                                            + " (profile: "
                                            + profile
                                            + ").")
                            .limitations(
                                    "Static analysis cannot determine whether the target database"
                                        + " is disposable or whether the profile is always active"
                                        + " in deployment.")
                            .source(property.sourceFile(), property.line())
                            .target(property.name())
                            .build());
        }
    }

    private void detectMissingSecurityStarter(BuildInfo buildInfo, List<Finding> findings) {
        if (buildInfo == null || buildInfo.dependencies() == null) {
            return;
        }
        boolean hasWebStarter =
                buildInfo.dependencies().stream()
                        .anyMatch(
                                dep ->
                                        dep.contains("spring-boot-starter-web")
                                                || dep.contains("spring-boot-starter-webflux"));
        boolean hasSecurityStarter =
                buildInfo.dependencies().stream()
                        .anyMatch(
                                dep ->
                                        dep.contains("spring-boot-starter-security")
                                                || dep.contains("spring-security-core")
                                                || dep.contains("spring-security-web"));
        if (hasWebStarter && !hasSecurityStarter) {
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_SECURITY_STARTER_MISSING,
                                    FindingConfidence.MEDIUM)
                            .shortMessage("Web application has no Spring Security dependency.")
                            .whyBadPractice(
                                    "Web applications without a security dependency have no"
                                        + " authentication or authorization protection enforced by"
                                        + " the framework by default.")
                            .possibleImpact(
                                    "All endpoints are publicly accessible unless secured by an"
                                        + " external gateway or custom filter not visible in the"
                                        + " build file.")
                            .recommendation(
                                    "Add spring-boot-starter-security and configure appropriate"
                                            + " authentication and authorization rules.")
                            .evidence(
                                    "spring-boot-starter-web or spring-boot-starter-webflux was"
                                        + " detected but no Spring Security dependency was found in"
                                        + " the build file.")
                            .limitations(
                                    "Security may be provided by an API gateway, service mesh, or"
                                        + " custom filter chain not declared in the build file.")
                            .location("Build configuration")
                            .build());
        }
    }

    private void detectActuatorExposure(
            ConfigurationAnalysis configurationAnalysis, List<Finding> findings) {
        Set<String> dangerous = Set.of("env", "configprops", "heapdump", "threaddump", "shutdown");
        for (ApplicationProperty property :
                configurationAnalysis == null
                        ? List.<ApplicationProperty>of()
                        : configurationAnalysis.properties()) {
            if (property == null
                    || !"management.endpoints.web.exposure.include".equals(property.name())
                    || property.value() == null) {
                continue;
            }
            String value = property.value().trim();
            boolean exposesAll = "*".equals(value);
            boolean exposesDangerous =
                    !exposesAll
                            && Stream.of(value.split(","))
                                    .map(String::trim)
                                    .anyMatch(dangerous::contains);
            if (!exposesAll && !exposesDangerous) {
                continue;
            }
            String exposed =
                    exposesAll
                            ? "*"
                            : Stream.of(value.split(","))
                                    .map(String::trim)
                                    .filter(dangerous::contains)
                                    .collect(Collectors.joining(", "));
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_ACTUATOR_ENDPOINT_EXPOSED_PROD,
                                    FindingConfidence.HIGH)
                            .shortMessage(
                                    "Sensitive actuator endpoint(s) exposed: " + exposed + ".")
                            .whyBadPractice(
                                    "Endpoints like env and configprops expose all loaded"
                                        + " environment variables and configuration properties"
                                        + " including secrets. heapdump and threaddump expose"
                                        + " runtime internals. shutdown can terminate the process"
                                        + " remotely.")
                            .possibleImpact(
                                    "Unauthenticated access to /actuator/env can leak credentials"
                                            + " and API keys. /actuator/shutdown can be used for"
                                            + " denial-of-service. These endpoints are frequently"
                                            + " targeted in Spring Boot attacks.")
                            .recommendation(
                                    "Restrict exposure to health and info for public applications:"
                                        + " management.endpoints.web.exposure.include=health,info."
                                        + " Protect any additional endpoints behind authentication"
                                        + " or network controls.")
                            .evidence(
                                    "management.endpoints.web.exposure.include="
                                            + value
                                            + " found in "
                                            + property.sourceFile()
                                            + ".")
                            .limitations(
                                    "Static analysis cannot determine whether Spring Security or a"
                                            + " firewall restricts access to actuator endpoints at"
                                            + " runtime.")
                            .source(property.sourceFile(), property.line())
                            .target("management.endpoints.web.exposure.include")
                            .build());
        }
    }

    private void detectConnectionPoolMisconfiguration(
            ConfigurationAnalysis configurationAnalysis, List<Finding> findings) {
        if (configurationAnalysis == null) {
            return;
        }
        for (ApplicationProperty property : configurationAnalysis.properties()) {
            if (property == null || property.name() == null || property.value() == null) {
                continue;
            }
            if (!"spring.datasource.hikari.maximum-pool-size".equals(property.name())) {
                continue;
            }
            try {
                int size = Integer.parseInt(property.value().trim());
                if (size < 2) {
                    findings.add(
                            FindingFactory.builder(
                                            FindingRules.SPRING_CONNECTION_POOL_MISCONFIGURED,
                                            FindingConfidence.HIGH)
                                    .shortMessage(
                                            "HikariCP maximum-pool-size="
                                                    + size
                                                    + " is too small for production use.")
                                    .whyBadPractice(
                                            "A pool of size 1 serializes all database access"
                                                    + " through a single connection. Any concurrent"
                                                    + " request must wait, and a single slow query"
                                                    + " blocks the entire application.")
                                    .possibleImpact(
                                            "Severe throughput degradation under any concurrent"
                                                    + " load. A single blocked connection causes"
                                                    + " application-wide request queuing.")
                                    .recommendation(
                                            "Set maximum-pool-size to at least the number of"
                                                + " concurrent threads that access the database,"
                                                + " typically 5–20 for most applications.")
                                    .evidence(
                                            "spring.datasource.hikari.maximum-pool-size="
                                                    + size
                                                    + " found in "
                                                    + property.sourceFile()
                                                    + ".")
                                    .limitations(
                                            "Static analysis cannot determine the application's"
                                                    + " actual concurrency requirements.")
                                    .source(property.sourceFile(), property.line())
                                    .target("spring.datasource.hikari.maximum-pool-size")
                                    .build());
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    // --- Helpers ---

    private Map<String, List<ApplicationProperty>> groupPropertiesByName(
            ConfigurationAnalysis configurationAnalysis) {
        if (configurationAnalysis == null || configurationAnalysis.properties() == null) {
            return Map.of();
        }
        return configurationAnalysis.properties().stream()
                .filter(property -> property != null && property.name() != null)
                .collect(
                        Collectors.groupingBy(
                                ApplicationProperty::name,
                                LinkedHashMap::new,
                                Collectors.toList()));
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

    private ApplicationProperty findProperty(
            ConfigurationAnalysis configurationAnalysis, String name) {
        if (configurationAnalysis == null || configurationAnalysis.properties() == null) {
            return null;
        }
        return configurationAnalysis.properties().stream()
                .filter(property -> name.equals(property.name()))
                .findFirst()
                .orElse(null);
    }

    private boolean dependencyPresent(
            BuildInfo buildInfo,
            GradleModelAnalysis gradleModelAnalysis,
            String group,
            String artifact) {
        boolean inBuildInfo =
                buildInfo.dependencies().stream()
                        .anyMatch(
                                value ->
                                        value.toLowerCase(Locale.ROOT)
                                                .contains(
                                                        (group + ":" + artifact)
                                                                .toLowerCase(Locale.ROOT)));
        boolean inGradleModel =
                gradleModelAnalysis != null
                        && gradleModelAnalysis.resolvedDependencies() != null
                        && gradleModelAnalysis.resolvedDependencies().stream()
                                .anyMatch(
                                        dependency ->
                                                group.equals(dependency.group())
                                                        && artifact.equals(dependency.artifact()));
        return inBuildInfo || inGradleModel;
    }

    private List<Path> migrationFiles(Path repositoryRoot) {
        Path migrationRoot = repositoryRoot.resolve("src/main/resources/db/migration");
        if (Files.notExists(migrationRoot)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(migrationRoot)) {
            return files.filter(Files::isRegularFile)
                    .filter(
                            path ->
                                    FLYWAY_MIGRATION_PATTERN
                                            .matcher(path.getFileName().toString())
                                            .matches())
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

    private void detectSecurityAutoconfigureExcluded(
            ConfigurationAnalysis configurationAnalysis, List<Finding> findings) {
        if (configurationAnalysis == null || configurationAnalysis.properties() == null) {
            return;
        }
        for (ApplicationProperty property : configurationAnalysis.properties()) {
            if (!"spring.autoconfigure.exclude".equals(property.name())) {
                continue;
            }
            if (property.value() == null) {
                continue;
            }
            String excluded =
                    SECURITY_AUTO_CONFIG_CLASSES.stream()
                            .filter(property.value()::contains)
                            .collect(Collectors.joining(", "));
            if (excluded.isBlank()) {
                continue;
            }
            String profile = normalizedProfile(property.profile());
            FindingFactory.Builder builder =
                    FindingFactory.builder(
                                    FindingRules.SPRING_SECURITY_AUTOCONFIGURE_EXCLUDED,
                                    FindingConfidence.HIGH)
                            .shortMessage(
                                    "spring.autoconfigure.exclude removes "
                                            + excluded
                                            + " — Spring Security is not loaded"
                                            + (profile.equals("default")
                                                    ? " in the default profile"
                                                    : " in the \"" + profile + "\" profile"))
                            .whyBadPractice(
                                    "Excluding security auto-configuration removes Spring"
                                        + " Security's default filter chain, user-details wiring,"
                                        + " and security headers for this profile.")
                            .possibleImpact(
                                    "All endpoints become unprotected, CSRF protection is absent,"
                                        + " and tests running under this profile do not exercise"
                                        + " the real security configuration at all.")
                            .recommendation(
                                    "Prefer @WithMockUser, SecurityMockMvcConfigurer, or a"
                                            + " dedicated test security configuration instead of"
                                            + " excluding auto-configuration classes.")
                            .evidence(
                                    "spring.autoconfigure.exclude contains: "
                                            + excluded
                                            + " in profile \""
                                            + profile
                                            + "\".")
                            .limitations(
                                    "Static analysis cannot determine whether a replacement"
                                        + " security configuration is loaded through an alternative"
                                        + " import or @Configuration class.")
                            .target(property.name());
            if (property.sourceFile() != null) {
                builder.source(property.sourceFile(), property.line());
            } else {
                builder.location("Configuration");
            }
            findings.add(builder.build());
        }
    }

    private void detectDatasourceNoTestOverride(
            ConfigurationAnalysis configurationAnalysis, List<Finding> findings) {
        if (configurationAnalysis == null || configurationAnalysis.properties() == null) {
            return;
        }
        List<ApplicationProperty> datasourceUrls =
                configurationAnalysis.properties().stream()
                        .filter(p -> "spring.datasource.url".equals(p.name()))
                        .filter(p -> p.value() != null)
                        .toList();
        ApplicationProperty defaultEntry =
                datasourceUrls.stream()
                        .filter(p -> "default".equals(normalizedProfile(p.profile())))
                        .filter(p -> !isEmbeddedDatabase(p.value()))
                        .findFirst()
                        .orElse(null);
        if (defaultEntry == null) {
            return;
        }
        boolean hasTestOverride =
                datasourceUrls.stream()
                        .anyMatch(p -> isTestProfile(normalizedProfile(p.profile())));
        if (hasTestOverride) {
            return;
        }
        FindingFactory.Builder builder =
                FindingFactory.builder(
                                FindingRules.SPRING_DATASOURCE_NO_TEST_OVERRIDE,
                                FindingConfidence.MEDIUM)
                        .shortMessage(
                                "spring.datasource.url points to a real database in the default"
                                        + " profile but no test profile overrides it")
                        .whyBadPractice(
                                "Without a test-profile datasource override, any Spring context"
                                        + " integration test loads the default profile and connects"
                                        + " to the production-adjacent database.")
                        .possibleImpact(
                                "Integration tests may read from or write to shared or"
                                        + " production data, causing data corruption, flaky tests,"
                                        + " and misleading test results.")
                        .recommendation(
                                "Add spring.datasource.url, spring.datasource.username, and"
                                        + " spring.datasource.password in"
                                        + " src/test/resources/application-test.properties pointing"
                                        + " to an embedded or containerised test database.")
                        .evidence(
                                "Default datasource URL: "
                                        + renderedValue(defaultEntry)
                                        + ". No test-profile datasource URL was detected.")
                        .limitations(
                                "Static analysis cannot confirm whether @DataJpaTest,"
                                        + " Testcontainers, or another mechanism supplies a test"
                                        + " datasource at runtime.")
                        .target(defaultEntry.name());
        if (defaultEntry.sourceFile() != null) {
            builder.source(defaultEntry.sourceFile(), defaultEntry.line());
        } else {
            builder.location("Configuration");
        }
        findings.add(builder.build());
    }

    private void detectH2InNonTestProfile(
            ConfigurationAnalysis configurationAnalysis, List<Finding> findings) {
        if (configurationAnalysis == null || configurationAnalysis.properties() == null) {
            return;
        }
        for (ApplicationProperty property : configurationAnalysis.properties()) {
            if (!"spring.datasource.url".equals(property.name())) {
                continue;
            }
            if (property.value() == null
                    || !property.value().toLowerCase(Locale.ROOT).startsWith("jdbc:h2:")) {
                continue;
            }
            String profile = normalizedProfile(property.profile());
            if (isTestOrLocalProfile(profile)) {
                continue;
            }
            FindingFactory.Builder builder =
                    FindingFactory.builder(
                                    FindingRules.SPRING_H2_IN_NON_TEST_PROFILE,
                                    FindingConfidence.HIGH)
                            .shortMessage(
                                    "Embedded H2 database configured in the \""
                                            + profile
                                            + "\" profile, which does not look like a test or"
                                            + " local profile")
                            .whyBadPractice(
                                    "H2 is an in-process in-memory database intended for testing"
                                        + " and local development. Using it in a non-test profile"
                                        + " means all data is lost on restart.")
                            .possibleImpact(
                                    "If this profile is ever activated in production or staging,"
                                            + " the application silently loses all stored data on"
                                            + " every restart, with no error or warning.")
                            .recommendation(
                                    "Replace the H2 datasource URL with a production-grade"
                                            + " database for non-test profiles and restrict H2 to"
                                            + " profiles named test, local, or dev.")
                            .evidence(
                                    "spring.datasource.url uses H2 in profile \""
                                            + profile
                                            + "\": "
                                            + property.value())
                            .limitations(
                                    "Static analysis cannot determine which profiles are activated"
                                            + " in each deployment environment.")
                            .target(property.name());
            if (property.sourceFile() != null) {
                builder.source(property.sourceFile(), property.line());
            } else {
                builder.location("Configuration");
            }
            findings.add(builder.build());
        }
    }

    private void detectFlywayDisabledInTest(
            ConfigurationAnalysis configurationAnalysis, List<Finding> findings) {
        if (configurationAnalysis == null || configurationAnalysis.properties() == null) {
            return;
        }
        for (ApplicationProperty property : configurationAnalysis.properties()) {
            if (!"spring.flyway.enabled".equals(property.name())) {
                continue;
            }
            if (!"false".equalsIgnoreCase(property.value())) {
                continue;
            }
            String profile = normalizedProfile(property.profile());
            if (!isTestProfile(profile)) {
                continue;
            }
            FindingFactory.Builder builder =
                    FindingFactory.builder(
                                    FindingRules.SPRING_FLYWAY_DISABLED_IN_TEST,
                                    FindingConfidence.HIGH)
                            .shortMessage(
                                    "spring.flyway.enabled=false in the \""
                                            + profile
                                            + "\" profile — schema migrations are skipped in tests")
                            .whyBadPractice(
                                    "Disabling Flyway in test profiles means migration scripts are"
                                        + " never executed during CI, so incompatible migrations"
                                        + " pass tests and fail only in staging or production.")
                            .possibleImpact(
                                    "Schema migration errors — missing columns, wrong types,"
                                            + " constraint violations — are discovered only after"
                                            + " deployment, when rollback is costly.")
                            .recommendation(
                                    "Enable Flyway in test profiles and point spring.datasource.url"
                                        + " at an embedded or containerised database so that every"
                                        + " CI run validates the full migration path.")
                            .evidence(
                                    "spring.flyway.enabled=false detected in profile \""
                                            + profile
                                            + "\".")
                            .limitations(
                                    "Static analysis cannot detect whether a custom schema"
                                        + " initializer or Testcontainers setup replicates Flyway"
                                        + " behavior in tests.")
                            .target(property.name());
            if (property.sourceFile() != null) {
                builder.source(property.sourceFile(), property.line());
            } else {
                builder.location("Configuration");
            }
            findings.add(builder.build());
        }
    }

    private void detectSchedulingDisabledInTest(
            ConfigurationAnalysis configurationAnalysis, List<Finding> findings) {
        if (configurationAnalysis == null || configurationAnalysis.properties() == null) {
            return;
        }
        for (ApplicationProperty property : configurationAnalysis.properties()) {
            if (!SCHEDULING_DISABLE_PROPERTIES.contains(property.name())) {
                continue;
            }
            if (!"false".equalsIgnoreCase(property.value())) {
                continue;
            }
            String profile = normalizedProfile(property.profile());
            if (!isTestProfile(profile)) {
                continue;
            }
            FindingFactory.Builder builder =
                    FindingFactory.builder(
                                    FindingRules.SPRING_SCHEDULING_DISABLED_IN_TEST,
                                    FindingConfidence.HIGH)
                            .shortMessage(
                                    property.name()
                                            + "=false in the \""
                                            + profile
                                            + "\" profile — scheduled tasks are never exercised"
                                            + " during tests")
                            .whyBadPractice(
                                    "Disabling the scheduler in tests prevents scheduled methods"
                                            + " from running, so cron expressions, retry logic, and"
                                            + " task timing behavior go untested.")
                            .possibleImpact(
                                    "Bugs in scheduled tasks — infinite loops, data corruption, or"
                                        + " silent failures — are only discovered when they run in"
                                        + " production.")
                            .recommendation(
                                    "Cover critical scheduled methods with targeted unit tests that"
                                        + " call the method directly, or write a @SpringBootTest"
                                        + " slice that enables the scheduler for a focused"
                                        + " integration test.")
                            .evidence(
                                    property.name()
                                            + "=false detected in profile \""
                                            + profile
                                            + "\".")
                            .limitations(
                                    "Static analysis cannot determine whether scheduled methods are"
                                            + " covered by direct unit tests outside the scheduler"
                                            + " context.")
                            .target(property.name());
            if (property.sourceFile() != null) {
                builder.source(property.sourceFile(), property.line());
            } else {
                builder.location("Configuration");
            }
            findings.add(builder.build());
        }
    }

    private void detectHibernateVersionMismatch(
            BuildInfo buildInfo, GradleModelAnalysis gradleModelAnalysis, List<Finding> findings) {
        if (gradleModelAnalysis == null || gradleModelAnalysis.resolvedDependencies() == null) {
            return;
        }
        // Determine effective Spring Boot version: prefer resolved over static build-file hint.
        String bootVersion =
                resolvedVersion("org.springframework.boot", "spring-boot", gradleModelAnalysis);
        if (bootVersion == null) {
            bootVersion = buildInfo.springBootVersion();
        }
        if (bootVersion == null || !bootVersion.startsWith("3.")) {
            return;
        }
        // Spring Boot 3.x requires Hibernate 6.x. Detect explicitly pinned pre-6.x versions.
        String hibernateVersion =
                resolvedVersion("org.hibernate", "hibernate-core", gradleModelAnalysis);
        if (hibernateVersion == null) {
            return;
        }
        int hibernateMajor = parseMajorVersion(hibernateVersion);
        if (hibernateMajor >= 6 || hibernateMajor < 0) {
            return;
        }
        findings.add(
                FindingFactory.builder(
                                FindingRules.SPRING_HIBERNATE_VERSION_MISMATCH,
                                FindingConfidence.HIGH)
                        .shortMessage(
                                "Hibernate "
                                        + hibernateVersion
                                        + " is not compatible with Spring Boot "
                                        + bootVersion
                                        + " — Spring Boot 3.x requires Hibernate 6.x")
                        .whyBadPractice(
                                "Spring Boot 3.x upgraded the JPA integration to Hibernate 6.x and"
                                    + " relies on its API and namespace changes. Pinning Hibernate"
                                    + " to an earlier major version overrides the Spring Boot BOM"
                                    + " and breaks this contract.")
                        .possibleImpact(
                                "The application will fail to start with"
                                        + " ClassNotFoundException, NoSuchMethodError, or"
                                        + " EntityManagerFactory errors when the Hibernate and"
                                        + " Spring Data JPA APIs are mismatched.")
                        .recommendation(
                                "Remove the explicit Hibernate version override from your build"
                                    + " script and let the Spring Boot BOM manage the version, or"
                                    + " upgrade to Hibernate 6.x explicitly.")
                        .evidence(
                                "Resolved org.hibernate:hibernate-core="
                                        + hibernateVersion
                                        + " with Spring Boot "
                                        + bootVersion
                                        + ". Hibernate 6.x is required.")
                        .limitations(
                                "Detected via Gradle resolved dependency graph. This finding only"
                                        + " appears in extended (Gradle model) analysis mode.")
                        .target("org.hibernate:hibernate-core")
                        .build());
    }

    /**
     * Returns the resolved version of a dependency from the Gradle model, or {@code null} if the
     * model is absent, the dependency is not resolved, or the version string is blank.
     */
    private String resolvedVersion(
            String group, String artifact, GradleModelAnalysis gradleModelAnalysis) {
        if (gradleModelAnalysis == null || gradleModelAnalysis.resolvedDependencies() == null) {
            return null;
        }
        return gradleModelAnalysis.resolvedDependencies().stream()
                .filter(dep -> group.equals(dep.group()) && artifact.equals(dep.artifact()))
                .map(GradleResolvedDependencyModel::version)
                .filter(v -> v != null && !v.isBlank())
                .findFirst()
                .orElse(null);
    }

    /** Returns the major version component of a version string, or {@code -1} if unparseable. */
    private int parseMajorVersion(String version) {
        if (version == null || version.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(version.split("[^0-9]")[0]);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private boolean isTestProfile(String normalizedProfile) {
        return TEST_PROFILES.contains(normalizedProfile);
    }

    private boolean isTestOrLocalProfile(String normalizedProfile) {
        return TEST_OR_LOCAL_PROFILES.contains(normalizedProfile);
    }

    private boolean isEmbeddedDatabase(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("jdbc:h2:")
                || lower.startsWith("jdbc:hsqldb:")
                || lower.startsWith("jdbc:derby:");
    }

    private String defaultString(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "";
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_DEVTOOLS_IN_PRODUCTION
    // ---------------------------------------------------------------------------

    private void detectDevToolsInProduction(BuildInfo buildInfo, List<Finding> findings) {
        if (buildInfo == null || buildInfo.dependencies() == null) {
            return;
        }
        boolean hasDevTools =
                buildInfo.dependencies().stream()
                        .anyMatch(dep -> dep.contains("spring-boot-devtools"));
        if (!hasDevTools) {
            return;
        }
        findings.add(
                FindingFactory.builder(
                                FindingRules.SPRING_DEVTOOLS_IN_PRODUCTION,
                                FindingConfidence.MEDIUM)
                        .shortMessage(
                                "spring-boot-devtools is declared as a runtime dependency —"
                                        + " it should not be present in production builds.")
                        .whyBadPractice(
                                "DevTools activates live-reload servers, remote restart endpoints,"
                                    + " and file-system watchers that have no purpose in"
                                    + " production. The remote-restart endpoint allows an"
                                    + " authenticated attacker to reload arbitrary application"
                                    + " state. File-system watchers waste CPU cycles monitoring a"
                                    + " read-only container image and add latency to the startup"
                                    + " sequence.")
                        .possibleImpact(
                                "Exposure of remote-restart and live-reload endpoints if network"
                                    + " controls are absent. Unnecessary CPU and memory overhead in"
                                    + " production pods. Potential class-loading conflicts because"
                                    + " DevTools uses a custom classloader hierarchy.")
                        .recommendation(
                                "In Gradle, declare DevTools in the devOnly configuration so it is"
                                    + " excluded from the production bootJar:"
                                    + " devOnly(\"org.springframework.boot:spring-boot-devtools\")."
                                    + " In Maven, set <optional>true</optional> on the dependency."
                                    + " Verify the final JAR with jar tf build/libs/*.jar | grep"
                                    + " devtools.")
                        .limitations(
                                "Medium confidence — the build tool may already exclude DevTools"
                                        + " from the final artifact via devOnly or optional scope,"
                                        + " which static dependency-list analysis cannot always"
                                        + " distinguish.")
                        .location("Build file")
                        .build());
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_ASYNC_SECURITY_CONTEXT_LOST
    // ---------------------------------------------------------------------------

    private void detectAsyncSecurityContextLost(
            Path repositoryRoot, BuildInfo buildInfo, List<Finding> findings) {
        if (buildInfo == null || buildInfo.dependencies() == null) {
            return;
        }
        boolean hasSpringSecurity =
                buildInfo.dependencies().stream()
                        .anyMatch(
                                dep ->
                                        dep.contains("spring-boot-starter-security")
                                                || dep.contains("spring-security-core")
                                                || dep.contains("spring-security-web"));
        if (!hasSpringSecurity) {
            return;
        }
        if (!sourceContainsText(repositoryRoot, "@Async")) {
            return;
        }
        boolean hasDelegatingExecutor =
                sourceContainsText(repositoryRoot, "DelegatingSecurityContextAsyncTaskExecutor")
                        || sourceContainsText(repositoryRoot, "DelegatingSecurityContextExecutor")
                        || sourceContainsText(repositoryRoot, "MODE_INHERITABLETHREADLOCAL");
        if (hasDelegatingExecutor) {
            return;
        }
        findings.add(
                FindingFactory.builder(
                                FindingRules.SPRING_ASYNC_SECURITY_CONTEXT_LOST,
                                FindingConfidence.MEDIUM)
                        .shortMessage(
                                "@Async methods are present alongside Spring Security, but no"
                                    + " DelegatingSecurityContextAsyncTaskExecutor is configured —"
                                    + " the SecurityContext will not be propagated to async"
                                    + " threads.")
                        .whyBadPractice(
                                "Spring Security stores the authenticated principal in a"
                                    + " ThreadLocal (ThreadLocalSecurityContextHolderStrategy)."
                                    + " When an @Async method runs on a separate thread from the"
                                    + " executor pool, that thread has an empty SecurityContext."
                                    + " Any security check inside the async method — including"
                                    + " @PreAuthorize, SecurityContextHolder.getContext(), or"
                                    + " repository-level method security — will see no"
                                    + " authentication and either throw AccessDeniedException or"
                                    + " behave as if the caller is anonymous.")
                        .possibleImpact(
                                "Silent authorization failures inside async methods. Background"
                                    + " tasks that audit or record the acting user will record a"
                                    + " null or anonymous principal. @PreAuthorize checks in"
                                    + " async-called services will throw AccessDeniedException even"
                                    + " for legitimately authenticated users.")
                        .recommendation(
                                "Configure a DelegatingSecurityContextAsyncTaskExecutor that wraps"
                                    + " the underlying TaskExecutor and copies the SecurityContext"
                                    + " to the new thread before each task runs. Alternatively, set"
                                    + " the SecurityContextHolder strategy to"
                                    + " MODE_INHERITABLETHREADLOCAL via"
                                    + " SecurityContextHolder.setStrategyName(). Note that"
                                    + " InheritableThreadLocal does not work with thread pools"
                                    + " where threads are reused — the delegating executor approach"
                                    + " is preferred.")
                        .limitations(
                                "Medium confidence — async methods may not perform any"
                                    + " security-sensitive operations, in which case the missing"
                                    + " propagation is harmless. Review which @Async methods access"
                                    + " the security context or call @PreAuthorize-annotated"
                                    + " methods.")
                        .location("Build file + src/main/java")
                        .build());
    }

    // ---------------------------------------------------------------------------
    // Source scanning helper
    // ---------------------------------------------------------------------------

    private boolean sourceContainsText(Path repositoryRoot, String text) {
        Path sourceRoot = repositoryRoot.resolve("src/main/java");
        if (Files.notExists(sourceRoot)) {
            return false;
        }
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .anyMatch(
                            p -> {
                                try {
                                    return Files.readString(p, StandardCharsets.UTF_8)
                                            .contains(text);
                                } catch (IOException e) {
                                    return false;
                                }
                            });
        } catch (IOException e) {
            return false;
        }
    }
}
