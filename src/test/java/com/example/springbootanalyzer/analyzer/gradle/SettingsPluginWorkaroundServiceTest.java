package com.example.springbootanalyzer.analyzer.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.springbootanalyzer.analyzer.model.gradle.GradlePluginResolutionFailure;
import com.example.springbootanalyzer.config.AnalyzerProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsPluginWorkaroundServiceTest {

    private final SettingsPluginWorkaroundService service = new SettingsPluginWorkaroundService();

    @TempDir
    Path tempDir;

    @Test
    void disablesGroovyFoojayPluginInSanitizedCopyOnly() throws Exception {
        Path repositoryRoot = tempDir.resolve("repository");
        Files.createDirectories(repositoryRoot);
        Files.writeString(repositoryRoot.resolve("settings.gradle"), """
                plugins {
                    id 'org.gradle.toolchains.foojay-resolver-convention' version '1.0.0'
                    id 'com.example.other' version '2.0.0'
                }
                """);
        Files.writeString(repositoryRoot.resolve("build.gradle"), "plugins { id 'java' }");

        SettingsPluginWorkaroundService.WorkaroundResult result = service.createSanitizedCopyAndApply(
                repositoryRoot,
                new GradlePluginResolutionFailure(
                        "org.gradle.toolchains.foojay-resolver-convention",
                        "1.0.0",
                        "artifact",
                        "settings.gradle",
                        2,
                        List.of(),
                        "message"
                ),
                gradleProperties()
        );

        assertThat(result.applied()).isTrue();
        assertThat(result.appliedWorkarounds()).hasSize(1);
        assertThat(Files.readString(repositoryRoot.resolve("settings.gradle")))
                .contains("id 'org.gradle.toolchains.foojay-resolver-convention' version '1.0.0'");
        assertThat(Files.readString(result.sanitizedRepositoryRoot().resolve("settings.gradle")))
                .contains("Disabled by Spring Boot Analyzer build-model workaround")
                .contains("com.example.other");
        assertThat(Files.readString(result.sanitizedRepositoryRoot().resolve("build.gradle"))).isEqualTo("plugins { id 'java' }");
    }

    private AnalyzerProperties.GradleProperties gradleProperties() {
        return new AnalyzerProperties.GradleProperties(
                true,
                Duration.ofSeconds(5),
                com.example.springbootanalyzer.analyzer.model.gradle.GradleExecutionMode.TOOLING_API,
                "9.5.0",
                tempDir.resolve("gradle-cache"),
                List.of(),
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
                new AnalyzerProperties.SettingsPluginWorkaroundProperties(true, true, List.of("org.gradle.toolchains.foojay-resolver-convention"), 1),
                new AnalyzerProperties.PluginResolutionBridgeProperties(
                        false,
                        false,
                        false,
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
                1024,
                100
        );
    }
}
