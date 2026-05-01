package com.robbanhoglund.springbootanalyzer.analyzer.gradle.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradlePluginDeclaration;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradlePluginDeclarationSource;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradlePluginResolutionBridgeResult;
import com.robbanhoglund.springbootanalyzer.config.AnalyzerProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GradlePluginResolutionBridgeTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesMarkerAndImplementationIntoLocalMavenLayout() throws Exception {
        Map<String, byte[]> artifacts = new HashMap<>();
        String pluginRepository = "https://plugins.gradle.org/m2";
        String mavenCentral = "https://repo.maven.apache.org/maven2";
        artifacts.put(
                pluginRepository + "/org/springframework/boot/org.springframework.boot.gradle.plugin/3.5.13/org.springframework.boot.gradle.plugin-3.5.13.pom",
                pom("""
                        <project>
                          <modelVersion>4.0.0</modelVersion>
                          <groupId>org.springframework.boot</groupId>
                          <artifactId>org.springframework.boot.gradle.plugin</artifactId>
                          <version>3.5.13</version>
                          <packaging>pom</packaging>
                          <dependencies>
                            <dependency>
                              <groupId>org.springframework.boot</groupId>
                              <artifactId>spring-boot-gradle-plugin</artifactId>
                              <version>3.5.13</version>
                            </dependency>
                          </dependencies>
                        </project>
                        """)
        );
        artifacts.put(
                pluginRepository + "/org/springframework/boot/spring-boot-gradle-plugin/3.5.13/spring-boot-gradle-plugin-3.5.13.pom",
                pom("""
                        <project>
                          <modelVersion>4.0.0</modelVersion>
                          <groupId>org.springframework.boot</groupId>
                          <artifactId>spring-boot-gradle-plugin</artifactId>
                          <version>3.5.13</version>
                          <properties>
                            <dependency.management.version>1.1.7</dependency.management.version>
                          </properties>
                          <dependencies>
                            <dependency>
                              <groupId>io.spring.gradle</groupId>
                              <artifactId>dependency-management-plugin</artifactId>
                              <version>${dependency.management.version}</version>
                            </dependency>
                          </dependencies>
                        </project>
                        """)
        );
        artifacts.put(
                pluginRepository + "/org/springframework/boot/spring-boot-gradle-plugin/3.5.13/spring-boot-gradle-plugin-3.5.13.jar",
                "jar".getBytes(StandardCharsets.UTF_8)
        );
        artifacts.put(
                mavenCentral + "/io/spring/gradle/dependency-management-plugin/1.1.7/dependency-management-plugin-1.1.7.pom",
                pom("""
                        <project>
                          <modelVersion>4.0.0</modelVersion>
                          <groupId>io.spring.gradle</groupId>
                          <artifactId>dependency-management-plugin</artifactId>
                          <version>1.1.7</version>
                        </project>
                        """)
        );
        artifacts.put(
                mavenCentral + "/io/spring/gradle/dependency-management-plugin/1.1.7/dependency-management-plugin-1.1.7.jar",
                "jar".getBytes(StandardCharsets.UTF_8)
        );

        GradlePluginResolutionBridge bridge = new GradlePluginResolutionBridge(
                new GradleCorePluginDetector(),
                (url, timeout, networkSettings) -> artifacts.containsKey(url)
                        ? new GradlePluginResolutionBridge.ArtifactResponse(200, artifacts.get(url))
                        : new GradlePluginResolutionBridge.ArtifactResponse(404, new byte[0])
        );
        Path repositoryRoot = tempDir.resolve("workspace/repository");
        Files.createDirectories(repositoryRoot);

        GradlePluginResolutionBridgeResult result = bridge.prefetch(
                repositoryRoot,
                List.of(new GradlePluginDeclaration(
                        "org.springframework.boot",
                        "3.5.13",
                        "build.gradle",
                        3,
                        GradlePluginDeclarationSource.PROJECT_PLUGINS_BLOCK,
                        false
                )),
                properties()
        );

        assertThat(result.successful()).isTrue();
        assertThat(result.resolvedPlugins()).singleElement().satisfies(plugin -> {
            assertThat(plugin.markerCoordinates()).isEqualTo("org.springframework.boot:org.springframework.boot.gradle.plugin:3.5.13");
            assertThat(plugin.implementationCoordinates()).isEqualTo("org.springframework.boot:spring-boot-gradle-plugin:3.5.13");
        });
        Path localRepository = Path.of(result.localMavenRepository());
        assertThat(localRepository.resolve("org/springframework/boot/org.springframework.boot.gradle.plugin/3.5.13/org.springframework.boot.gradle.plugin-3.5.13.pom")).exists();
        assertThat(localRepository.resolve("org/springframework/boot/spring-boot-gradle-plugin/3.5.13/spring-boot-gradle-plugin-3.5.13.jar")).exists();
        assertThat(localRepository.resolve("io/spring/gradle/dependency-management-plugin/1.1.7/dependency-management-plugin-1.1.7.jar")).exists();
    }

    private byte[] pom(String xml) {
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private AnalyzerProperties.GradleProperties properties() {
        return new AnalyzerProperties.GradleProperties(
                true,
                Duration.ofSeconds(5),
                com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleExecutionMode.TOOLING_API,
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
                4096,
                100
        );
    }
}
