package com.robbanhoglund.springbootanalyzer.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildInfo;
import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildTool;
import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.ApplicationProperty;
import com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.ConfigurationAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.ConfigurationSummary;
import com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.PropertyKind;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleAnalysisStatus;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleModelAnalysis;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Focused tests for the five profile-drift detection rules added to
 * {@link ConfigurationFindingAnalyzer}. Each test constructs
 * {@link ApplicationProperty} objects directly so the tests are independent
 * of the configuration file parser.
 */
class ConfigurationFindingAnalyzerProfileDriftTest {

    @TempDir Path repoRoot;

    private ConfigurationFindingAnalyzer analyzer;
    private BuildInfo emptyBuild;
    private GradleModelAnalysis noGradle;

    @BeforeEach
    void setUp() {
        analyzer = new ConfigurationFindingAnalyzer();
        emptyBuild =
                new BuildInfo(BuildTool.GRADLE, true, "21", List.of(), "3.5.0", null, "MEDIUM");
        noGradle =
                GradleModelAnalysis.empty(
                        GradleAnalysisStatus.NOT_REQUESTED, "TOOLING_API", List.of());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static ApplicationProperty prop(String name, String value, String profile) {
        return new ApplicationProperty(
                name,
                value,
                false,
                false,
                "src/main/resources/application"
                        + (profile == null ? "" : "-" + profile)
                        + ".properties",
                1,
                profile,
                PropertyKind.SPRING_BOOT,
                null,
                List.of());
    }

    private ConfigurationAnalysis config(ApplicationProperty... properties) {
        return new ConfigurationAnalysis(
                List.of(),
                List.of(properties),
                List.of(),
                List.of(),
                new ConfigurationSummary(0, 0, 0, 0, 0, 0, List.of()));
    }

    private List<Finding> findings(ConfigurationAnalysis cfg) {
        return analyzer.analyze(repoRoot, emptyBuild, cfg, noGradle);
    }

    private static Finding byRule(List<Finding> findings, String ruleId) {
        return findings.stream().filter(f -> ruleId.equals(f.ruleId())).findFirst().orElse(null);
    }

    // ── SPRING_SECURITY_AUTOCONFIGURE_EXCLUDED ────────────────────────────────

    @Test
    void flagsSecurityAutoconfigureExcludedWhenSecurityAutoConfigurationPresent() throws Exception {
        ConfigurationAnalysis cfg =
                config(
                        prop(
                                "spring.autoconfigure.exclude",
                                "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration",
                                "test"));

        Finding f = byRule(findings(cfg), "SPRING_SECURITY_AUTOCONFIGURE_EXCLUDED");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("SecurityAutoConfiguration");
    }

    @Test
    void flagsSecurityAutoconfigureExcludedForUserDetailsServiceAutoConfiguration()
            throws Exception {
        ConfigurationAnalysis cfg =
                config(
                        prop(
                                "spring.autoconfigure.exclude",
                                "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration",
                                null));

        Finding f = byRule(findings(cfg), "SPRING_SECURITY_AUTOCONFIGURE_EXCLUDED");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("UserDetailsServiceAutoConfiguration");
    }

    @Test
    void doesNotFlagExcludeWhenNoSecurityClassPresent() {
        ConfigurationAnalysis cfg =
                config(
                        prop(
                                "spring.autoconfigure.exclude",
                                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                                "test"));

        assertThat(byRule(findings(cfg), "SPRING_SECURITY_AUTOCONFIGURE_EXCLUDED")).isNull();
    }

    @Test
    void doesNotFlagWhenAutoconfigureExcludePropertyIsAbsent() {
        ConfigurationAnalysis cfg =
                config(prop("spring.datasource.url", "jdbc:postgresql://localhost/db", null));

        assertThat(byRule(findings(cfg), "SPRING_SECURITY_AUTOCONFIGURE_EXCLUDED")).isNull();
    }

    // ── SPRING_DATASOURCE_NO_TEST_OVERRIDE ────────────────────────────────────

    @Test
    void flagsDatasourceNoTestOverrideWhenDefaultHasRealDbAndNoTestProfile() {
        ConfigurationAnalysis cfg =
                config(
                        prop(
                                "spring.datasource.url",
                                "jdbc:postgresql://db.prod.example.com:5432/orders",
                                null));

        Finding f = byRule(findings(cfg), "SPRING_DATASOURCE_NO_TEST_OVERRIDE");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("spring.datasource.url");
    }

    @Test
    void doesNotFlagDatasourceNoTestOverrideWhenTestProfileHasDatasource() {
        ConfigurationAnalysis cfg =
                config(
                        prop(
                                "spring.datasource.url",
                                "jdbc:postgresql://db.prod.example.com:5432/orders",
                                null),
                        prop("spring.datasource.url", "jdbc:h2:mem:testdb", "test"));

        assertThat(byRule(findings(cfg), "SPRING_DATASOURCE_NO_TEST_OVERRIDE")).isNull();
    }

    @Test
    void doesNotFlagDatasourceNoTestOverrideWhenDefaultIsH2() {
        ConfigurationAnalysis cfg =
                config(prop("spring.datasource.url", "jdbc:h2:mem:appdb", null));

        assertThat(byRule(findings(cfg), "SPRING_DATASOURCE_NO_TEST_OVERRIDE")).isNull();
    }

    @Test
    void doesNotFlagDatasourceNoTestOverrideWhenNoDatasourceConfigured() {
        ConfigurationAnalysis cfg = config(prop("server.port", "8080", null));

        assertThat(byRule(findings(cfg), "SPRING_DATASOURCE_NO_TEST_OVERRIDE")).isNull();
    }

    @Test
    void recognisesCiProfileAsTestOverrideForDatasource() {
        ConfigurationAnalysis cfg =
                config(
                        prop(
                                "spring.datasource.url",
                                "jdbc:postgresql://db.prod.example.com/orders",
                                null),
                        prop("spring.datasource.url", "jdbc:h2:mem:cidb", "ci"));

        assertThat(byRule(findings(cfg), "SPRING_DATASOURCE_NO_TEST_OVERRIDE")).isNull();
    }

    // ── SPRING_H2_IN_NON_TEST_PROFILE ─────────────────────────────────────────

    @Test
    void flagsH2InDefaultProfileWhenNoTestOverrideExists() {
        // Default profile with H2 — not in a test/local profile
        // "default" is not in TEST_OR_LOCAL_PROFILES so it should be flagged
        ConfigurationAnalysis cfg =
                config(prop("spring.datasource.url", "jdbc:h2:mem:appdb", null));

        Finding f = byRule(findings(cfg), "SPRING_H2_IN_NON_TEST_PROFILE");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("H2");
    }

    @Test
    void doesNotFlagH2InTestProfile() {
        ConfigurationAnalysis cfg =
                config(prop("spring.datasource.url", "jdbc:h2:mem:testdb", "test"));

        assertThat(byRule(findings(cfg), "SPRING_H2_IN_NON_TEST_PROFILE")).isNull();
    }

    @Test
    void doesNotFlagH2InLocalProfile() {
        ConfigurationAnalysis cfg =
                config(prop("spring.datasource.url", "jdbc:h2:mem:localdb", "local"));

        assertThat(byRule(findings(cfg), "SPRING_H2_IN_NON_TEST_PROFILE")).isNull();
    }

    @Test
    void doesNotFlagPostgresInDefaultProfile() {
        ConfigurationAnalysis cfg =
                config(prop("spring.datasource.url", "jdbc:postgresql://localhost:5432/db", null));

        assertThat(byRule(findings(cfg), "SPRING_H2_IN_NON_TEST_PROFILE")).isNull();
    }

    // ── SPRING_FLYWAY_DISABLED_IN_TEST ────────────────────────────────────────

    @Test
    void flagsFlywayDisabledInTestProfile() {
        ConfigurationAnalysis cfg = config(prop("spring.flyway.enabled", "false", "test"));

        Finding f = byRule(findings(cfg), "SPRING_FLYWAY_DISABLED_IN_TEST");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("spring.flyway.enabled");
        assertThat(f.message()).contains("test");
    }

    @Test
    void flagsFlywayDisabledInCiProfile() {
        ConfigurationAnalysis cfg = config(prop("spring.flyway.enabled", "false", "ci"));

        Finding f = byRule(findings(cfg), "SPRING_FLYWAY_DISABLED_IN_TEST");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("ci");
    }

    @Test
    void doesNotFlagFlywayDisabledInProdProfile() {
        // Flyway disabled in prod is a separate concern (intentional blue/green deployment)
        ConfigurationAnalysis cfg = config(prop("spring.flyway.enabled", "false", "prod"));

        assertThat(byRule(findings(cfg), "SPRING_FLYWAY_DISABLED_IN_TEST")).isNull();
    }

    @Test
    void doesNotFlagFlywayEnabledInTestProfile() {
        ConfigurationAnalysis cfg = config(prop("spring.flyway.enabled", "true", "test"));

        assertThat(byRule(findings(cfg), "SPRING_FLYWAY_DISABLED_IN_TEST")).isNull();
    }

    // ── SPRING_SCHEDULING_DISABLED_IN_TEST ────────────────────────────────────

    @Test
    void flagsSpringTaskSchedulingDisabledInTestProfile() {
        ConfigurationAnalysis cfg = config(prop("spring.task.scheduling.enabled", "false", "test"));

        Finding f = byRule(findings(cfg), "SPRING_SCHEDULING_DISABLED_IN_TEST");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("spring.task.scheduling.enabled");
        assertThat(f.message()).contains("test");
    }

    @Test
    void flagsQuartzAutoStartupDisabledInTestProfile() {
        ConfigurationAnalysis cfg = config(prop("spring.quartz.auto-startup", "false", "test"));

        Finding f = byRule(findings(cfg), "SPRING_SCHEDULING_DISABLED_IN_TEST");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("spring.quartz.auto-startup");
    }

    @Test
    void doesNotFlagSchedulingDisabledInProdProfile() {
        ConfigurationAnalysis cfg = config(prop("spring.task.scheduling.enabled", "false", "prod"));

        assertThat(byRule(findings(cfg), "SPRING_SCHEDULING_DISABLED_IN_TEST")).isNull();
    }

    @Test
    void doesNotFlagSchedulingEnabledInTestProfile() {
        ConfigurationAnalysis cfg = config(prop("spring.task.scheduling.enabled", "true", "test"));

        assertThat(byRule(findings(cfg), "SPRING_SCHEDULING_DISABLED_IN_TEST")).isNull();
    }
}
