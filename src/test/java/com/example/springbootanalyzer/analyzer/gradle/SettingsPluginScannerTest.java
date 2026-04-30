package com.example.springbootanalyzer.analyzer.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsPluginScannerTest {

    private final GradleSettingsPluginScanner scanner = new GradleSettingsPluginScanner();

    @TempDir
    Path tempDir;

    @Test
    void extractsGroovySettingsPlugins() throws Exception {
        Files.writeString(tempDir.resolve("settings.gradle"), """
                plugins {
                    id 'org.gradle.toolchains.foojay-resolver-convention' version '1.0.0'
                    id("com.example.demo") version "1.2.3"
                }
                """);

        var plugins = scanner.scan(tempDir);

        assertThat(plugins).hasSize(2);
        assertThat(plugins).extracting("pluginId")
                .containsExactly("org.gradle.toolchains.foojay-resolver-convention", "com.example.demo");
    }

    @Test
    void extractsKotlinSettingsPlugins() throws Exception {
        Files.writeString(tempDir.resolve("settings.gradle.kts"), """
                plugins {
                    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
                }
                """);

        var plugins = scanner.scan(tempDir);

        assertThat(plugins).hasSize(1);
        assertThat(plugins.getFirst().pluginId()).isEqualTo("org.gradle.toolchains.foojay-resolver-convention");
        assertThat(plugins.getFirst().version()).isEqualTo("1.0.0");
    }

    @Test
    void ignoresCommentsAndHandlesMissingVersion() throws Exception {
        Files.writeString(tempDir.resolve("settings.gradle"), """
                plugins {
                    // id 'ignore.me' version '0.0.1'
                    id 'org.gradle.toolchains.foojay-resolver-convention'
                }
                """);

        var plugins = scanner.scan(tempDir);

        assertThat(plugins).hasSize(1);
        assertThat(plugins.getFirst().pluginId()).isEqualTo("org.gradle.toolchains.foojay-resolver-convention");
        assertThat(plugins.getFirst().version()).isNull();
    }
}
