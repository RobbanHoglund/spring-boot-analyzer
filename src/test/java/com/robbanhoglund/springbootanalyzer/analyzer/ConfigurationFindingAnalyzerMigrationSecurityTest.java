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
 * Tests for the migration and security configuration rules added to
 * {@link ConfigurationFindingAnalyzer}: JDBC URL embedded credentials, the literal default user
 * password, the deprecated {@code spring.profiles} property, and the renamed {@code httptrace}
 * actuator endpoint.
 */
class ConfigurationFindingAnalyzerMigrationSecurityTest {

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
        return propWith(name, value, profile, false);
    }

    private static ApplicationProperty propWith(
            String name, String value, String profile, boolean placeholder) {
        return new ApplicationProperty(
                name,
                value,
                false,
                placeholder,
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

    // ── SPRING_JDBC_URL_EMBEDDED_CREDENTIALS ──────────────────────────────────

    @Test
    void flagsPasswordEmbeddedInJdbcUrl() {
        ConfigurationAnalysis cfg =
                config(
                        prop(
                                "spring.datasource.url",
                                "jdbc:postgresql://db.example.com/orders?user=admin&password=secret",
                                null));

        Finding f = byRule(findings(cfg), "SPRING_JDBC_URL_EMBEDDED_CREDENTIALS");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("spring.datasource.url");
    }

    @Test
    void doesNotEchoThePasswordValue() {
        ConfigurationAnalysis cfg =
                config(prop("spring.datasource.url", "jdbc:mysql://h/db?password=hunter2", null));

        Finding f = byRule(findings(cfg), "SPRING_JDBC_URL_EMBEDDED_CREDENTIALS");
        assertThat(f).isNotNull();
        assertThat(f.message()).doesNotContain("hunter2");
    }

    @Test
    void doesNotFlagJdbcUrlWithoutCredentials() {
        ConfigurationAnalysis cfg =
                config(prop("spring.datasource.url", "jdbc:postgresql://localhost/db", null));

        assertThat(byRule(findings(cfg), "SPRING_JDBC_URL_EMBEDDED_CREDENTIALS")).isNull();
    }

    // ── SPRING_DEFAULT_USER_PASSWORD_LITERAL ──────────────────────────────────

    @Test
    void flagsLiteralDefaultUserPassword() {
        ConfigurationAnalysis cfg = config(prop("spring.security.user.password", "admin123", null));

        Finding f = byRule(findings(cfg), "SPRING_DEFAULT_USER_PASSWORD_LITERAL");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("spring.security.user.password");
    }

    @Test
    void doesNotFlagPlaceholderDefaultUserPassword() {
        ConfigurationAnalysis cfg =
                config(propWith("spring.security.user.password", "${ADMIN_PW}", null, true));

        assertThat(byRule(findings(cfg), "SPRING_DEFAULT_USER_PASSWORD_LITERAL")).isNull();
    }

    // ── SPRING_PROFILES_PROPERTY_DEPRECATED ───────────────────────────────────

    @Test
    void flagsDeprecatedSpringProfilesProperty() {
        ConfigurationAnalysis cfg = config(prop("spring.profiles", "dev", null));

        Finding f = byRule(findings(cfg), "SPRING_PROFILES_PROPERTY_DEPRECATED");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("spring.profiles");
    }

    @Test
    void doesNotFlagSpringProfilesActive() {
        ConfigurationAnalysis cfg = config(prop("spring.profiles.active", "prod", null));

        assertThat(byRule(findings(cfg), "SPRING_PROFILES_PROPERTY_DEPRECATED")).isNull();
    }

    // ── SPRING_ACTUATOR_HTTPTRACE_RENAMED ─────────────────────────────────────

    @Test
    void flagsHttptraceInExposureInclude() {
        ConfigurationAnalysis cfg =
                config(
                        prop(
                                "management.endpoints.web.exposure.include",
                                "health,info,httptrace",
                                null));

        Finding f = byRule(findings(cfg), "SPRING_ACTUATOR_HTTPTRACE_RENAMED");
        assertThat(f).isNotNull();
    }

    @Test
    void flagsDedicatedHttptraceProperty() {
        ConfigurationAnalysis cfg =
                config(prop("management.endpoint.httptrace.enabled", "true", null));

        assertThat(byRule(findings(cfg), "SPRING_ACTUATOR_HTTPTRACE_RENAMED")).isNotNull();
    }

    @Test
    void doesNotFlagHttpexchanges() {
        ConfigurationAnalysis cfg =
                config(
                        prop(
                                "management.endpoints.web.exposure.include",
                                "health,info,httpexchanges",
                                null));

        assertThat(byRule(findings(cfg), "SPRING_ACTUATOR_HTTPTRACE_RENAMED")).isNull();
    }
}
