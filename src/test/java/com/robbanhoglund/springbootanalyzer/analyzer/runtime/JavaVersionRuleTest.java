package com.robbanhoglund.springbootanalyzer.analyzer.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildInfo;
import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildTool;
import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.ConfigurationAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.ConfigurationSummary;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleAnalysisStatus;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleJavaToolchainModel;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleModelAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradlePluginResolutionBridgeResult;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleResolvedDependencyModel;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for Java-version-aware finding rules:
 * SPRING_BOOT3_REQUIRES_JAVA17 and SPRING_VIRTUAL_THREADS_JAVA_TOO_OLD.
 */
class JavaVersionRuleTest {

    private final RuntimeStackAnalyzer analyzer = new RuntimeStackAnalyzer();

    @TempDir Path tempDir;

    // ── SPRING_BOOT3_REQUIRES_JAVA17 ──────────────────────────────────────────

    @Test
    void flagsBoot3WithJava11FromGradleModel() {
        var result =
                analyzer.analyze(
                        tempDir,
                        buildInfo("3.5.1", "11"),
                        gradleModel("3.5.1", "11"),
                        emptyConfig(),
                        List.of(),
                        List.of());

        Finding f = byRule(result.findings(), "SPRING_BOOT3_REQUIRES_JAVA17");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("3.5.1");
        assertThat(f.message()).contains("11");
    }

    @Test
    void flagsBoot3WithJava11FromBuildInfoWhenNoGradleModel() {
        var result =
                analyzer.analyze(
                        tempDir,
                        buildInfo("3.2.0", "11"),
                        GradleModelAnalysis.empty(
                                GradleAnalysisStatus.NOT_REQUESTED, "TOOLING_API", List.of()),
                        emptyConfig(),
                        List.of(),
                        List.of());

        Finding f = byRule(result.findings(), "SPRING_BOOT3_REQUIRES_JAVA17");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("11");
    }

    @Test
    void doesNotFlagBoot3WithJava17() {
        var result =
                analyzer.analyze(
                        tempDir,
                        buildInfo("3.5.1", "17"),
                        gradleModel("3.5.1", "17"),
                        emptyConfig(),
                        List.of(),
                        List.of());

        assertThat(byRule(result.findings(), "SPRING_BOOT3_REQUIRES_JAVA17")).isNull();
    }

    @Test
    void doesNotFlagBoot3WithJava21() {
        var result =
                analyzer.analyze(
                        tempDir,
                        buildInfo("3.5.1", "21"),
                        gradleModel("3.5.1", "21"),
                        emptyConfig(),
                        List.of(),
                        List.of());

        assertThat(byRule(result.findings(), "SPRING_BOOT3_REQUIRES_JAVA17")).isNull();
    }

    @Test
    void doesNotFlagBoot2WithJava11() {
        // Boot 2.x supports Java 11; rule should not fire
        var result =
                analyzer.analyze(
                        tempDir,
                        buildInfo("2.7.18", "11"),
                        gradleModel("2.7.18", "11"),
                        emptyConfig(),
                        List.of(),
                        List.of());

        assertThat(byRule(result.findings(), "SPRING_BOOT3_REQUIRES_JAVA17")).isNull();
    }

    @Test
    void doesNotFlagWhenBootVersionUnknown() {
        var result =
                analyzer.analyze(
                        tempDir,
                        buildInfo(null, "11"),
                        GradleModelAnalysis.empty(
                                GradleAnalysisStatus.NOT_REQUESTED, "TOOLING_API", List.of()),
                        emptyConfig(),
                        List.of(),
                        List.of());

        assertThat(byRule(result.findings(), "SPRING_BOOT3_REQUIRES_JAVA17")).isNull();
    }

    @Test
    void doesNotFlagWhenJavaVersionUnknown() {
        var result =
                analyzer.analyze(
                        tempDir,
                        buildInfo("3.5.1", null),
                        GradleModelAnalysis.empty(
                                GradleAnalysisStatus.NOT_REQUESTED, "TOOLING_API", List.of()),
                        emptyConfig(),
                        List.of(),
                        List.of());

        assertThat(byRule(result.findings(), "SPRING_BOOT3_REQUIRES_JAVA17")).isNull();
    }

    @Test
    void usesGradleModelVersionsOverBuildInfoForBoot3Rule() {
        // buildInfo says Boot 2.7 + Java 11, but Gradle model says Boot 3.5 + Java 11 → should flag
        var result =
                analyzer.analyze(
                        tempDir,
                        buildInfo("2.7.18", "11"),
                        gradleModel("3.5.1", "11"),
                        emptyConfig(),
                        List.of(),
                        List.of());

        Finding f = byRule(result.findings(), "SPRING_BOOT3_REQUIRES_JAVA17");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("3.5.1");
    }

    // ── SPRING_VIRTUAL_THREADS_JAVA_TOO_OLD ──────────────────────────────────

