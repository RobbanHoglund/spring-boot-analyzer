package com.robbanhoglund.springbootanalyzer.analyzer.gradle.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradlePluginDeclarationSource;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GradlePluginDeclarationScannerTest {

    private final GradlePluginDeclarationScanner scanner =
            new GradlePluginDeclarationScanner(new GradleVersionCatalogPluginScanner());

    @TempDir
    Path tempDir;

    @Test
    void extractsSettingsAndProjectPluginsFromGroovyDsl() throws Exception {
        Files.writeString(tempDir.resolve("settings.gradle"), """
                plugins {
                    id 'org.gradle.toolchains.foojay-resolver-convention' version '1.0.0'
                }
                """);
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins {
                    id 'org.springframework.boot' version '3.5.13'
                    id 'io.spring.dependency-management' version '1.1.7' apply false
                    id 'java'
                }
                """);

        var declarations = scanner.scan(tempDir);

        assertThat(declarations)
                .extracting(item -> item.pluginId() + ":" + item.version())
                .contains("org.gradle.toolchains.foojay-resolver-convention:1.0.0",
                        "org.springframework.boot:3.5.13",
                        "io.spring.dependency-management:1.1.7",
                        "java:null");
        assertThat(declarations)
                .filteredOn(item -> "org.gradle.toolchains.foojay-resolver-convention".equals(item.pluginId()))
                .singleElement()
                .extracting(item -> item.source())
                .isEqualTo(GradlePluginDeclarationSource.SETTINGS_PLUGINS_BLOCK);
    }

    @Test
    void resolvesVersionCatalogAliases() throws Exception {
        Files.createDirectories(tempDir.resolve("gradle"));
        Files.writeString(tempDir.resolve("gradle/libs.versions.toml"), """
                [versions]
                dependency-management = "1.1.7"

                [plugins]
                spring-boot = { id = "org.springframework.boot", version = "3.5.13" }
                dependency-management = { id = "io.spring.dependency-management", version.ref = "dependency-management" }
                """);
        Files.writeString(tempDir.resolve("build.gradle.kts"), """
                plugins {
                    alias(libs.plugins.spring.boot)
                    alias(libs.plugins.dependency.management) apply false
                }
                """);

        var declarations = scanner.scan(tempDir);

        assertThat(declarations)
                .extracting(item -> item.pluginId() + ":" + item.version())
                .contains("org.springframework.boot:3.5.13", "io.spring.dependency-management:1.1.7");
        assertThat(declarations)
                .allMatch(item -> item.source() == GradlePluginDeclarationSource.VERSION_CATALOG_ALIAS);
    }
}
