package com.robbanhoglund.springbootanalyzer.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import com.robbanhoglund.springbootanalyzer.analyzer.configuration.ConfigurationAnalyzer;
import com.robbanhoglund.springbootanalyzer.analyzer.configuration.ConfigurationFileScanner;
import com.robbanhoglund.springbootanalyzer.analyzer.configuration.ConfigurationPropertiesClassAnalyzer;
import com.robbanhoglund.springbootanalyzer.analyzer.configuration.PropertiesFileParser;
import com.robbanhoglund.springbootanalyzer.analyzer.configuration.PropertyNameNormalizer;
import com.robbanhoglund.springbootanalyzer.analyzer.configuration.PropertyReferenceAnalyzer;
import com.robbanhoglund.springbootanalyzer.analyzer.configuration.SensitivePropertyValueRedactor;
import com.robbanhoglund.springbootanalyzer.analyzer.configuration.SpringConfigurationMetadataCatalog;
import com.robbanhoglund.springbootanalyzer.analyzer.configuration.YamlConfigurationParser;
import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildInfo;
import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildTool;
import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingSeverity;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleAnalysisStatus;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleModelAnalysis;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Configuration rules exercised through the real parsing pipeline: files are written to a
 * temporary repository, parsed by {@link ConfigurationAnalyzer} and judged by
 * {@link ConfigurationFindingAnalyzer}.
 */
class ConfigurationRulesTest {

    private final PropertyNameNormalizer propertyNameNormalizer = new PropertyNameNormalizer();
    private final ConfigurationAnalyzer configurationAnalyzer =
            new ConfigurationAnalyzer(
                    new ConfigurationFileScanner(),
                    new PropertiesFileParser(),
                    new YamlConfigurationParser(),
                    new SpringConfigurationMetadataCatalog(),
                    new ConfigurationPropertiesClassAnalyzer(propertyNameNormalizer),
                    new PropertyReferenceAnalyzer(propertyNameNormalizer),
                    new SensitivePropertyValueRedactor(),
                    propertyNameNormalizer);
    private final ConfigurationFindingAnalyzer findingAnalyzer =
            new ConfigurationFindingAnalyzer(new SensitivePropertyValueRedactor());

    @TempDir Path repoRoot;

