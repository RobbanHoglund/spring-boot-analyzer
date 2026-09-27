package com.robbanhoglund.springbootanalyzer.analyzer;

import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.robbanhoglund.springbootanalyzer.analyzer.configuration.SensitivePropertyValueRedactor;
import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildInfo;
import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingConfidence;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingFactory;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingOccurrence;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingRules;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingSeverity;
import com.robbanhoglund.springbootanalyzer.analyzer.model.SourceLocation;
import com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.ApplicationProperty;
import com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.ConfigurationAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.PropertyKind;
import com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.PropertyReference;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleModelAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleResolvedDependencyModel;
import com.robbanhoglund.springbootanalyzer.analyzer.source.JavaSources;
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

    /** Excluding this one means the Spring Security filter is never registered. */
    private static final String SECURITY_FILTER_AUTO_CONFIGURATION =
            "SecurityFilterAutoConfiguration";

    /**
     * Auto-configurations that create the default web security chain and enable web security
     * (Spring Boot 3: SecurityAutoConfiguration; Spring Boot 4: ServletWebSecurityAutoConfiguration,
     * plus the reactive variants). Excluding UserDetailsServiceAutoConfiguration only removes the
     * generated default user and is harmless.
     */
    private static final Set<String> SECURITY_CHAIN_AUTO_CONFIGURATIONS =
            Set.of(
                    "SecurityAutoConfiguration",
                    "ServletWebSecurityAutoConfiguration",
                    "ReactiveSecurityAutoConfiguration",
                    "ReactiveWebSecurityAutoConfiguration");

    private static final Set<String> PROFILE_SPECIFIC_INVALID_PROPERTIES =
            Set.of("spring.profiles.active", "spring.profiles.include", "spring.profiles.default");
    private static final Set<String> SCHEDULING_DISABLE_PROPERTIES =
            Set.of("spring.task.scheduling.enabled", "spring.quartz.auto-startup");
    // Flyway versioned migration filename: V<version>__<description>.sql, where <version> is one or
    // more numeric segments separated by '.' or '_' (e.g. V1, V1.0, V2_1, V20230101). The version
    // is intentionally allowed to be a single digit — the common V1__init.sql form.
    // Flyway matches the "V" prefix case-sensitively but the ".sql" suffix case-insensitively.
    private static final Pattern FLYWAY_MIGRATION_PATTERN =
            Pattern.compile("V(?<version>[0-9]+(?:[._][0-9]+)*)__.+\\.(?i:sql)");

    private final SensitivePropertyValueRedactor sensitivePropertyValueRedactor;

    public ConfigurationFindingAnalyzer(
            SensitivePropertyValueRedactor sensitivePropertyValueRedactor) {
        this.sensitivePropertyValueRedactor = sensitivePropertyValueRedactor;
    }

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
        return analyze(
                JavaSources.from(repositoryRoot),
                repositoryRoot,
                buildInfo,
                configurationAnalysis,
                gradleModelAnalysis);
    }

    public List<Finding> analyze(
            JavaSources javaSources,
            Path repositoryRoot,
            BuildInfo buildInfo,
            ConfigurationAnalysis configurationAnalysis,
            GradleModelAnalysis gradleModelAnalysis) {
        List<Finding> findings = new ArrayList<>();
        detectSensitiveProfileDuplication(configurationAnalysis, findings);
        detectAdditionalRiskyConfiguration(configurationAnalysis, findings);
        detectCrossProfileDrift(configurationAnalysis, findings);
        detectHibernateVersionMismatch(buildInfo, gradleModelAnalysis, findings);
        detectSecurityAutoconfigureExcluded(configurationAnalysis, javaSources, findings);
        detectDatasourceNoTestOverride(repositoryRoot, configurationAnalysis, findings);
        detectH2InNonTestProfile(configurationAnalysis, findings);
        detectFlywayDisabledInTest(configurationAnalysis, findings);
        detectSchedulingDisabledInTest(configurationAnalysis, findings);
        detectConditionalBeanMatrixIssues(configurationAnalysis, findings);
        detectFlywaySchemaRisks(
                repositoryRoot, buildInfo, configurationAnalysis, gradleModelAnalysis, findings);
        detectLiquibaseMissingChangelog(
                repositoryRoot, buildInfo, configurationAnalysis, gradleModelAnalysis, findings);
        detectMissingSecurityStarter(buildInfo, findings);
        detectOpenInViewNotDisabled(configurationAnalysis, buildInfo, findings);
        detectActuatorExposure(configurationAnalysis, findings);
        detectConnectionPoolMisconfiguration(configurationAnalysis, findings);
        detectDevToolsInProduction(repositoryRoot, buildInfo, findings);
        detectAsyncSecurityContextLost(javaSources, buildInfo, findings);
        detectMultipartUnlimitedSize(configurationAnalysis, findings);
        detectJdbcUrlEmbeddedCredentials(configurationAnalysis, findings);
        detectDefaultUserPasswordLiteral(configurationAnalysis, findings);
        detectDeprecatedSpringProfiles(configurationAnalysis, buildInfo, findings);
        detectProfilesActiveInProfileSpecificFile(configurationAnalysis, buildInfo, findings);
        detectSqlInitAlwaysProd(configurationAnalysis, findings);
        detectActuatorShowValuesAlways(configurationAnalysis, findings);
        detectActuatorHttptraceRenamed(configurationAnalysis, findings);
        detectRemovedConfigurationProperties(configurationAnalysis, buildInfo, findings);
        detectTaskExecutionMaxPoolIgnored(configurationAnalysis, findings);
        detectDataRestExposedRepositories(
                buildInfo, configurationAnalysis, gradleModelAnalysis, findings);
        return findings;
    }

    /**
     * A configuration key Spring Boot no longer reads. {@code prefix} entries cover a whole
     * namespace; {@code replacement} is null when there is no direct successor, and
     * {@code removedIn} is the Spring Boot line ({@code major.minor}) that stopped reading it.
     */
    private record RemovedProperty(
            String key, boolean prefix, String replacement, String note, String removedIn) {

        /**
         * Whether the key is gone on {@code bootVersion}. For an unknown version only keys
         * removed by Spring Boot 2.5 count; later removals may still be valid on Boot 2.
         */
        boolean removedFor(String bootVersion) {
            String[] parts = removedIn.split("\\.");
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            if (SpringBootVersions.major(bootVersion) < 0) {
                return major < 3;
            }
            return SpringBootVersions.isAtLeast(bootVersion, major, minor);
        }
    }

    /**
     * Keys that Spring Boot removed without keeping a deprecation entry in its metadata. Renamed
     * keys that Spring Boot still describes as deprecated are reported by
     * SPRING_DEPRECATED_CONFIGURATION_PROPERTY instead.
     */
    private static final List<RemovedProperty> REMOVED_PROPERTIES =
            List.of(
                    new RemovedProperty(
                            "server.tomcat.max-threads",
                            false,
                            "server.tomcat.threads.max",
                            null,
                            "2.5"),
                    new RemovedProperty(
                            "server.tomcat.min-spare-threads",
                            false,
                            "server.tomcat.threads.min-spare",
                            null,
                            "2.5"),
                    new RemovedProperty(
                            "server.jetty.max-threads",
                            false,
                            "server.jetty.threads.max",
                            null,
                            "2.5"),
                    new RemovedProperty(
                            "server.jetty.min-threads",
                            false,
                            "server.jetty.threads.min",
                            null,
                            "2.5"),
                    new RemovedProperty(
                            "server.jetty.selectors",
                            false,
                            "server.jetty.threads.selectors",
                            null,
                            "2.5"),
                    new RemovedProperty(
                            "server.jetty.acceptors",
                            false,
                            "server.jetty.threads.acceptors",
                            null,
                            "2.5"),
                    new RemovedProperty(
                            "server.undertow.io-threads",
                            false,
                            "server.undertow.threads.io",
                            null,
                            "2.5"),
                    new RemovedProperty(
                            "server.undertow.worker-threads",
                            false,
                            "server.undertow.threads.worker",
                            null,
                            "2.5"),
                    new RemovedProperty(
                            "spring.elasticsearch.rest.read-timeout",
                            false,
                            "spring.elasticsearch.socket-timeout",
                            null,
                            "3.0"),
                    new RemovedProperty(
                            "spring.elasticsearch.rest.",
                            true,
                            "spring.elasticsearch.",
                            null,
                            "3.0"),
                    new RemovedProperty(
                            "spring.mvc.pathmatch.use-suffix-pattern",
                            false,
                            null,
                            "suffix pattern matching was removed in Spring Framework 6",
                            "3.0"),
                    new RemovedProperty(
                            "spring.mvc.pathmatch.use-registered-suffix-pattern",
                            false,
                            null,
                            "suffix pattern matching was removed in Spring Framework 6",
                            "3.0"),
                    new RemovedProperty(
                            "spring.mvc.contentnegotiation.favor-path-extension",
                            false,
                            null,
                            "path-extension content negotiation was removed in Spring Framework 6",
                            "3.0"),
                    new RemovedProperty(
                            "spring.jpa.hibernate.naming.strategy",
                            false,
                            "spring.jpa.hibernate.naming.physical-strategy",
                            null,
                            "2.0"),
                    new RemovedProperty(
                            "spring.jackson.serialization-inclusion",
                            false,
                            "spring.jackson.default-property-inclusion",
                            null,
                            "2.0"),
                    new RemovedProperty(
                            "security.basic.enabled",
                            false,
                            null,
                            "configure a SecurityFilterChain instead",
                            "2.0"),
                    new RemovedProperty(
                            "security.user.", true, "spring.security.user.", null, "2.0"),
                    new RemovedProperty(
                            "management.security.enabled",
                            false,
                            null,
                            "secure actuator endpoints through Spring Security instead",
                            "2.0"),
                    new RemovedProperty(
                            "server.context-path",
                            false,
                            "server.servlet.context-path",
                            null,
                            "2.0"),
                    new RemovedProperty(
                            "server.servlet-path", false, "spring.mvc.servlet.path", null, "2.0"),
                    new RemovedProperty(
                            "server.session.", true, "server.servlet.session.", null, "2.0"),
                    new RemovedProperty(
                            "spring.http.multipart.",
                            true,
                            "spring.servlet.multipart.",
                            null,
                            "2.0"),
                    new RemovedProperty(
                            "management.port", false, "management.server.port", null, "2.0"),
                    new RemovedProperty(
                            "management.address", false, "management.server.address", null, "2.0"),
                    new RemovedProperty(
                            "management.context-path",
                            false,
                            "management.endpoints.web.base-path",
                            null,
                            "2.0"),
                    new RemovedProperty("flyway.", true, "spring.flyway.", null, "2.0"),
                    new RemovedProperty("liquibase.", true, "spring.liquibase.", null, "2.0"),
                    new RemovedProperty(
                            "endpoints.",
                            true,
                            null,
                            "actuator endpoints are configured under management.endpoint(s).*",
                            "2.0"),
                    new RemovedProperty(
                            "spring.sleuth.",
                            true,
                            null,
                            "Spring Cloud Sleuth does not support Spring Boot 3; tracing is"
                                    + " configured under management.tracing.*",
                            "3.0"),
                    new RemovedProperty(
                            "spring.zipkin.base-url",
                            false,
                            "management.zipkin.tracing.endpoint",
                            null,
                            "3.0"));

    private void detectRemovedConfigurationProperties(
            ConfigurationAnalysis configurationAnalysis,
            BuildInfo buildInfo,
            List<Finding> findings) {
        if (configurationAnalysis == null || configurationAnalysis.properties() == null) {
            return;
        }
        String bootVersion = buildInfo == null ? null : buildInfo.springBootVersion();
        Set<String> reported = new LinkedHashSet<>();
        for (ApplicationProperty property : configurationAnalysis.properties()) {
            // A key the project binds or references itself is its own property, not Boot's.
            if (property.name() == null
                    || property.kind() != PropertyKind.UNKNOWN
                    || !reported.add(property.name() + "@" + property.sourceFile())) {
                continue;
            }
            String name = property.name();
            RemovedProperty removed =
                    REMOVED_PROPERTIES.stream()
                            .filter(
                                    candidate ->
                                            candidate.prefix()
                                                    ? name.startsWith(candidate.key())
                                                    : name.equals(candidate.key()))
                            .filter(candidate -> candidate.removedFor(bootVersion))
                            .findFirst()
                            .orElse(null);
            if (removed == null) {
                continue;
            }
            String replacement =
                    removed.replacement() == null
                            ? null
                            : removed.prefix()
                                    ? removed.replacement() + name.substring(removed.key().length())
                                    : removed.replacement();
            String advice =
                    replacement != null
                            ? "use " + replacement
                            : removed.note() != null ? removed.note() : "remove the setting";
            FindingFactory.Builder builder =
                    FindingFactory.builder(
                                    FindingRules.SPRING_REMOVED_CONFIGURATION_PROPERTY,
                                    FindingConfidence.HIGH)
                            .shortMessage(
                                    name
                                            + " no longer exists in Spring Boot — the setting is"
                                            + " ignored; "
                                            + advice
                                            + ".")
                            .whyBadPractice(
                                    "Spring Boot binds configuration by name. A key it no longer"
                                            + " declares is simply never read, so the value in "
                                            + property.sourceFile()
                                            + " has no effect and no error tells you so.")
                            .possibleImpact(
                                    "The application runs with the default instead of the"
                                            + " configured value — for example the default thread"
                                            + " pool size or timeout.")
                            .recommendation(
                                    replacement != null
                                            ? "Rename the key to "
                                                    + replacement
                                                    + " and check that the value still has the same"
                                                    + " meaning."
                                            : "Remove the key; " + advice + ".")
                            .evidence(name + " is set in " + property.sourceFile() + ".")
                            .limitations(
                                    "Covers keys Spring Boot removed without a deprecation entry;"
                                            + " renamed keys that the metadata still lists are"
                                            + " reported as deprecated properties.")
                            .target(name);
            if (property.sourceFile() != null) {
                builder.source(property.sourceFile(), property.line());
            } else {
                builder.location("Configuration");
            }
            findings.add(builder.build());
        }
    }

    private void detectTaskExecutionMaxPoolIgnored(
            ConfigurationAnalysis configurationAnalysis, List<Finding> findings) {
        ApplicationProperty maxSize =
                findProperty(configurationAnalysis, "spring.task.execution.pool.max-size");
        if (maxSize == null
                || hasProperty(
                        configurationAnalysis, "spring.task.execution.pool.queue-capacity")) {
            return;
        }
        // The pool settings do not apply when virtual threads run the executor.
        ApplicationProperty virtualThreads =
                findProperty(configurationAnalysis, "spring.threads.virtual.enabled");
        if (virtualThreads != null
                && virtualThreads.value() != null
                && "true".equalsIgnoreCase(virtualThreads.value().trim())) {
            return;
        }
        // A maximum no larger than the core size (8 by default) loses nothing.
        ApplicationProperty coreSize =
                findProperty(configurationAnalysis, "spring.task.execution.pool.core-size");
        Integer core = coreSize == null ? Integer.valueOf(8) : parseInteger(coreSize.value());
        Integer max = parseInteger(maxSize.value());
        if (core != null && max != null && max <= core) {
            return;
        }
        FindingFactory.Builder builder =
                FindingFactory.builder(
                                FindingRules.SPRING_TASK_EXECUTOR_MAX_POOL_IGNORED,
                                FindingConfidence.HIGH)
                        .shortMessage(
                                "spring.task.execution.pool.max-size is set without"
                                        + " queue-capacity — the pool never grows beyond its core"
                                        + " size.")
                        .whyBadPractice(
                                "A ThreadPoolExecutor only adds threads beyond the core size when"
                                    + " its queue is full. Spring Boot's default queue capacity is"
                                    + " unbounded, so the queue never fills and max-size is"
                                    + " ignored, as the property's own documentation notes.")
                        .possibleImpact(
                                "Under load, @Async and executor tasks queue up behind the core"
                                        + " threads instead of running in parallel, and the queue"
                                        + " can grow without limit.")
                        .recommendation(
                                "Set spring.task.execution.pool.queue-capacity to a bounded value"
                                        + " (and decide what happens when it is full), or drop"
                                        + " max-size and size the core pool instead.")
                        .evidence(
                                "spring.task.execution.pool.max-size="
                                        + maxSize.value()
                                        + " is set in "
                                        + maxSize.sourceFile()
                                        + " and no queue-capacity is configured.")
                        .limitations(
                                "An executor bean defined in code replaces the auto-configured one;"
                                        + " its own settings are not visible here.")
                        .target("spring.task.execution.pool.max-size");
        if (maxSize.sourceFile() != null) {
            builder.source(maxSize.sourceFile(), maxSize.line());
        } else {
            builder.location("Configuration");
        }
        findings.add(builder.build());
    }

    private static Integer parseInteger(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private void detectDataRestExposedRepositories(
            BuildInfo buildInfo,
            ConfigurationAnalysis configurationAnalysis,
            GradleModelAnalysis gradleModelAnalysis,
            List<Finding> findings) {
        boolean dataRestPresent =
                dependencyPresent(
                                buildInfo,
                                gradleModelAnalysis,
                                "org.springframework.boot",
                                "spring-boot-starter-data-rest")
                        || dependencyPresent(
                                buildInfo,
                                gradleModelAnalysis,
                                "org.springframework.data",
                                "spring-data-rest-core");
        if (!dataRestPresent) {
            return;
        }
        // detection-strategy=annotated only exports repositories explicitly opted in via
        // @RepositoryRestResource, so the blanket auto-exposure risk does not apply.
        ApplicationProperty strategy =
                findProperty(configurationAnalysis, "spring.data.rest.detection-strategy");
        if (strategy != null
                && strategy.value() != null
                && "annotated".equalsIgnoreCase(strategy.value().trim())) {
            return;
        }
        findings.add(
                FindingFactory.builder(
                                FindingRules.SPRING_DATA_REST_REPOSITORIES_EXPOSED,
                                FindingConfidence.MEDIUM)
                        .shortMessage(
                                "Spring Data REST is on the classpath; public repositories are"
                                        + " auto-exposed as HTTP CRUD endpoints.")
                        .whyBadPractice(
                                "With the default detection strategy, Spring Data REST maps full"
                                        + " CRUD HTTP endpoints at /<repository> for every public"
                                        + " Spring Data repository — including internal ones never"
                                        + " intended to be reachable over HTTP.")
                        .possibleImpact(
                                "Internal entities can be read and modified over HTTP without any"
                                    + " explicit controller, frequently unnoticed until a security"
                                    + " review or an incident.")
                        .recommendation(
                                "Set spring.data.rest.detection-strategy=annotated and export only"
                                    + " intended repositories with @RepositoryRestResource, or mark"
                                    + " sensitive repositories @RepositoryRestResource(exported ="
                                    + " false). Ensure Spring Security protects the base path.")
                        .evidence(
                                "spring-boot-starter-data-rest / spring-data-rest-core was detected"
                                        + " and spring.data.rest.detection-strategy is not set to"
                                        + " 'annotated'.")
                        .limitations(
                                "Static analysis flags the dependency's default behaviour;"
                                    + " per-repository @RepositoryRestResource(exported = false)"
                                    + " overrides are not individually verified.")
                        .target("spring-data-rest")
                        .build());
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
            ConfigurationAnalysis configurationAnalysis,
            BuildInfo buildInfo,
            List<Finding> findings) {
        if (configurationAnalysis == null || configurationAnalysis.properties() == null) {
            return;
        }
        // Before Spring Boot 2.4 spring.profiles was the supported document guard.
        if (isBootVersionBefore24(buildInfo)) {
            return;
        }
        String bootVersion = buildInfo == null ? null : buildInfo.springBootVersion();
        boolean legacyProcessing =
                SpringBootVersions.isBefore(bootVersion, 3, 0)
                        && configurationAnalysis.properties().stream()
                                .anyMatch(
                                        p ->
                                                p != null
                                                        && "spring.config.use-legacy-processing"
                                                                .equals(p.name())
                                                        && p.value() != null
                                                        && "true"
                                                                .equalsIgnoreCase(
                                                                        p.value().trim()));
        for (ApplicationProperty property : configurationAnalysis.properties()) {
            if (property == null || property.name() == null) {
                continue;
            }
            String baseName = property.name().replaceAll("\\[\\d+]$", "");
            if (!"spring.profiles".equals(baseName)) {
                continue;
            }
            FindingFactory.Builder builder =
                    FindingFactory.builder(
                            FindingRules.SPRING_PROFILES_PROPERTY_DEPRECATED,
                            FindingConfidence.HIGH);
            if (legacyProcessing) {
                builder.severity(FindingSeverity.INFO)
                        .shortMessage(
                                "spring.profiles in "
                                        + property.sourceFile()
                                        + " only works because"
                                        + " spring.config.use-legacy-processing=true — it fails on"
                                        + " Spring Boot 3.")
                        .whyBadPractice(
                                "spring.config.use-legacy-processing keeps the pre-2.4 profile"
                                    + " handling alive on Spring Boot 2.4-2.7. Spring Boot 3"
                                    + " removed the switch, and its config-data loader rejects"
                                    + " spring.profiles with InvalidConfigDataPropertyException.")
                        .possibleImpact(
                                "The application stops starting as soon as it is upgraded to"
                                        + " Spring Boot 3.");
            } else if (SpringBootVersions.isBefore(bootVersion, 3, 0)) {
                // Spring Boot 2.4-2.7 still honours the key and only logs a deprecation warning.
                builder.severity(FindingSeverity.WARNING)
                        .shortMessage(
                                "spring.profiles in "
                                        + property.sourceFile()
                                        + " is deprecated and makes Spring Boot 3 fail at"
                                        + " startup — use spring.config.activate.on-profile.")
                        .whyBadPractice(
                                "Spring Boot 2.4 replaced the spring.profiles document guard with"
                                    + " spring.config.activate.on-profile. Spring Boot 2.4-2.7"
                                    + " still apply the old key and log a deprecation warning;"
                                    + " Spring Boot 3 rejects it with"
                                    + " InvalidConfigDataPropertyException before the application"
                                    + " context starts.")
                        .possibleImpact(
                                "The application stops starting as soon as it is upgraded to"
                                        + " Spring Boot 3.");
            } else {
                builder.shortMessage(
                                "spring.profiles in "
                                        + property.sourceFile()
                                        + " makes Spring Boot fail at startup — use"
                                        + " spring.config.activate.on-profile.")
                        .whyBadPractice(
                                "Spring Boot 2.4 replaced the spring.profiles document guard with"
                                    + " spring.config.activate.on-profile. The config-data loader"
                                    + " rejects the old key with InvalidConfigDataPropertyException"
                                    + " (\"Property 'spring.profiles' ... is invalid and should be"
                                    + " replaced with 'spring.config.activate.on-profile'\") before"
                                    + " the application context starts.")
                        .possibleImpact(
                                "The application does not start while this file is on the"
                                        + " classpath.");
            }
            findings.add(
                    builder.recommendation(
                                    "Replace spring.profiles with spring.config.activate.on-profile"
                                            + " to guard a document, or use spring.profiles.group"
                                            + " to compose profiles.")
                            .evidence(property.name() + " found in " + property.sourceFile() + ".")
                            .limitations(
                                    "spring.profiles.active, spring.profiles.include and"
                                            + " spring.profiles.group are different keys and stay"
                                            + " valid in the default configuration.")
                            .source(property.sourceFile(), property.line())
                            .target("spring.profiles")
                            .build());
        }
    }

    private void detectProfilesActiveInProfileSpecificFile(
            ConfigurationAnalysis configurationAnalysis,
            BuildInfo buildInfo,
            List<Finding> findings) {
        if (configurationAnalysis == null || configurationAnalysis.properties() == null) {
            return;
        }
        // Only invalid since the Boot 2.4 config-data model; legacy processing (Boot <= 2.3, or
        // 2.4-2.7 with spring.config.use-legacy-processing=true) allowed the pattern.
        if (isBootVersionBefore24(buildInfo)
                || configurationAnalysis.properties().stream()
                        .anyMatch(
                                p ->
                                        p != null
                                                && "spring.config.use-legacy-processing"
                                                        .equals(p.name())
                                                && p.value() != null
                                                && "true".equalsIgnoreCase(p.value().trim()))) {
            return;
        }
        Set<String> reported = new LinkedHashSet<>();
        for (ApplicationProperty property : configurationAnalysis.properties()) {
            if (property == null || property.name() == null) {
                continue;
            }
            String name = property.name();
            // Also the list forms (spring.profiles.include[0]) a YAML sequence produces.
            String baseName = name.replaceAll("\\[\\d+]$", "");
            if (!PROFILE_SPECIFIC_INVALID_PROPERTIES.contains(baseName)) {
                continue;
            }
            // A test-classpath application.properties replaces the main one on the test classpath;
            // it is not a profile-specific file.
            if (isTestResource(property)
                    && "default".equals(normalizedProfile(property.profile()))) {
                continue;
            }
            // Only invalid when the property lives in a profile-specific file
            // (application-<profile>.*) or an on-profile-guarded document. "bootstrap" is the
            // pseudo-profile the file scanner assigns to Spring Cloud's bootstrap.yml, which is
            // not profile-specific — activating profiles there is the standard legacy-bootstrap
            // idiom.
            String profile = normalizedProfile(property.profile());
            if ("default".equals(profile)
                    || "bootstrap".equals(profile)
                    || !reported.add(property.sourceFile() + "|" + profile + "|" + baseName)) {
                continue;
            }
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_PROFILES_ACTIVE_IN_PROFILE_SPECIFIC_FILE,
                                    FindingConfidence.HIGH)
                            .shortMessage(
                                    name
                                            + " is set inside profile-specific configuration"
                                            + " (profile '"
                                            + profile
                                            + "') — Spring Boot fails at startup.")
                            .whyBadPractice(
                                    "Since the Spring Boot 2.4 config-data model (all of Boot 3.x"
                                        + " and 4.x), spring.profiles.active,"
                                        + " spring.profiles.include and spring.profiles.default are"
                                        + " invalid inside profile-specific files and"
                                        + " spring.config.activate.on-profile documents. Boot"
                                        + " throws InvalidConfigDataPropertyException while loading"
                                        + " the configuration.")
                            .possibleImpact(
                                    "The application fails to start every time the profile is"
                                            + " activated — a deterministic startup crash that"
                                            + " typically surfaces at deploy time.")
                            .recommendation(
                                    "Activate additional profiles from the default"
                                        + " application.properties/yaml, the SPRING_PROFILES_ACTIVE"
                                        + " environment variable, or compose profiles with"
                                        + " spring.profiles.group.<name> in the default document.")
                            .evidence(
                                    name
                                            + " was found in "
                                            + property.sourceFile()
                                            + " attributed to profile '"
                                            + profile
                                            + "'.")
                            .source(property.sourceFile(), property.line())
                            .target(name)
                            .build());
        }
    }

    private void detectSqlInitAlwaysProd(
            ConfigurationAnalysis configurationAnalysis, List<Finding> findings) {
        if (configurationAnalysis == null || configurationAnalysis.properties() == null) {
            return;
        }
        for (ApplicationProperty property : configurationAnalysis.properties()) {
            if (property == null || property.name() == null || property.value() == null) {
                continue;
            }
            if (!"spring.sql.init.mode".equals(property.name())
                    || !"always".equalsIgnoreCase(property.value().trim())) {
                continue;
            }
            String profile = normalizedProfile(property.profile());
            if (!isProdLikeProfile(profile)) {
                continue;
            }
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_SQL_INIT_ALWAYS_PROD,
                                    FindingConfidence.HIGH)
                            .shortMessage(
                                    "spring.sql.init.mode=always in production-oriented profile '"
                                            + profile
                                            + "' re-runs SQL init scripts on every startup.")
                            .whyBadPractice(
                                    "With mode=always, Spring Boot executes schema.sql/data.sql (or"
                                        + " the configured spring.sql.init.*-locations) against the"
                                        + " real datasource on every application startup — the"
                                        + " default 'embedded' runs them only for in-memory"
                                        + " databases. Init scripts are rarely idempotent.")
                            .possibleImpact(
                                    "Duplicate or overwritten rows on every restart, or a startup"
                                            + " abort when a script fails against existing data"
                                            + " (continue-on-error defaults to false) — a"
                                            + " data-corruption class risk in production.")
                            .recommendation(
                                    "Use Flyway or Liquibase for production schema/data changes."
                                            + " If SQL init scripts are needed for local bootstrap,"
                                            + " keep mode=always out of production profiles or make"
                                            + " every script strictly idempotent.")
                            .evidence(
                                    "spring.sql.init.mode=always was found in "
                                            + property.sourceFile()
                                            + " (profile: "
                                            + profile
                                            + ").")
                            .limitations(
                                    "Static analysis cannot verify whether the profile's datasource"
                                            + " is a disposable/embedded database or whether the"
                                            + " scripts are idempotent.")
                            .source(property.sourceFile(), property.line())
                            .target("spring.sql.init.mode")
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
                String value = property.value().trim();
                // Spring compares havingValue case-insensitively, and setting a boolean switch to
                // its other value is how a conditional bean is turned off on purpose.
                boolean matches = expectedValues.stream().anyMatch(value::equalsIgnoreCase);
                boolean booleanToggle =
                        isBooleanLiteral(value)
                                && expectedValues.stream().allMatch(this::isBooleanLiteral);
                if (!matches && !booleanToggle) {
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

    private boolean isBooleanLiteral(String value) {
        return "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value);
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
        List<Path> migrationRoots = flywayMigrationRoots(repositoryRoot, configurationAnalysis);
        if (flywayLocationsConfigured(configurationAnalysis) && migrationRoots.isEmpty()) {
            // spring.flyway.locations points only at filesystem/absolute paths outside the cloned
            // repository (or an unresolvable placeholder), so the migration files cannot be seen
            // statically — don't emit a likely-false "missing migrations" finding.
            return;
        }
        String scannedLocations = describeRoots(repositoryRoot, migrationRoots);
        List<Path> migrations = migrationFiles(migrationRoots);
        if (!customFlywayNaming(configurationAnalysis)) {
            detectFlywayMigrationNamesIgnored(repositoryRoot, migrationRoots, findings);
        }
        if (migrations.isEmpty()) {
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_FLYWAY_MISSING_MIGRATIONS,
                                    FindingConfidence.MEDIUM)
                            .shortMessage(
                                    "Flyway appears to be enabled, but no migration files were"
                                            + " found in "
                                            + scannedLocations
                                            + ".")
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
                                            + " configuration or dependencies were detected, but no"
                                            + " versioned migration files (V…__….sql) were found in"
                                            + " "
                                            + scannedLocations
                                            + ".")
                            .limitations(
                                    "Static analysis resolves classpath: and filesystem: Flyway"
                                            + " locations within the repository; locations outside"
                                            + " the cloned project cannot be inspected.")
                            .location(firstRootRelative(repositoryRoot, migrationRoots))
                            .target("flyway migrations")
                            .build());
        } else {
            Map<String, List<Path>> byVersion =
                    duplicateVersions(
                            migrations, vendorSplitRoots(repositoryRoot, configurationAnalysis));
            byVersion.forEach(
                    (version, paths) -> {
                        if (paths.size() > 1) {
                            findings.add(
                                    FindingFactory.builder(
                                                    FindingRules.SPRING_FLYWAY_DUPLICATE_VERSION,
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
                                                            + " outside the repository. With a"
                                                            + " {vendor} location, versions are"
                                                            + " compared per database folder.")
                                            .source(
                                                    repositoryRoot
                                                            .relativize(paths.get(1))
                                                            .toString()
                                                            .replace('\\', '/'),
                                                    1)
                                            .target("Flyway migration version " + version)
                                            .build());
                        }
                    });
        }
    }

    private void detectOpenInViewNotDisabled(
            ConfigurationAnalysis configurationAnalysis,
            BuildInfo buildInfo,
            List<Finding> findings) {
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
        // Open-session-in-view only registers in servlet web applications; batch/CLI apps with a
        // datasource never get the interceptor, so the absent-property default is harmless there.
        boolean servletWebApp =
                buildInfo != null
                        && buildInfo.dependencies() != null
                        && buildInfo.dependencies().stream().anyMatch(this::isServletWebDependency);
        if (!servletWebApp) {
            return;
        }
        boolean openInViewExplicit =
                configurationAnalysis.properties().stream()
                        .anyMatch(p -> "spring.jpa.open-in-view".equals(p.name()));
        if (!openInViewExplicit) {
            // Not-set is the Spring Boot default in every JPA web project, so this variant is
            // informational; the explicit =true case stays at the rule's WARNING severity.
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_JPA_OPEN_IN_VIEW, FindingConfidence.MEDIUM)
                            .severity(
                                    com.robbanhoglund.springbootanalyzer.analyzer.model
                                            .FindingSeverity.INFO)
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

    private boolean isServletWebDependency(String dependency) {
        if (dependency == null) {
            return false;
        }
        String normalized = dependency.toLowerCase(Locale.ROOT);
        return (normalized.contains("spring-boot-starter-web")
                        && !normalized.contains("spring-boot-starter-webflux"))
                || normalized.contains("spring-webmvc");
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
        return sensitivePropertyValueRedactor.isSensitive(propertyName);
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

    /**
     * Returns {@code true} when the resolved Spring Boot version is known to predate the 2.4
     * config-data model. Unknown versions return {@code false} so version-gated rules still run.
     */
    private boolean isBootVersionBefore24(BuildInfo buildInfo) {
        String version = buildInfo == null ? null : buildInfo.springBootVersion();
        if (version == null || version.isBlank()) {
            return false;
        }
        String[] parts = version.split("\\.");
        try {
            int major = Integer.parseInt(parts[0].replaceAll("[^0-9].*$", ""));
            int minor =
                    parts.length > 1 ? Integer.parseInt(parts[1].replaceAll("[^0-9].*$", "")) : 0;
            return major < 2 || (major == 2 && minor < 4);
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private void detectActuatorShowValuesAlways(
            ConfigurationAnalysis configurationAnalysis, List<Finding> findings) {
        if (configurationAnalysis == null || configurationAnalysis.properties() == null) {
            return;
        }
        for (ApplicationProperty property : configurationAnalysis.properties()) {
            if (property == null || property.name() == null || property.value() == null) {
                continue;
            }
            String name = property.name();
            if (!"management.endpoint.env.show-values".equals(name)
                    && !"management.endpoint.configprops.show-values".equals(name)) {
                continue;
            }
            if (!"always".equalsIgnoreCase(property.value().trim())) {
                continue;
            }
            String endpoint = name.contains(".env.") ? "/actuator/env" : "/actuator/configprops";
            findings.add(
                    FindingFactory.builder(
                                    FindingRules.SPRING_ACTUATOR_SHOW_VALUES_ALWAYS,
                                    FindingConfidence.HIGH)
                            .shortMessage(
                                    name
                                            + "=always unmasks every secret value on "
                                            + endpoint
                                            + ".")
                            .whyBadPractice(
                                    "Spring Boot 3 sanitizes property values on the env and"
                                        + " configprops endpoints by default, showing '******' for"
                                        + " anything that looks sensitive. Setting show-values to"
                                        + " always disables that masking entirely, so passwords,"
                                        + " tokens, and connection strings are returned verbatim.")
                            .possibleImpact(
                                    "Anyone who can reach the endpoint reads the application's"
                                        + " secrets in plain text. Combined with wildcard actuator"
                                        + " exposure or a missing security filter chain, this is a"
                                        + " direct credential leak.")
                            .recommendation(
                                    "Use when-authorized (the safe alternative) so values are only"
                                        + " revealed to authorized callers, or leave the property"
                                        + " unset to keep the default masking.")
                            .evidence(
                                    name
                                            + "=always was found in "
                                            + property.sourceFile()
                                            + " (profile: "
                                            + normalizedProfile(property.profile())
                                            + ").")
                            .limitations(
                                    "Static analysis cannot see whether the endpoint is reachable"
                                            + " or protected by a security filter chain.")
                            .source(property.sourceFile(), property.line())
                            .target(name)
                            .build());
        }
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

    /** A file that starts like a versioned, undo or repeatable migration. */
    private static final Pattern FLYWAY_ATTEMPTED_MIGRATION =
            Pattern.compile("^[VvUuRr](?:\\d|_).*");

    /** Flyway's default naming: V1__x.sql, U1__x.sql, R__x.sql (prefix case-sensitive). */
    private static final Pattern FLYWAY_VALID_NAME =
            Pattern.compile("^(?:[VU]\\d+(?:[._]\\d+)*__.*|R__.+)\\.(?i:sql)$");

    private static final Pattern FLYWAY_SINGLE_UNDERSCORE =
            Pattern.compile("^([VUR])(\\d+(?:[._]\\d+)*)?_(?!_)(.+)$");

    private boolean customFlywayNaming(ConfigurationAnalysis configurationAnalysis) {
        return hasProperty(configurationAnalysis, "spring.flyway.sql-migration-prefix")
                || hasProperty(
                        configurationAnalysis, "spring.flyway.repeatable-sql-migration-prefix")
                || hasProperty(configurationAnalysis, "spring.flyway.sql-migration-separator")
                || hasProperty(configurationAnalysis, "spring.flyway.sql-migration-suffixes");
    }

    private void detectFlywayMigrationNamesIgnored(
            Path repositoryRoot, List<Path> migrationRoots, List<Finding> findings) {
        Set<Path> reported = new java.util.HashSet<>();
        for (Path root : migrationRoots) {
            if (Files.notExists(root)) {
                continue;
            }
            List<Path> files;
            try (Stream<Path> stream = Files.walk(root)) {
                files = stream.filter(Files::isRegularFile).sorted().toList();
            } catch (IOException exception) {
                continue;
            }
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (!name.toLowerCase(Locale.ROOT).endsWith(".sql")
                        || !FLYWAY_ATTEMPTED_MIGRATION.matcher(name).matches()
                        || FLYWAY_VALID_NAME.matcher(name).matches()
                        || !reported.add(file)) {
                    continue;
                }
                String relative = repositoryRoot.relativize(file).toString().replace('\\', '/');
                findings.add(
                        FindingFactory.builder(
                                        FindingRules.SPRING_FLYWAY_MIGRATION_NAME_IGNORED,
                                        FindingConfidence.HIGH)
                                .shortMessage(
                                        name
                                                + " does not follow Flyway's naming convention —"
                                                + " Flyway skips it and the migration never runs.")
                                .whyBadPractice(
                                        "Flyway only recognizes V<version>__<description>.sql"
                                            + " (versioned) and R__<description>.sql (repeatable)"
                                            + " files: the prefix is case-sensitive and the version"
                                            + " is followed by two underscores. Other files in a"
                                            + " migration location are skipped without an error,"
                                            + " because validateMigrationNaming defaults to false.")
                                .possibleImpact(
                                        "The schema change in this file is never applied; code"
                                                + " that relies on it fails at runtime, or an"
                                                + " environment silently lags behind.")
                                .recommendation(
                                        "Rename the file to "
                                                + suggestedMigrationName(name)
                                                + ", and consider"
                                                + " spring.flyway.validate-migration-naming=true so"
                                                + " a bad name fails fast.")
                                .evidence(
                                        relative
                                                + " is in a Flyway migration location but matches"
                                                + " neither V<version>__<description>.sql nor"
                                                + " R__<description>.sql.")
                                .limitations(
                                        "Assumes Flyway's default prefixes and separator; custom"
                                                + " spring.flyway.sql-migration-* settings disable"
                                                + " this check.")
                                .source(relative, 1)
                                .target(name)
                                .build());
            }
        }
    }

    private static String suggestedMigrationName(String name) {
        String fixed = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        Matcher singleUnderscore = FLYWAY_SINGLE_UNDERSCORE.matcher(fixed);
        if (singleUnderscore.matches()) {
            String version = singleUnderscore.group(2) == null ? "" : singleUnderscore.group(2);
            fixed = singleUnderscore.group(1) + version + "__" + singleUnderscore.group(3);
        }
        return fixed;
    }

    /** Roots configured with a {vendor} placeholder: each subfolder is one database's set. */
    private Set<Path> vendorSplitRoots(
            Path repositoryRoot, ConfigurationAnalysis configurationAnalysis) {
        String configured = flywayLocationsValue(configurationAnalysis);
        if (configured == null) {
            return Set.of();
        }
        Path boundary = repositoryRoot.normalize();
        Set<Path> roots = new LinkedHashSet<>();
        for (String rawLocation : configured.split(",")) {
            if (!rawLocation.contains("{vendor}")) {
                continue;
            }
            Path resolved = resolveFlywayLocation(repositoryRoot, boundary, rawLocation);
            if (resolved != null) {
                roots.add(resolved);
            }
        }
        return roots;
    }

    /**
     * Migration versions that occur more than once in a set Flyway loads together. With a
     * {vendor} location only one database folder is active at a time, so each folder is compared
     * with the shared locations but not with the other databases' folders.
     */
    private Map<String, List<Path>> duplicateVersions(
            List<Path> migrations, Set<Path> vendorRoots) {
        Map<String, List<Path>> byVendor = new LinkedHashMap<>();
        for (Path migration : migrations) {
            String vendor = "";
            for (Path root : vendorRoots) {
                if (migration.startsWith(root) && root.relativize(migration).getNameCount() > 1) {
                    vendor = root.relativize(migration).getName(0).toString();
                    break;
                }
            }
            byVendor.computeIfAbsent(vendor, key -> new ArrayList<>()).add(migration);
        }
        List<Path> shared = byVendor.getOrDefault("", List.of());
        List<List<Path>> loadedTogether = new ArrayList<>();
        if (byVendor.keySet().stream().allMatch(String::isEmpty)) {
            loadedTogether.add(shared);
        } else {
            byVendor.forEach(
                    (vendor, files) -> {
                        if (!vendor.isEmpty()) {
                            List<Path> set = new ArrayList<>(shared);
                            set.addAll(files);
                            loadedTogether.add(set);
                        }
                    });
        }
        Map<String, List<Path>> duplicates = new LinkedHashMap<>();
        for (List<Path> set : loadedTogether) {
            Map<String, List<Path>> byVersion = new LinkedHashMap<>();
            for (Path migration : set) {
                String version = migrationVersion(migration);
                if (version != null) {
                    byVersion.computeIfAbsent(version, key -> new ArrayList<>()).add(migration);
                }
            }
            byVersion.forEach(
                    (version, paths) -> {
                        if (paths.size() > 1) {
                            List<Path> merged =
                                    duplicates.computeIfAbsent(version, key -> new ArrayList<>());
                            paths.stream()
                                    .filter(path -> !merged.contains(path))
                                    .forEach(merged::add);
                        }
                    });
        }
        return duplicates;
    }

    private List<Path> migrationFiles(List<Path> migrationRoots) {
        List<Path> migrations = new ArrayList<>();
        for (Path migrationRoot : migrationRoots) {
            if (Files.notExists(migrationRoot)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(migrationRoot)) {
                files.filter(Files::isRegularFile)
                        .filter(
                                path ->
                                        FLYWAY_MIGRATION_PATTERN
                                                .matcher(path.getFileName().toString())
                                                .matches())
                        .forEach(migrations::add);
            } catch (IOException exception) {
                // Best-effort — skip an unreadable migration tree.
            }
        }
        return migrations.stream().distinct().sorted().toList();
    }

    /** Returns the configured {@code spring.flyway.locations} value, or null when unset/blank. */
    private String flywayLocationsValue(ConfigurationAnalysis configurationAnalysis) {
        ApplicationProperty property =
                findProperty(configurationAnalysis, "spring.flyway.locations");
        if (property == null || property.value() == null) {
            return null;
        }
        String value = property.value().trim();
        // An unresolvable placeholder (e.g. ${FLYWAY_LOCATIONS}) cannot be mapped statically.
        if (value.isEmpty() || value.contains("${")) {
            return null;
        }
        return value;
    }

    private boolean flywayLocationsConfigured(ConfigurationAnalysis configurationAnalysis) {
        return flywayLocationsValue(configurationAnalysis) != null;
    }

    /**
     * Resolves the directories Flyway would scan for migrations. When {@code spring.flyway.locations}
     * is not set, falls back to the Spring Boot default ({@code classpath:db/migration} →
     * {@code src/main/resources/db/migration}). Configured {@code classpath:} and {@code filesystem:}
     * locations are mapped to repository paths; locations resolving outside the repository (e.g.
     * absolute filesystem paths) are dropped because they cannot be inspected statically.
     */
    private static final String LIQUIBASE_DEFAULT_CHANGELOG =
            "db/changelog/db.changelog-master.yaml";

    private void detectLiquibaseMissingChangelog(
            Path repositoryRoot,
            BuildInfo buildInfo,
            ConfigurationAnalysis configurationAnalysis,
            GradleModelAnalysis gradleModelAnalysis,
            List<Finding> findings) {
        boolean liquibasePresent =
                dependencyPresent(
                        buildInfo, gradleModelAnalysis, "org.liquibase", "liquibase-core");
        if (!liquibasePresent) {
            return;
        }
        if (configurationAnalysis == null || configurationAnalysis.properties() == null) {
            return;
        }
        // Only a default-profile "enabled=false" disables Liquibase everywhere; the common
        // disable-in-tests idiom (application-test.yaml) must not suppress the production defect.
        boolean disabledByDefault =
                configurationAnalysis.properties().stream()
                        .filter(
                                p ->
                                        p != null
                                                && "spring.liquibase.enabled".equals(p.name())
                                                && "default".equals(normalizedProfile(p.profile())))
                        .anyMatch(
                                p ->
                                        p.value() != null
                                                && "false".equalsIgnoreCase(p.value().trim()));
        if (disabledByDefault) {
            return;
        }
        // Spring's relaxed binding accepts change-log, changeLog and changelog; the normalizer
        // lowercases names, so match both the kebab and the collapsed forms.
        ApplicationProperty changeLogProperty =
                configurationAnalysis.properties().stream()
                        .filter(
                                p ->
                                        p != null
                                                && ("spring.liquibase.change-log".equals(p.name())
                                                        || "spring.liquibase.changelog"
                                                                .equals(p.name())))
                        .filter(p -> "default".equals(normalizedProfile(p.profile())))
                        .findFirst()
                        .orElseGet(
                                () ->
                                        configurationAnalysis.properties().stream()
                                                .filter(
                                                        p ->
                                                                p != null
                                                                        && ("spring.liquibase.change-log"
                                                                                        .equals(
                                                                                                p
                                                                                                        .name())
                                                                                || "spring.liquibase.changelog"
                                                                                        .equals(
                                                                                                p
                                                                                                        .name())))
                                                .findFirst()
                                                .orElse(null));
        String configured =
                changeLogProperty == null || changeLogProperty.value() == null
                        ? null
                        : changeLogProperty.value().trim();
        String location = configured != null ? configured : LIQUIBASE_DEFAULT_CHANGELOG;
        // Values static analysis cannot judge: placeholders and non-classpath resource URLs.
        if (location.contains("${")) {
            return;
        }
        String lower = location.toLowerCase(Locale.ROOT);
        if (lower.startsWith("file:")
                || lower.startsWith("optional:")
                || lower.startsWith("http")) {
            return;
        }
        String relative =
                lower.startsWith("classpath:")
                        ? location.substring("classpath:".length())
                        : location;
        relative = relative.replace('\\', '/');
        while (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        if (relative.isEmpty() || isAbsolutePath(relative)) {
            return;
        }
        Path resolved = repositoryRoot.resolve("src/main/resources").resolve(relative).normalize();
        if (!resolved.startsWith(repositoryRoot.normalize()) || Files.exists(resolved)) {
            return;
        }
        String describedLocation = "src/main/resources/" + relative;
        findings.add(
                FindingFactory.builder(
                                FindingRules.SPRING_LIQUIBASE_MISSING_CHANGELOG,
                                FindingConfidence.MEDIUM)
                        .shortMessage(
                                "Liquibase is on the classpath but no changelog was found at "
                                        + describedLocation
                                        + ".")
                        .whyBadPractice(
                                "With liquibase-core on the classpath and a DataSource present,"
                                    + " Spring Boot auto-configures Liquibase against the changelog"
                                    + " at spring.liquibase.change-log (default"
                                    + " classpath:/db/changelog/db.changelog-master.yaml). When the"
                                    + " file does not exist, startup fails with 'Cannot find"
                                    + " changelog location'.")
                        .possibleImpact(
                                "The application fails to start in every environment — typically"
                                        + " discovered only at deploy time.")
                        .recommendation(
                                configured == null
                                        ? "Create db/changelog/db.changelog-master.yaml under"
                                                + " src/main/resources, or point"
                                                + " spring.liquibase.change-log at the actual"
                                                + " changelog file."
                                        : "Create the changelog at the configured location, or"
                                                + " correct spring.liquibase.change-log to point at"
                                                + " the actual file.")
                        .evidence(
                                "liquibase-core was detected in the build dependencies, but no file"
                                        + " exists at "
                                        + describedLocation
                                        + (configured == null
                                                ? " (the Spring Boot default location)."
                                                : " (from spring.liquibase.change-log)."))
                        .limitations(
                                "Multi-module builds that package resources elsewhere, or"
                                        + " changelogs provided by another module/jar, are not"
                                        + " visible to this check.")
                        .location(describedLocation)
                        .target("liquibase changelog")
                        .build());
    }

    private List<Path> flywayMigrationRoots(
            Path repositoryRoot, ConfigurationAnalysis configurationAnalysis) {
        String configured = flywayLocationsValue(configurationAnalysis);
        if (configured == null) {
            return List.of(repositoryRoot.resolve("src/main/resources/db/migration").normalize());
        }
        Path repoBoundary = repositoryRoot.normalize();
        List<Path> roots = new ArrayList<>();
        for (String rawLocation : configured.split(",")) {
            Path resolved = resolveFlywayLocation(repositoryRoot, repoBoundary, rawLocation);
            if (resolved != null && !roots.contains(resolved)) {
                roots.add(resolved);
            }
        }
        return roots;
    }

    private Path resolveFlywayLocation(Path repositoryRoot, Path repoBoundary, String rawLocation) {
        String location = rawLocation.trim();
        if (location.isEmpty()) {
            return null;
        }
        String lower = location.toLowerCase(Locale.ROOT);
        String relative;
        Path base;
        if (lower.startsWith("filesystem:")) {
            relative = location.substring("filesystem:".length()).trim();
            // An absolute filesystem path points outside the cloned repo and cannot be inspected.
            if (isAbsolutePath(relative)) {
                return null;
            }
            // filesystem: locations are otherwise resolved relative to the project root.
            base = repositoryRoot;
        } else if (lower.startsWith("classpath:")) {
            relative = location.substring("classpath:".length()).trim();
            base = repositoryRoot.resolve("src/main/resources");
        } else {
            // No prefix — Flyway treats it as a classpath location.
            relative = location;
            base = repositoryRoot.resolve("src/main/resources");
        }
        relative = relative.replace('\\', '/');
        while (relative.startsWith("./")) {
            relative = relative.substring(2);
        }
        while (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        // Drop a {vendor}/{...} placeholder segment; Files.walk recurses into any subdirectory.
        int placeholder = relative.indexOf('{');
        if (placeholder >= 0) {
            relative = relative.substring(0, placeholder);
        }
        while (relative.endsWith("/")) {
            relative = relative.substring(0, relative.length() - 1);
        }
        if (relative.isEmpty()) {
            return null;
        }
        Path resolved = base.resolve(relative).normalize();
        if (!resolved.startsWith(repoBoundary)) {
            // Resolved outside the cloned repository (e.g. via "..") — cannot scan.
            return null;
        }
        return resolved;
    }

    private static boolean isAbsolutePath(String path) {
        return path.startsWith("/") || path.startsWith("\\") || path.matches("^[A-Za-z]:[\\\\/].*");
    }

    private String describeRoots(Path repositoryRoot, List<Path> roots) {
        if (roots.isEmpty()) {
            return "the configured Flyway locations";
        }
        Path base = repositoryRoot.normalize();
        return roots.stream()
                .map(root -> relativizeWithinRepo(base, root))
                .collect(Collectors.joining(", "));
    }

    private String firstRootRelative(Path repositoryRoot, List<Path> roots) {
        if (roots.isEmpty()) {
            return "db/migration";
        }
        return relativizeWithinRepo(repositoryRoot.normalize(), roots.get(0));
    }

    private static String relativizeWithinRepo(Path base, Path target) {
        try {
            return base.relativize(target).toString().replace('\\', '/');
        } catch (IllegalArgumentException exception) {
            return target.toString().replace('\\', '/');
        }
    }

    private String migrationVersion(Path path) {
        Matcher matcher = FLYWAY_MIGRATION_PATTERN.matcher(path.getFileName().toString());
        return matcher.matches() ? matcher.group("version") : null;
    }

    private void detectSecurityAutoconfigureExcluded(
            ConfigurationAnalysis configurationAnalysis,
            JavaSources javaSources,
            List<Finding> findings) {
        if (configurationAnalysis == null || configurationAnalysis.properties() == null) {
            return;
        }
        // An application that enables web security itself still builds its chain when the
        // default-chain auto-configuration is excluded.
        boolean ownWebSecurity =
                javaSources != null
                        && javaSources.files().stream()
                                .anyMatch(
                                        file ->
                                                file.content().contains("@EnableWebSecurity")
                                                        || file.content()
                                                                .contains(
                                                                        "@EnableWebFluxSecurity"));
        for (ApplicationProperty property : configurationAnalysis.properties()) {
            if (property.name() == null
                    || property.value() == null
                    || !"spring.autoconfigure.exclude"
                            .equals(property.name().replaceAll("\\[\\d+]$", ""))) {
                continue;
            }
            List<String> excluded =
                    java.util.Arrays.stream(property.value().split("[,\\s]+"))
                            .map(String::trim)
                            .filter(value -> !value.isEmpty())
                            .toList();
            String filterExclusion =
                    excluded.stream()
                            .filter(
                                    name ->
                                            simpleClassName(name)
                                                    .equals(SECURITY_FILTER_AUTO_CONFIGURATION))
                            .findFirst()
                            .orElse(null);
            String chainExclusion =
                    excluded.stream()
                            .filter(this::createsDefaultSecurityChain)
                            .findFirst()
                            .orElse(null);
            if (filterExclusion == null && (chainExclusion == null || ownWebSecurity)) {
                continue;
            }
            String removed =
                    simpleClassName(filterExclusion != null ? filterExclusion : chainExclusion);
            String profile = normalizedProfile(property.profile());
            String where =
                    profile.equals("default")
                            ? " in the default profile"
                            : " in the \"" + profile + "\" profile";
            FindingFactory.Builder builder =
                    FindingFactory.builder(
                                    FindingRules.SPRING_SECURITY_AUTOCONFIGURE_EXCLUDED,
                                    FindingConfidence.HIGH)
                            .shortMessage(
                                    "spring.autoconfigure.exclude removes "
                                            + removed
                                            + " — requests are not secured"
                                            + where
                                            + ".")
                            .whyBadPractice(
                                    filterExclusion != null
                                            ? "SecurityFilterAutoConfiguration registers Spring"
                                                    + " Security's filter with the servlet"
                                                    + " container. Without it no"
                                                    + " SecurityFilterChain — not even the"
                                                    + " application's own — sees a request."
                                            : removed
                                                    + " creates the default security filter chain"
                                                    + " and enables web security. The project"
                                                    + " declares no @EnableWebSecurity"
                                                    + " configuration of its own, so no chain is"
                                                    + " built.")
                            .possibleImpact(
                                    "Every endpoint is reachable without authentication in this"
                                            + " profile, and tests running under it do not"
                                            + " exercise the real security configuration.")
                            .recommendation(
                                    "Keep the auto-configuration and adjust security with your own"
                                        + " SecurityFilterChain; in tests prefer @WithMockUser or a"
                                        + " test security configuration over excluding it.")
                            .evidence(
                                    "spring.autoconfigure.exclude contains "
                                            + (filterExclusion != null
                                                    ? filterExclusion
                                                    : chainExclusion)
                                            + " in profile \""
                                            + profile
                                            + "\".")
                            .limitations(
                                    "Excluding UserDetailsServiceAutoConfiguration or"
                                        + " ManagementWebSecurityAutoConfiguration is not reported:"
                                        + " it removes the generated default user or the actuator"
                                        + " defaults, not the filter chain.")
                            .target(property.name());
            if (property.sourceFile() != null) {
                builder.source(property.sourceFile(), property.line());
            } else {
                builder.location("Configuration");
            }
            findings.add(builder.build());
        }
    }

    /**
     * Whether an excluded auto-configuration builds the default web security chain. Spring Boot 3
     * does that in {@code ...autoconfigure.security.servlet.SecurityAutoConfiguration}; Spring Boot 4
     * moved it to {@code ServletWebSecurityAutoConfiguration}, and its
     * {@code org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration} only wires
     * core beans.
     */
    private boolean createsDefaultSecurityChain(String className) {
        String simpleName = simpleClassName(className);
        if (!SECURITY_CHAIN_AUTO_CONFIGURATIONS.contains(simpleName)) {
            return false;
        }
        if (simpleName.equals("SecurityAutoConfiguration")
                || simpleName.equals("ReactiveSecurityAutoConfiguration")) {
            return !className.contains(".") || className.contains(".autoconfigure.security.");
        }
        return true;
    }

    private static String simpleClassName(String className) {
        int dot = className.lastIndexOf('.');
        return dot >= 0 ? className.substring(dot + 1) : className;
    }

    /** A property read from a file under src/test — configuration only tests ever load. */
    private static boolean isTestResource(ApplicationProperty property) {
        String sourceFile = property.sourceFile();
        return sourceFile != null
                && (sourceFile.startsWith("src/test/") || sourceFile.contains("/src/test/"));
    }

    /** What the test sources reveal about how Spring contexts get their datasource. */
    private record TestSourceSignals(boolean loadsSpringContext, boolean suppliesDatasource) {

        private static final List<String> DATASOURCE_MARKERS =
                List.of(
                        "@ServiceConnection",
                        "@DynamicPropertySource",
                        "jdbc:tc:",
                        "spring.datasource.url",
                        "@AutoConfigureTestDatabase",
                        "@Testcontainers");

        static TestSourceSignals scan(Path repositoryRoot) {
            Path testRoot = repositoryRoot == null ? null : repositoryRoot.resolve("src/test/java");
            if (testRoot == null || Files.notExists(testRoot)) {
                return new TestSourceSignals(false, false);
            }
            boolean loadsContext = false;
            boolean suppliesDatasource = false;
            try (Stream<Path> files = Files.walk(testRoot)) {
                for (Path file :
                        files.filter(Files::isRegularFile)
                                .filter(path -> path.toString().endsWith(".java"))
                                .toList()) {
                    String content;
                    try {
                        content = Files.readString(file, StandardCharsets.UTF_8);
                    } catch (IOException exception) {
                        continue;
                    }
                    loadsContext |= content.contains("@SpringBootTest");
                    suppliesDatasource |= DATASOURCE_MARKERS.stream().anyMatch(content::contains);
                }
            } catch (IOException exception) {
                return new TestSourceSignals(false, false);
            }
            return new TestSourceSignals(loadsContext, suppliesDatasource);
        }
    }

    private void detectDatasourceNoTestOverride(
            Path repositoryRoot,
            ConfigurationAnalysis configurationAnalysis,
            List<Finding> findings) {
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
                        .filter(p -> !isTestResource(p))
                        .filter(p -> !isEmbeddedDatabase(p.value()))
                        .findFirst()
                        .orElse(null);
        if (defaultEntry == null) {
            return;
        }
        // A test profile file, or a test-classpath application.properties that replaces the main
        // one, both override the datasource for tests.
        boolean hasTestOverride =
                datasourceUrls.stream()
                        .anyMatch(
                                p ->
                                        isTestProfile(normalizedProfile(p.profile()))
                                                || isTestResource(p));
        if (hasTestOverride) {
            return;
        }
        // Only a @SpringBootTest without Testcontainers or a replaced datasource reaches the
        // configured database; without one nothing connects to it.
        TestSourceSignals tests = TestSourceSignals.scan(repositoryRoot);
        if (!tests.loadsSpringContext() || tests.suppliesDatasource()) {
            return;
        }
        FindingFactory.Builder builder =
                FindingFactory.builder(
                                FindingRules.SPRING_DATASOURCE_NO_TEST_OVERRIDE,
                                FindingConfidence.MEDIUM)
                        .shortMessage(
                                "spring.datasource.url points to a real database in the default"
                                        + " profile, and the @SpringBootTest tests do not"
                                        + " override it")
                        .whyBadPractice(
                                "@SpringBootTest loads the default configuration. Without a test"
                                        + " profile, a test-classpath application.properties or"
                                        + " Testcontainers, integration tests connect to the"
                                        + " configured database.")
                        .possibleImpact(
                                "Integration tests may read from or write to shared or"
                                        + " production data, causing data corruption, flaky tests,"
                                        + " and misleading test results.")
                        .recommendation(
                                "Point tests at a disposable database: a @ServiceConnection"
                                        + " Testcontainers container, or spring.datasource.url in"
                                        + " src/test/resources/application-test.properties.")
                        .evidence(
                                "Default datasource URL: "
                                        + renderedValue(defaultEntry)
                                        + ". The test sources start Spring contexts, but no test"
                                        + " datasource was detected.")
                        .limitations(
                                "Test datasources supplied through environment variables or CI"
                                        + " configuration are not visible.")
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
            if (isTestOrLocalProfile(profile) || isTestResource(property)) {
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

    /** The Hibernate major each Spring Boot major manages through its BOM. */
    private static final Map<Integer, Integer> REQUIRED_HIBERNATE_MAJOR_BY_BOOT_MAJOR =
            Map.of(3, 6, 4, 7);

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
        // Spring Boot 3 manages Hibernate 6 and Spring Boot 4 manages Hibernate 7; an explicitly
        // pinned older major breaks the JPA integration. Unknown future lines are not guessed.
        Integer requiredHibernateMajor =
                REQUIRED_HIBERNATE_MAJOR_BY_BOOT_MAJOR.get(SpringBootVersions.major(bootVersion));
        if (requiredHibernateMajor == null) {
            return;
        }
        String hibernateVersion =
                resolvedVersion("org.hibernate", "hibernate-core", gradleModelAnalysis);
        if (hibernateVersion == null) {
            hibernateVersion =
                    resolvedVersion("org.hibernate.orm", "hibernate-core", gradleModelAnalysis);
        }
        if (hibernateVersion == null) {
            return;
        }
        int hibernateMajor = parseMajorVersion(hibernateVersion);
        if (hibernateMajor >= requiredHibernateMajor || hibernateMajor < 0) {
            return;
        }
        int bootMajor = SpringBootVersions.major(bootVersion);
        findings.add(
                FindingFactory.builder(
                                FindingRules.SPRING_HIBERNATE_VERSION_MISMATCH,
                                FindingConfidence.HIGH)
                        .shortMessage(
                                "Hibernate "
                                        + hibernateVersion
                                        + " is not compatible with Spring Boot "
                                        + bootVersion
                                        + " — Spring Boot "
                                        + bootMajor
                                        + ".x requires Hibernate "
                                        + requiredHibernateMajor
                                        + ".x")
                        .whyBadPractice(
                                "Spring Boot "
                                        + bootMajor
                                        + ".x builds its JPA integration on Hibernate "
                                        + requiredHibernateMajor
                                        + ".x and relies on its API and namespace changes."
                                        + " Pinning Hibernate to an earlier major version"
                                        + " overrides the Spring Boot BOM and breaks this"
                                        + " contract.")
                        .possibleImpact(
                                "The application will fail to start with"
                                        + " ClassNotFoundException, NoSuchMethodError, or"
                                        + " EntityManagerFactory errors when the Hibernate and"
                                        + " Spring Data JPA APIs are mismatched.")
                        .recommendation(
                                "Remove the explicit Hibernate version override from your build"
                                        + " script and let the Spring Boot BOM manage the"
                                        + " version, or upgrade to Hibernate "
                                        + requiredHibernateMajor
                                        + ".x explicitly.")
                        .evidence(
                                "Resolved hibernate-core "
                                        + hibernateVersion
                                        + " with Spring Boot "
                                        + bootVersion
                                        + ". Hibernate "
                                        + requiredHibernateMajor
                                        + ".x is required.")
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

    /** Gradle configurations whose dependencies are packaged into the bootJar/bootWar. */
    private static final Set<String> PACKAGED_GRADLE_CONFIGURATIONS =
            Set.of("implementation", "runtimeOnly", "api", "compile", "runtime");

    private static final Pattern GRADLE_CONFIGURATION_PREFIX =
            Pattern.compile("^\\s*([A-Za-z]+)\\b");

    /** Where a build file puts DevTools into the packaged artifact. */
    private record DevToolsDeclaration(String buildFile, int line, String how) {}

    /**
     * Reports DevTools only where the build actually packages it. Spring Boot excludes DevTools
     * from repackaged archives when Gradle declares it {@code developmentOnly}, and the Maven
     * repackage goal excludes it by default unless {@code excludeDevtools} is set to false — so
     * the dependency list alone, which carries no configuration or scope, cannot tell.
     */
    private void detectDevToolsInProduction(
            Path repositoryRoot, BuildInfo buildInfo, List<Finding> findings) {
        if (repositoryRoot == null || buildInfo == null || buildInfo.dependencies() == null) {
            return;
        }
        boolean hasDevTools =
                buildInfo.dependencies().stream()
                        .anyMatch(dep -> dep.contains("spring-boot-devtools"));
        if (!hasDevTools) {
            return;
        }
        DevToolsDeclaration declaration = packagedDevToolsDeclaration(repositoryRoot);
        if (declaration == null) {
            return;
        }
        findings.add(
                FindingFactory.builder(
                                FindingRules.SPRING_DEVTOOLS_IN_PRODUCTION, FindingConfidence.HIGH)
                        .shortMessage(
                                "spring-boot-devtools is packaged into the production artifact ("
                                        + declaration.how()
                                        + " in "
                                        + declaration.buildFile()
                                        + ").")
                        .whyBadPractice(
                                "Spring Boot keeps DevTools out of repackaged archives only when"
                                    + " Gradle declares it developmentOnly, or through the Maven"
                                    + " repackage goal's default excludeDevtools=true. Declared"
                                    + " this way, it ships inside the production jar. DevTools"
                                    + " switches itself off when the application is launched as a"
                                    + " fully packaged app, so the direct risk is limited, but the"
                                    + " artifact carries a development tool with restart and"
                                    + " remote-update endpoints.")
                        .possibleImpact(
                                "A larger artifact and attack surface. If DevTools does not"
                                    + " recognise the launch as packaged, or"
                                    + " spring.devtools.remote.secret is set, live reload, restart"
                                    + " and the remote-update endpoint become active in"
                                    + " production.")
                        .recommendation(
                                "In Gradle, declare it as"
                                    + " developmentOnly(\"org.springframework.boot:spring-boot-devtools\")."
                                    + " In Maven, remove the excludeDevtools=false override and"
                                    + " mark the dependency <optional>true</optional>. Verify with"
                                    + " jar tf <artifact>.jar | grep devtools.")
                        .evidence(
                                declaration.buildFile()
                                        + " line "
                                        + declaration.line()
                                        + ": spring-boot-devtools "
                                        + declaration.how()
                                        + ".")
                        .limitations(
                                "Only the root build.gradle, build.gradle.kts and pom.xml are"
                                        + " inspected; DevTools added in a subproject or"
                                        + " through a convention plugin is not seen.")
                        .source(declaration.buildFile(), declaration.line())
                        .target("spring-boot-devtools")
                        .build());
    }

    private DevToolsDeclaration packagedDevToolsDeclaration(Path repositoryRoot) {
        for (String gradleFile : List.of("build.gradle", "build.gradle.kts")) {
            List<String> lines = readLines(repositoryRoot.resolve(gradleFile));
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                String trimmed = line.trim();
                if (!line.contains("devtools") || trimmed.startsWith("//")) {
                    continue;
                }
                Matcher matcher = GRADLE_CONFIGURATION_PREFIX.matcher(line);
                if (matcher.find() && PACKAGED_GRADLE_CONFIGURATIONS.contains(matcher.group(1))) {
                    return new DevToolsDeclaration(
                            gradleFile, index + 1, "declared as " + matcher.group(1));
                }
            }
        }
        List<String> pomLines = readLines(repositoryRoot.resolve("pom.xml"));
        String pom = String.join("\n", pomLines);
        if (pom.contains("<artifactId>spring-boot-devtools</artifactId>")
                && pom.replaceAll("\\s+", "")
                        .contains("<excludeDevtools>false</excludeDevtools>")) {
            for (int index = 0; index < pomLines.size(); index++) {
                if (pomLines.get(index).contains("<artifactId>spring-boot-devtools</artifactId>")) {
                    return new DevToolsDeclaration(
                            "pom.xml", index + 1, "included by excludeDevtools=false");
                }
            }
        }
        return null;
    }

    private static List<String> readLines(Path file) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException | java.io.UncheckedIOException exception) {
            return List.of();
        }
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_ASYNC_SECURITY_CONTEXT_LOST
    // ---------------------------------------------------------------------------

    private void detectAsyncSecurityContextLost(
            JavaSources javaSources, BuildInfo buildInfo, List<Finding> findings) {
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
        if (!sourcesContainAnnotation(javaSources, "Async")) {
            return;
        }
        boolean hasDelegatingExecutor =
                sourcesContainIdentifier(javaSources, "DelegatingSecurityContextAsyncTaskExecutor")
                        || sourcesContainIdentifier(
                                javaSources, "DelegatingSecurityContextExecutor")
                        || sourcesContainIdentifier(javaSources, "MODE_INHERITABLETHREADLOCAL");
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
                                    + " to the new thread before each task runs. Avoid"
                                    + " MODE_INHERITABLETHREADLOCAL with @Async: inheritable"
                                    + " thread-locals are copied only when a thread is created, so"
                                    + " reused pool threads carry a stale — potentially another"
                                    + " user's — SecurityContext.")
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
    // Parsed-source helpers
    // ---------------------------------------------------------------------------

    private boolean sourcesContainAnnotation(JavaSources javaSources, String annotationName) {
        return javaSources.files().stream()
                .map(JavaSources.JavaFile::compilationUnit)
                .filter(Objects::nonNull)
                .flatMap(compilationUnit -> compilationUnit.findAll(AnnotationExpr.class).stream())
                .anyMatch(
                        annotation -> annotationName.equals(annotation.getName().getIdentifier()));
    }

    /**
     * Returns whether any parsed source references the given identifier, either symbolically or
     * as a string literal.
     *
     * <p>The literal form matters: {@code SecurityContextHolder.setStrategyName(String)} takes the
     * strategy name as text, so {@code setStrategyName("MODE_INHERITABLETHREADLOCAL")} and
     * {@code System.setProperty("spring.security.strategy", "MODE_INHERITABLETHREADLOCAL")} are
     * both idiomatic. Matching only {@link SimpleName} nodes would miss them and report a
     * SecurityContext propagation problem in projects that have already solved it. Comments are
     * still excluded, because the search runs over the AST rather than raw file text.
     */
    private boolean sourcesContainIdentifier(JavaSources javaSources, String identifier) {
        return javaSources.files().stream()
                .map(JavaSources.JavaFile::compilationUnit)
                .filter(Objects::nonNull)
                .anyMatch(
                        compilationUnit ->
                                compilationUnit.findAll(SimpleName.class).stream()
                                                .anyMatch(
                                                        name ->
                                                                identifier.equals(
                                                                        name.getIdentifier()))
                                        || compilationUnit.findAll(StringLiteralExpr.class).stream()
                                                .anyMatch(
                                                        literal ->
                                                                identifier.equals(
                                                                        literal.asString())));
    }
}