    @Test
    void flagsVirtualThreadsEnabledWithJava17() {
        var result =
                analyzer.analyze(
                        tempDir,
                        buildInfo("3.5.1", "17"),
                        gradleModel("3.5.1", "17"),
                        configWithProperty("spring.threads.virtual.enabled", "true"),
                        List.of(),
                        List.of());

        Finding f = byRule(result.findings(), "SPRING_VIRTUAL_THREADS_JAVA_TOO_OLD");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("17");
        assertThat(f.message()).contains("spring.threads.virtual.enabled=true");
    }

    @Test
    void doesNotFlagVirtualThreadsWithJava21() {
        var result =
                analyzer.analyze(
                        tempDir,
                        buildInfo("3.5.1", "21"),
                        gradleModel("3.5.1", "21"),
                        configWithProperty("spring.threads.virtual.enabled", "true"),
                        List.of(),
                        List.of());

        assertThat(byRule(result.findings(), "SPRING_VIRTUAL_THREADS_JAVA_TOO_OLD")).isNull();
    }

    @Test
    void doesNotFlagVirtualThreadsNotEnabled() {
        var result =
                analyzer.analyze(
                        tempDir,
                        buildInfo("3.5.1", "17"),
                        gradleModel("3.5.1", "17"),
                        emptyConfig(),
                        List.of(),
                        List.of());

        assertThat(byRule(result.findings(), "SPRING_VIRTUAL_THREADS_JAVA_TOO_OLD")).isNull();
    }

    @Test
    void usesToolchainVersionForVirtualThreadsRule() {
        // buildInfo says Java 21, but Gradle toolchain says Java 17 → should flag
        var result =
                analyzer.analyze(
                        tempDir,
                        buildInfo("3.5.1", "21"),
                        gradleModelWithToolchain("3.5.1", "17"),
                        configWithProperty("spring.threads.virtual.enabled", "true"),
                        List.of(),
                        List.of());

        Finding f = byRule(result.findings(), "SPRING_VIRTUAL_THREADS_JAVA_TOO_OLD");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("17");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static BuildInfo buildInfo(String springBootVersion, String javaVersion) {
        return new BuildInfo(
                BuildTool.GRADLE,
                true,
                javaVersion,
                List.of(),
                springBootVersion,
                springBootVersion != null ? "Gradle plugins" : null,
                "HIGH");
    }

    private static GradleModelAnalysis gradleModel(String bootVersion, String javaVersion) {
        var resolved =
                bootVersion != null
                        ? List.of(
                                new GradleResolvedDependencyModel(
                                        ":",
                                        "runtimeClasspath",
                                        "org.springframework.boot",
                                        "spring-boot",
                                        bootVersion,
                                        true,
                                        null))
                        : List.<GradleResolvedDependencyModel>of();
        var toolchains =
                javaVersion != null
                        ? List.of(new GradleJavaToolchainModel(":", javaVersion, null, null))
                        : List.<GradleJavaToolchainModel>of();
        return buildGradleModel(resolved, toolchains);
    }

    private static GradleModelAnalysis gradleModelWithToolchain(
            String bootVersion, String toolchainJava) {
        var resolved =
                List.of(
                        new GradleResolvedDependencyModel(
                                ":",
                                "runtimeClasspath",
                                "org.springframework.boot",
                                "spring-boot",
                                bootVersion,
                                true,
                                null));
        var toolchains = List.of(new GradleJavaToolchainModel(":", toolchainJava, null, null));
        return buildGradleModel(resolved, toolchains);
    }

    private static GradleModelAnalysis buildGradleModel(
            List<GradleResolvedDependencyModel> resolved,
            List<GradleJavaToolchainModel> toolchains) {
        return new GradleModelAnalysis(
                GradleAnalysisStatus.SUCCESS,
                "9.5",
                "21",
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
                toolchains,
                List.of());
    }

    private static ConfigurationAnalysis emptyConfig() {
        return new ConfigurationAnalysis(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new ConfigurationSummary(0, 0, 0, 0, 0, 0, List.of()));
    }

    private static ConfigurationAnalysis configWithProperty(String name, String value) {
        var prop =
                new com.robbanhoglund.springbootanalyzer.analyzer.model.configuration
                        .ApplicationProperty(
                        name,
                        value,
                        false,
                        false,
                        "src/main/resources/application.properties",
                        1,
                        "default",
                        com.robbanhoglund.springbootanalyzer.analyzer.model.configuration
                                .PropertyKind.SPRING_BOOT,
                        com.robbanhoglund.springbootanalyzer.analyzer.model.configuration
                                .PropertyDocumentation.unknown(),
                        List.of());
        return new ConfigurationAnalysis(
                List.of(),
                List.of(prop),
                List.of(),
                List.of(),
                new ConfigurationSummary(1, 0, 0, 0, 0, 0, List.of("default")));
    }

    private static Finding byRule(List<Finding> findings, String ruleId) {
        return findings.stream().filter(f -> ruleId.equals(f.ruleId())).findFirst().orElse(null);
    }
}