    private void write(String relativePath, String content) throws IOException {
        Path file = repoRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private List<Finding> findings(String ruleId, String bootVersion, String... dependencies) {
        BuildInfo buildInfo =
                new BuildInfo(
                        BuildTool.GRADLE,
                        true,
                        "21",
                        List.of(dependencies),
                        bootVersion,
                        "build.gradle plugin",
                        "HIGH");
        ConfigurationAnalyzer.Result configuration =
                configurationAnalyzer.analyze(repoRoot, buildInfo);
        List<Finding> all = new ArrayList<>(configuration.findings());
        all.addAll(
                findingAnalyzer.analyze(
                        repoRoot,
                        buildInfo,
                        configuration.configurationAnalysis(),
                        GradleModelAnalysis.empty(
                                GradleAnalysisStatus.NOT_REQUESTED, "TOOLING_API", List.of())));
        return all.stream().filter(finding -> ruleId.equals(finding.ruleId())).toList();
    }

    // ── SPRING_REMOVED_CONFIGURATION_PROPERTY ─────────────────────────────────

    @Test
    void flagsKeysSpringBootNoLongerReads() throws IOException {
        write(
                "src/main/resources/application.properties",
                """
                server.tomcat.max-threads=200
                server.context-path=/shop
                spring.sleuth.sampler.probability=1.0
                server.tomcat.threads.max=100
                """);

        List<Finding> removed = findings("SPRING_REMOVED_CONFIGURATION_PROPERTY", "3.5.13");

        assertThat(removed)
                .extracting(Finding::target)
                .containsExactlyInAnyOrder(
                        "server.tomcat.max-threads",
                        "server.context-path",
                        "spring.sleuth.sampler.probability");
        assertThat(removed)
                .filteredOn(finding -> "server.tomcat.max-threads".equals(finding.target()))
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.severity()).isEqualTo(FindingSeverity.WARNING);
                            assertThat(finding.message()).contains("use server.tomcat.threads.max");
                            assertThat(finding.primaryLocation().startLine()).isEqualTo(1);
                        });
    }

    @Test
    void reportsBoot3RemovalsOnlyFromBoot3() throws IOException {
        write(
                "src/main/resources/application.properties",
                """
                spring.sleuth.sampler.probability=1.0
                spring.mvc.pathmatch.use-suffix-pattern=true
                server.context-path=/shop
                """);

        assertThat(findings("SPRING_REMOVED_CONFIGURATION_PROPERTY", "2.7.18"))
                .extracting(Finding::target)
                .containsExactly("server.context-path");
        assertThat(findings("SPRING_REMOVED_CONFIGURATION_PROPERTY", null))
                .extracting(Finding::target)
                .containsExactly("server.context-path");
    }

    // ── SPRING_TASK_EXECUTOR_MAX_POOL_IGNORED ─────────────────────────────────

    @Test
    void flagsTaskExecutionMaxSizeWithoutQueueCapacity() throws IOException {
        write(
                "src/main/resources/application.yml",
                """
                spring:
                  task:
                    execution:
                      pool:
                        core-size: 4
                        max-size: 16
                """);

        assertThat(findings("SPRING_TASK_EXECUTOR_MAX_POOL_IGNORED", "3.5.13"))
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.severity()).isEqualTo(FindingSeverity.WARNING);
                            assertThat(finding.target())
                                    .isEqualTo("spring.task.execution.pool.max-size");
                        });
    }

    @Test
    void doesNotFlagBoundedQueuesSmallMaximumsOrVirtualThreads() throws IOException {
        write(
                "src/main/resources/application.properties",
                """
                spring.task.execution.pool.max-size=16
                spring.task.execution.pool.queue-capacity=100
                """);
        assertThat(findings("SPRING_TASK_EXECUTOR_MAX_POOL_IGNORED", "3.5.13")).isEmpty();

        write(
                "src/main/resources/application.properties",
                "spring.task.execution.pool.max-size=8\n");
        assertThat(findings("SPRING_TASK_EXECUTOR_MAX_POOL_IGNORED", "3.5.13")).isEmpty();

        write(
                "src/main/resources/application.properties",
                """
                spring.task.execution.pool.max-size=32
                spring.threads.virtual.enabled=true
                """);
        assertThat(findings("SPRING_TASK_EXECUTOR_MAX_POOL_IGNORED", "3.5.13")).isEmpty();
    }

    // ── SPRING_FLYWAY_MIGRATION_NAME_IGNORED ──────────────────────────────────

    private void writeMigrations() throws IOException {
        write("src/main/resources/db/migration/V1__init.sql", "create table a (id int);");
        write("src/main/resources/db/migration/v2__lowercase_prefix.sql", "select 1;");
        write("src/main/resources/db/migration/V3_single_underscore.sql", "select 1;");
        write("src/main/resources/db/migration/R_refresh_view.sql", "select 1;");
        write("src/main/resources/db/migration/V4__upper_suffix.SQL", "select 1;");
        write("src/main/resources/db/migration/R__valid_view.sql", "select 1;");
        write("src/main/resources/db/migration/README.md", "notes");
    }

    @Test
    void flagsMigrationFilesFlywaySkips() throws IOException {
        writeMigrations();

        List<Finding> ignored =
                findings(
                        "SPRING_FLYWAY_MIGRATION_NAME_IGNORED",
                        "3.5.13",
                        "org.flywaydb:flyway-core");

        assertThat(ignored)
                .extracting(Finding::target)
                .containsExactlyInAnyOrder(
                        "v2__lowercase_prefix.sql",
                        "V3_single_underscore.sql",
                        "R_refresh_view.sql");
        assertThat(ignored)
                .filteredOn(finding -> "V3_single_underscore.sql".equals(finding.target()))
                .singleElement()
                .extracting(Finding::recommendation)
                .asString()
                .contains("V3__single_underscore.sql");
    }

    @Test
    void doesNotCheckMigrationNamesWithCustomFlywayNaming() throws IOException {
        writeMigrations();
        write(
                "src/main/resources/application.properties",
                "spring.flyway.sql-migration-separator=_\n");

        assertThat(
                        findings(
                                "SPRING_FLYWAY_MIGRATION_NAME_IGNORED",
                                "3.5.13",
                                "org.flywaydb:flyway-core"))
                .isEmpty();
    }

    // ── SPRING_PROFILES_PROPERTY_DEPRECATED ───────────────────────────────────

    private void writeLegacyProfileDocument(String extraDefaultProperties) throws IOException {
        write(
                "src/main/resources/application.yml",
                extraDefaultProperties
                        + """
                        server:
                          port: 8080
                        ---
                        spring:
                          profiles: dev
                        server:
                          port: 9090
                        """);
    }

    @Test
    void reportsLegacySpringProfilesByBootVersion() throws IOException {
        writeLegacyProfileDocument("");

        assertThat(findings("SPRING_PROFILES_PROPERTY_DEPRECATED", "3.5.13"))
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.severity()).isEqualTo(FindingSeverity.ERROR);
                            assertThat(finding.message())
                                    .contains("makes Spring Boot fail at startup");
                        });
        assertThat(findings("SPRING_PROFILES_PROPERTY_DEPRECATED", "2.7.18"))
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.severity()).isEqualTo(FindingSeverity.WARNING);
                            assertThat(finding.message()).contains("makes Spring Boot 3 fail");
                        });
    }

    @Test
    void reportsLegacySpringProfilesUnderLegacyProcessingAsInfo() throws IOException {
        writeLegacyProfileDocument("spring:\n  config:\n    use-legacy-processing: true\n");

        assertThat(findings("SPRING_PROFILES_PROPERTY_DEPRECATED", "2.7.18"))
                .singleElement()
                .extracting(Finding::severity)
                .isEqualTo(FindingSeverity.INFO);
    }

    // ── SPRING_PROFILES_ACTIVE_IN_PROFILE_SPECIFIC_FILE ───────────────────────

    @Test
    void flagsProfileActivationInsideProfileSpecificFilesOncePerKey() throws IOException {
        write(
                "src/main/resources/application-dev.yml",
                """
                spring:
                  profiles:
                    include:
                      - metrics
                      - tracing
                """);
        write("src/main/resources/application-prod.properties", "spring.profiles.default=prod\n");
        write("src/test/resources/application.properties", "spring.profiles.active=test\n");

        List<Finding> invalid =
                findings("SPRING_PROFILES_ACTIVE_IN_PROFILE_SPECIFIC_FILE", "3.5.13");

        assertThat(invalid)
                .extracting(Finding::target)
                .containsExactlyInAnyOrder("spring.profiles.include[0]", "spring.profiles.default");
        assertThat(invalid).allMatch(finding -> finding.severity() == FindingSeverity.ERROR);
    }

    // ── SPRING_CONDITIONAL_VALUE_MISMATCH ─────────────────────────────────────

    @Test
    void treatsBooleanSwitchesAsDeliberateButFlagsUnknownProviderValues() throws IOException {
        write(
                "src/main/resources/application.properties",
                """
                feature.audit.enabled=FALSE
                app.storage=s3x
                """);
        write(
                "src/main/java/com/example/Beans.java",
                """
                package com.example;

                import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
                import org.springframework.stereotype.Component;

                @Component
                @ConditionalOnProperty(name = "feature.audit.enabled", havingValue = "true")
                class AuditTrail {
                }

                @Component
                @ConditionalOnProperty(name = "app.storage", havingValue = "s3")
                class S3Storage {
                }

                @Component
                @ConditionalOnProperty(name = "app.storage", havingValue = "LOCAL")
                class LocalStorage {
                }
                """);

        assertThat(findings("SPRING_CONDITIONAL_VALUE_MISMATCH", "3.5.13"))
                .singleElement()
                .extracting(Finding::target)
                .isEqualTo("app.storage");
    }

    // ── SPRING_H2_IN_NON_TEST_PROFILE ─────────────────────────────────────────

    @Test
    void ignoresH2InTestResources() throws IOException {
        write(
                "src/test/resources/application.properties",
                "spring.datasource.url=jdbc:h2:mem:testdb\n");

        assertThat(findings("SPRING_H2_IN_NON_TEST_PROFILE", "3.5.13")).isEmpty();

        write(
                "src/main/resources/application.properties",
                "spring.datasource.url=jdbc:h2:mem:app\n");
        assertThat(findings("SPRING_H2_IN_NON_TEST_PROFILE", "3.5.13")).hasSize(1);
    }
}
