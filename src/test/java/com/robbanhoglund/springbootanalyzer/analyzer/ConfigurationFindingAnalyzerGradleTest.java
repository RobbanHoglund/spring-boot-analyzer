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
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradlePluginResolutionBridgeResult;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleResolvedDependencyModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ConfigurationFindingAnalyzer} rules that require Gradle model data —
 * specifically those that use resolved dependency versions rather than presence-only signals.
 */
class ConfigurationFindingAnalyzerGradleTest {

    @TempDir Path repoRoot;

    private ConfigurationFindingAnalyzer analyzer;
    private BuildInfo buildInfoBoot3;
    private BuildInfo buildInfoBoot2;

    @BeforeEach
    void setUp() {
        analyzer = new ConfigurationFindingAnalyzer();
        buildInfoBoot3 =
                new BuildInfo(
                        BuildTool.GRADLE, true, "17", List.of(), "3.5.1", "Gradle plugins", "HIGH");
        buildInfoBoot2 =
                new BuildInfo(
                        BuildTool.GRADLE,
                        true,
                        "11",
                        List.of(),
                        "2.7.18",
                        "Gradle plugins",
                        "HIGH");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static GradleModelAnalysis gradleModelWith(
            List<GradleResolvedDependencyModel> resolved) {
        return new GradleModelAnalysis(
                GradleAnalysisStatus.SUCCESS,
                "9.5",
                "17",
                "TOOLING_API",
                null,
                null,
                null,
                false,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                GradlePluginResolutionBridgeResult.empty(),
                false,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                resolved,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private static GradleResolvedDependencyModel dep(
            String group, String artifact, String version) {
        return new GradleResolvedDependencyModel(
                ":", "runtimeClasspath", group, artifact, version, true, null);
    }

    private static ConfigurationAnalysis emptyConfig() {
        return new ConfigurationAnalysis(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new ConfigurationSummary(0, 0, 0, 0, 0, 0, List.of()));
    }

    private List<Finding> findings(BuildInfo build, GradleModelAnalysis gradle) {
        return analyzer.analyze(repoRoot, build, emptyConfig(), gradle);
    }

    private static Finding byRule(List<Finding> findings, String ruleId) {
        return findings.stream().filter(f -> ruleId.equals(f.ruleId())).findFirst().orElse(null);
    }

    // ── SPRING_HIBERNATE_VERSION_MISMATCH ─────────────────────────────────────

    @Test
    void flagsHibernate5WithSpringBoot3UsingResolvedBootVersion() {
        GradleModelAnalysis gradle =
                gradleModelWith(
                        List.of(
                                dep("org.springframework.boot", "spring-boot", "3.5.1"),
                                dep("org.hibernate", "hibernate-core", "5.6.15.Final")));

        Finding f = byRule(findings(buildInfoBoot3, gradle), "SPRING_HIBERNATE_VERSION_MISMATCH");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("5.6.15.Final");
        assertThat(f.message()).contains("3.5.1");
        assertThat(f.target()).isEqualTo("org.hibernate:hibernate-core");
    }

    @Test
    void flagsHibernate4WithSpringBoot3() {
        GradleModelAnalysis gradle =
                gradleModelWith(
                        List.of(
                                dep("org.springframework.boot", "spring-boot", "3.3.0"),
                                dep("org.hibernate", "hibernate-core", "4.3.11.Final")));

        Finding f = byRule(findings(buildInfoBoot3, gradle), "SPRING_HIBERNATE_VERSION_MISMATCH");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("4.3.11.Final");
    }

    @Test
    void doesNotFlagHibernate6WithSpringBoot3() {
        GradleModelAnalysis gradle =
                gradleModelWith(
                        List.of(
                                dep("org.springframework.boot", "spring-boot", "3.5.1"),
                                dep("org.hibernate", "hibernate-core", "6.6.3.Final")));

        assertThat(byRule(findings(buildInfoBoot3, gradle), "SPRING_HIBERNATE_VERSION_MISMATCH"))
                .isNull();
    }

    @Test
    void doesNotFlagHibernate5WithSpringBoot2() {
        // Hibernate 5 is correct for Spring Boot 2.x
        GradleModelAnalysis gradle =
                gradleModelWith(
                        List.of(
                                dep("org.springframework.boot", "spring-boot", "2.7.18"),
                                dep("org.hibernate", "hibernate-core", "5.6.15.Final")));

        assertThat(byRule(findings(buildInfoBoot2, gradle), "SPRING_HIBERNATE_VERSION_MISMATCH"))
                .isNull();
    }

    @Test
    void doesNotFlagWhenHibernateAbsentFromResolvedDependencies() {
        GradleModelAnalysis gradle =
                gradleModelWith(List.of(dep("org.springframework.boot", "spring-boot", "3.5.1")));

        assertThat(byRule(findings(buildInfoBoot3, gradle), "SPRING_HIBERNATE_VERSION_MISMATCH"))
                .isNull();
    }

    @Test
    void doesNotFlagWhenGradleModelNotRequested() {
        GradleModelAnalysis noGradle =
                GradleModelAnalysis.empty(
                        GradleAnalysisStatus.NOT_REQUESTED, "TOOLING_API", List.of());

        assertThat(byRule(findings(buildInfoBoot3, noGradle), "SPRING_HIBERNATE_VERSION_MISMATCH"))
                .isNull();
    }

    @Test
    void usesResolvedBootVersionOverBuildInfoWhenBothPresent() {
        // buildInfo says 3.5.1 but gradle model says 3.3.0 — check uses gradle model boot version
        // with hibernate 5 this should still flag (both are 3.x)
        GradleModelAnalysis gradle =
                gradleModelWith(
                        List.of(
                                dep("org.springframework.boot", "spring-boot", "3.3.0"),
                                dep("org.hibernate", "hibernate-core", "5.6.15.Final")));

        Finding f = byRule(findings(buildInfoBoot3, gradle), "SPRING_HIBERNATE_VERSION_MISMATCH");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("3.3.0"); // resolved version used, not 3.5.1
    }

    // ── Flyway version in evidence ────────────────────────────────────────────

    @Test
    void flywayEvidenceIncludesResolvedVersionWhenGradleModelAvailable() {
        GradleModelAnalysis gradle =
                gradleModelWith(
                        List.of(
                                dep("org.flywaydb", "flyway-core", "10.22.0"),
                                dep("org.springframework.boot", "spring-boot", "3.5.1")));

        // Use a build info with Flyway declared so flywayPresent is true via buildInfo
        BuildInfo buildWithFlyway =
                new BuildInfo(
                        BuildTool.GRADLE,
                        true,
                        "17",
                        List.of("org.flywaydb:flyway-core"),
                        "3.5.1",
                        "Gradle plugins",
                        "HIGH");

        List<Finding> result = analyzer.analyze(repoRoot, buildWithFlyway, emptyConfig(), gradle);
        // SPRING_FLYWAY_MISSING_MIGRATIONS fires because no migration files exist in tempDir
        Finding f = byRule(result, "SPRING_FLYWAY_MISSING_MIGRATIONS");
        assertThat(f).isNotNull();
        assertThat(f.evidence()).contains("Flyway 10.22.0");
    }

    @Test
    void flywayEvidenceFallsBackToGenericLabelWhenNoGradleModel() {
        BuildInfo buildWithFlyway =
                new BuildInfo(
                        BuildTool.GRADLE,
                        true,
                        "17",
                        List.of("org.flywaydb:flyway-core"),
                        "3.5.1",
                        "Gradle plugins",
                        "HIGH");

        GradleModelAnalysis noGradle =
                GradleModelAnalysis.empty(
                        GradleAnalysisStatus.NOT_REQUESTED, "TOOLING_API", List.of());

        List<Finding> result = analyzer.analyze(repoRoot, buildWithFlyway, emptyConfig(), noGradle);
        Finding f = byRule(result, "SPRING_FLYWAY_MISSING_MIGRATIONS");
        assertThat(f).isNotNull();
        assertThat(f.evidence()).startsWith("Flyway ");
        assertThat(f.evidence()).doesNotContain("Flyway 1"); // no version number
    }

    @Test
    void doesNotFlagMissingMigrationsWhenSingleDigitVersionedFilesExist() throws IOException {
        // The conventional V1__init.sql / V2__... naming (single-digit version directly followed by
        // the "__" separator) must be recognised. A regex requiring two or more version characters
        // previously missed these and produced a false "missing migrations" warning.
        Path migrationDir =
                Files.createDirectories(repoRoot.resolve("src/main/resources/db/migration"));
        Files.writeString(migrationDir.resolve("V1__init.sql"), "create table t (id int);");
        Files.writeString(
                migrationDir.resolve("V2__add_users.sql"), "create table users (id int);");

        BuildInfo buildWithFlyway =
                new BuildInfo(
                        BuildTool.GRADLE,
                        true,
                        "17",
                        List.of("org.flywaydb:flyway-core"),
                        "3.5.1",
                        "Gradle plugins",
                        "HIGH");

        List<Finding> result =
                analyzer.analyze(repoRoot, buildWithFlyway, emptyConfig(), buildGradleNone());
        assertThat(byRule(result, "SPRING_FLYWAY_MISSING_MIGRATIONS")).isNull();
    }

    @Test
    void flagsDuplicateSingleDigitMigrationVersions() throws IOException {
        Path migrationDir =
                Files.createDirectories(repoRoot.resolve("src/main/resources/db/migration"));
        Files.writeString(migrationDir.resolve("V1__init.sql"), "create table t (id int);");
        Files.writeString(migrationDir.resolve("V1__also_one.sql"), "create table u (id int);");

        BuildInfo buildWithFlyway =
                new BuildInfo(
                        BuildTool.GRADLE,
                        true,
                        "17",
                        List.of("org.flywaydb:flyway-core"),
                        "3.5.1",
                        "Gradle plugins",
                        "HIGH");

        List<Finding> result =
                analyzer.analyze(repoRoot, buildWithFlyway, emptyConfig(), buildGradleNone());
        assertThat(byRule(result, "SPRING_FLYWAY_MISSING_MIGRATIONS")).isNull();
        Finding duplicate = byRule(result, "SPRING_FLYWAY_DUPLICATE_VERSION");
        assertThat(duplicate).isNotNull();
        assertThat(duplicate.message()).contains("1");
    }

    @Test
    void doesNotFlagMissingMigrationsForCustomClasspathLocation() throws IOException {
        Path dir = Files.createDirectories(repoRoot.resolve("src/main/resources/db/changelog"));
        Files.writeString(dir.resolve("V1__init.sql"), "create table t (id int);");

        List<Finding> result =
                analyzer.analyze(
                        repoRoot,
                        flywayBuild(),
                        configWithFlywayLocations("classpath:db/changelog"),
                        buildGradleNone());
        assertThat(byRule(result, "SPRING_FLYWAY_MISSING_MIGRATIONS")).isNull();
    }

    @Test
    void flagsMissingMigrationsForCustomLocationWhenFilesAreElsewhere() throws IOException {
        // Files live in the DEFAULT db/migration dir, but spring.flyway.locations points at a
        // custom dir — Flyway would not find them there, so the warning is correct and must name
        // the configured location rather than the default.
        Path dir = Files.createDirectories(repoRoot.resolve("src/main/resources/db/migration"));
        Files.writeString(dir.resolve("V1__init.sql"), "create table t (id int);");

        List<Finding> result =
                analyzer.analyze(
                        repoRoot,
                        flywayBuild(),
                        configWithFlywayLocations("classpath:db/changelog"),
                        buildGradleNone());
        Finding f = byRule(result, "SPRING_FLYWAY_MISSING_MIGRATIONS");
        assertThat(f).isNotNull();
        assertThat(f.evidence()).contains("db/changelog");
        assertThat(f.evidence()).doesNotContain("db/migration");
    }

    @Test
    void resolvesFilesystemFlywayLocationRelativeToRepo() throws IOException {
        Path dir = Files.createDirectories(repoRoot.resolve("db/migration"));
        Files.writeString(dir.resolve("V1__init.sql"), "create table t (id int);");

        List<Finding> result =
                analyzer.analyze(
                        repoRoot,
                        flywayBuild(),
                        configWithFlywayLocations("filesystem:db/migration"),
                        buildGradleNone());
        assertThat(byRule(result, "SPRING_FLYWAY_MISSING_MIGRATIONS")).isNull();
    }

    @Test
    void doesNotFlagWhenFlywayLocationIsAbsoluteAndOutsideRepository() {
        // An absolute filesystem location cannot be inspected in a cloned repo; suppress rather
        // than emit a false "missing migrations" finding.
        List<Finding> result =
                analyzer.analyze(
                        repoRoot,
                        flywayBuild(),
                        configWithFlywayLocations("filesystem:/var/lib/db/migration"),
                        buildGradleNone());
        assertThat(byRule(result, "SPRING_FLYWAY_MISSING_MIGRATIONS")).isNull();
    }

    @Test
    void honoursMultipleConfiguredFlywayLocations() throws IOException {
        Path dir = Files.createDirectories(repoRoot.resolve("src/main/resources/db/extra"));
        Files.writeString(dir.resolve("V1__init.sql"), "create table t (id int);");

        List<Finding> result =
                analyzer.analyze(
                        repoRoot,
                        flywayBuild(),
                        configWithFlywayLocations("classpath:db/migration,classpath:db/extra"),
                        buildGradleNone());
        assertThat(byRule(result, "SPRING_FLYWAY_MISSING_MIGRATIONS")).isNull();
    }

    private static BuildInfo flywayBuild() {
        return new BuildInfo(
                BuildTool.GRADLE,
                true,
                "17",
                List.of("org.flywaydb:flyway-core"),
                "3.5.1",
                "Gradle plugins",
                "HIGH");
    }

    private static ConfigurationAnalysis configWithFlywayLocations(String value) {
        ApplicationProperty property =
                new ApplicationProperty(
                        "spring.flyway.locations",
                        value,
                        false,
                        false,
                        "src/main/resources/application.properties",
                        1,
                        "default",
                        PropertyKind.SPRING_BOOT,
                        null,
                        List.of());
        return new ConfigurationAnalysis(
                List.of(),
                List.of(property),
                List.of(),
                List.of(),
                new ConfigurationSummary(0, 0, 0, 0, 0, 0, List.of()));
    }

    private static GradleModelAnalysis buildGradleNone() {
        return GradleModelAnalysis.empty(
                GradleAnalysisStatus.NOT_REQUESTED, "TOOLING_API", List.of());
    }
}
