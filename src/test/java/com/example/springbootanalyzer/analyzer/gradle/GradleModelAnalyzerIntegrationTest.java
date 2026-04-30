package com.example.springbootanalyzer.analyzer.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.springbootanalyzer.api.dto.AnalysisMode;
import com.example.springbootanalyzer.analyzer.model.BuildInfo;
import com.example.springbootanalyzer.analyzer.model.BuildTool;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleAnalysisStatus;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleExecutionMode;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleResolutionResult;
import com.example.springbootanalyzer.analyzer.gradle.plugin.GradleCorePluginDetector;
import com.example.springbootanalyzer.analyzer.gradle.plugin.GradlePluginDeclarationScanner;
import com.example.springbootanalyzer.analyzer.gradle.plugin.GradlePluginResolutionBridge;
import com.example.springbootanalyzer.analyzer.gradle.plugin.GradleVersionCatalogPluginScanner;
import com.example.springbootanalyzer.config.AnalyzerProperties;
import com.example.springbootanalyzer.git.GitRepositoryReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GradleModelAnalyzerIntegrationTest {

    @TempDir
    Path tempDir;

    private final Path sharedGradleCache = Path.of(
            System.getProperty("java.io.tmpdir"),
            "spring-boot-analyzer-it-gradle-cache"
    );

    @Test
    void springBootProjectProducesResolvedDependencies() throws Exception {
        Files.writeString(tempDir.resolve("settings.gradle"), """
                pluginManagement {
                    repositories {
                        gradlePluginPortal()
                        mavenCentral()
                    }
                }
                rootProject.name = 'demo-app'
                """);
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'org.springframework.boot' version '3.5.13'
                    id 'io.spring.dependency-management' version '1.1.7'
                }

                repositories {
                    mavenCentral()
                }

                dependencies {
                    implementation 'org.springframework.boot:spring-boot-starter-web'
                    implementation 'org.springframework.boot:spring-boot-starter-jdbc'
                    implementation 'org.flywaydb:flyway-core:11.20.3'
                    runtimeOnly 'org.postgresql:postgresql:42.7.7'
                    testImplementation 'org.springframework.boot:spring-boot-starter-test'
                }
                """);

        GradleFailureClassifier failureClassifier = new GradleFailureClassifier(new GradlePluginResolutionFailureParser());
        GradleModelAnalyzer analyzer = new GradleModelAnalyzer(
                new GradleSafetyPolicy(new GradleJavaCompatibilityService()),
                new GradleJavaCompatibilityService(),
                new GradleToolingApiExecutionService(new GradleJavaCompatibilityService(), failureClassifier),
                new GradleExecutionService(
                        new GradleCommandBuilder(),
                        new GradleExecutableLocator(),
                        new GradleJavaCompatibilityService(),
                        failureClassifier
                ),
                new GradleModelReportParser(),
                new GradleSettingsPluginScanner(),
                new GradlePluginDeclarationScanner(new GradleVersionCatalogPluginScanner()),
                new GradlePluginResolutionBridge(new GradleCorePluginDetector())
        );

        var result = analyzer.analyze(
                new GitRepositoryReference("https://github.com/example/demo-app.git", "main", null, AnalysisMode.STATIC_PLUS_GRADLE_MODEL),
                tempDir,
                new BuildInfo(
                        BuildTool.GRADLE,
                        true,
                        "25",
                        List.of("org.springframework.boot:spring-boot-starter-web"),
                        "3.5.13",
                        "Gradle plugins",
                        "HIGH"
                ),
                analyzerProperties()
        );

        assertThat(result.gradleModelAnalysis().status())
                .isIn(GradleAnalysisStatus.SUCCESS, GradleAnalysisStatus.SUCCESS_WITH_WORKAROUND, GradleAnalysisStatus.PARTIAL);
        assertThat(result.gradleModelAnalysis().declaredDependencies()).isNotEmpty();
        assertThat(result.gradleModelAnalysis().resolvedDependencies()).isNotEmpty();
        assertThat(result.gradleModelAnalysis().repositories()).isNotEmpty();
        assertThat(result.gradleModelAnalysis().configurations())
                .extracting(configuration -> configuration.name())
                .contains("compileClasspath", "runtimeClasspath");
        assertThat(result.gradleModelAnalysis().resolvedDependencies().stream()
                .filter(item -> "org.springframework.boot".equals(item.group())
                        && "spring-boot-starter-web".equals(item.artifact())
                        && item.version() != null
                        && !item.version().isBlank())
                .toList())
                .isNotEmpty();
        assertThat(result.gradleModelAnalysis().resolvedDependencies().stream()
                .filter(item -> "org.springframework".equals(item.group())
                        && item.version() != null
                        && !item.version().isBlank())
                .toList())
                .isNotEmpty();
        assertThat(result.gradleModelAnalysis().resolvedDependencies().stream()
                .filter(item -> "compileClasspath".equals(item.configuration())
                        && "org.springframework.boot".equals(item.group())
                        && "spring-boot-starter-web".equals(item.artifact()))
                .toList())
                .isNotEmpty()
                .allSatisfy(item -> assertThat(item.direct()).isTrue());
        assertThat(result.gradleModelAnalysis().resolvedDependencies().stream()
                .filter(item -> List.of("compileClasspath", "runtimeClasspath").contains(item.configuration())
                        && "org.springframework".equals(item.group())
                        && "spring-core".equals(item.artifact()))
                .toList())
                .isNotEmpty()
                .allSatisfy(item -> assertThat(item.direct()).isFalse());
        long uniqueResolvedModules = result.gradleModelAnalysis().resolvedDependencies().stream()
                .map(item -> item.group() + ":" + item.artifact() + ":" + item.version())
                .distinct()
                .count();
        assertThat(uniqueResolvedModules).isLessThan(result.gradleModelAnalysis().resolvedDependencies().size());
        assertThat(result.gradleModelAnalysis().resolutionResults().stream()
                .filter(item -> List.of("compileClasspath", "runtimeClasspath", "testRuntimeClasspath").contains(item.configuration()))
                .toList())
                .isNotEmpty()
                .allSatisfy(item -> {
                    assertThat(item.successful()).isTrue();
                    assertThat(item.resolvedDependencyCount()).isGreaterThan(0);
                });
        assertThat(result.findings()).extracting(finding -> finding.message())
                .noneMatch(message -> message.contains("Gradle plugin resolution was bridged through a local analyzer plugin cache"));
    }

    @Test
    void failedDependencyResolutionDoesNotLeakResolvedDependencyRows() throws Exception {
        Files.writeString(tempDir.resolve("settings.gradle"), """
                pluginManagement {
                    repositories {
                        gradlePluginPortal()
                        mavenCentral()
                    }
                }
                rootProject.name = 'broken-demo'
                """);
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'org.springframework.boot' version '3.5.13'
                    id 'io.spring.dependency-management' version '1.1.7'
                }

                repositories {
                    mavenCentral()
                }

                dependencies {
                    implementation 'org.springframework.boot:spring-boot-starter-web:0.0.0-does-not-exist'
                }
                """);

        GradleFailureClassifier failureClassifier = new GradleFailureClassifier(new GradlePluginResolutionFailureParser());
        GradleModelAnalyzer analyzer = new GradleModelAnalyzer(
                new GradleSafetyPolicy(new GradleJavaCompatibilityService()),
                new GradleJavaCompatibilityService(),
                new GradleToolingApiExecutionService(new GradleJavaCompatibilityService(), failureClassifier),
                new GradleExecutionService(
                        new GradleCommandBuilder(),
                        new GradleExecutableLocator(),
                        new GradleJavaCompatibilityService(),
                        failureClassifier
                ),
                new GradleModelReportParser(),
                new GradleSettingsPluginScanner(),
                new GradlePluginDeclarationScanner(new GradleVersionCatalogPluginScanner()),
                new GradlePluginResolutionBridge(new GradleCorePluginDetector())
        );

        var result = analyzer.analyze(
                new GitRepositoryReference("https://github.com/example/broken-demo.git", "main", null, AnalysisMode.STATIC_PLUS_GRADLE_MODEL),
                tempDir,
                new BuildInfo(
                        BuildTool.GRADLE,
                        true,
                        "25",
                        List.of("org.springframework.boot:spring-boot-starter-web"),
                        "3.5.13",
                        "Gradle plugins",
                        "HIGH"
                ),
                analyzerProperties()
        );

        assertThat(result.gradleModelAnalysis().status()).isEqualTo(GradleAnalysisStatus.PARTIAL);
        assertThat(result.gradleModelAnalysis().resolvedDependencies()).isEmpty();
        List<GradleResolutionResult> dependencyBearingResults = result.gradleModelAnalysis().resolutionResults().stream()
                .filter(item -> List.of("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath")
                        .contains(item.configuration()))
                .toList();
        assertThat(dependencyBearingResults).isNotEmpty();
        assertThat(dependencyBearingResults)
                .allSatisfy(item -> {
                    assertThat(item.successful()).isFalse();
                    assertThat(item.resolvedDependencyCount()).isZero();
                    assertThat(item.errorMessage()).isNotBlank();
                });
    }

    private AnalyzerProperties analyzerProperties() {
        return new AnalyzerProperties(
                tempDir,
                true,
                false,
                new AnalyzerProperties.ScheduledWorkspaceCleanupProperties(true, Duration.ofDays(7), 4),
                new AnalyzerProperties.GradleProperties(
                        true,
                        Duration.ofMinutes(2),
                        GradleExecutionMode.TOOLING_API,
                        "9.5.0",
                        sharedGradleCache,
                        List.of("HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "no_proxy"),
                        null,
                        null,
                        true,
                        List.of("https://plugins.gradle.org/m2/"),
                        true,
                        false,
                        true,
                        false,
                        true,
                        false,
                        false,
                        new AnalyzerProperties.SettingsPluginWorkaroundProperties(false, false, List.of(), 1),
                        new AnalyzerProperties.PluginResolutionBridgeProperties(
                                true,
                                true,
                                true,
                                "Spring Boot Analyzer plugin cache",
                                List.of("https://plugins.gradle.org/m2/", "https://repo.maven.apache.org/maven2/"),
                                Duration.ofSeconds(30),
                                50,
                                500,
                                false,
                                2
                        ),
                        false,
                        false,
                        true,
                        null,
                        null,
                        1024 * 1024,
                        10_000
                )
        );
    }
}
