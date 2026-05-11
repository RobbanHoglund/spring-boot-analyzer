package com.robbanhoglund.springbootanalyzer.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildTool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildFileAnalyzerTest {

    private final BuildFileAnalyzer analyzer = new BuildFileAnalyzer();

    @TempDir Path tempDir;

    @Test
    void detectsGradleSpringBootProject() throws IOException {
        Files.writeString(
                tempDir.resolve("build.gradle"),
                """
                plugins {
                    id 'org.springframework.boot' version '3.5.13'
                }

                java {
                    toolchain {
                        languageVersion = JavaLanguageVersion.of(25)
                    }
                }

                dependencies {
                    implementation 'org.springframework.boot:spring-boot-starter-web'
                    implementation 'com.example:library:1.0.0'
                }
                """);

        var buildInfo = analyzer.analyze(tempDir);

        assertThat(buildInfo.buildTool()).isEqualTo(BuildTool.GRADLE);
        assertThat(buildInfo.springBootDetected()).isTrue();
        assertThat(buildInfo.javaVersionHint()).isEqualTo("25");
        assertThat(buildInfo.dependencies())
                .contains("org.springframework.boot:spring-boot-starter-web");
        assertThat(buildInfo.dependencies()).contains("com.example:library:1.0.0");
        assertThat(buildInfo.springBootVersion()).isEqualTo("3.5.13");
        assertThat(buildInfo.springBootVersionSource()).isEqualTo("Gradle plugins");
        assertThat(buildInfo.springBootVersionConfidence()).isEqualTo("HIGH");
    }

    @Test
    void detectsMavenSpringBootProject() throws IOException {
        Files.writeString(
                tempDir.resolve("pom.xml"),
                """
                <project>
                    <properties>
                        <java.version>25</java.version>
                    </properties>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                            </plugin>
                        </plugins>
                    </build>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-web</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);

        var buildInfo = analyzer.analyze(tempDir);

        assertThat(buildInfo.buildTool()).isEqualTo(BuildTool.MAVEN);
        assertThat(buildInfo.springBootDetected()).isTrue();
        assertThat(buildInfo.javaVersionHint()).isEqualTo("25");
        assertThat(buildInfo.dependencies())
                .contains("org.springframework.boot:spring-boot-starter-web");
    }

    @Test
    void detectsSpringBootVersionFromMavenParent() throws IOException {
        Files.writeString(
                tempDir.resolve("pom.xml"),
                """
                <project>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>3.5.13</version>
                    </parent>
                </project>
                """);

        var buildInfo = analyzer.analyze(tempDir);

        assertThat(buildInfo.springBootVersion()).isEqualTo("3.5.13");
        assertThat(buildInfo.springBootVersionSource()).isEqualTo("Maven parent");
    }

    @Test
    void detectsSpringBootVersionFromMavenBom() throws IOException {
        Files.writeString(
                tempDir.resolve("pom.xml"),
                """
                <project>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-dependencies</artifactId>
                                <version>3.4.8</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);

        var buildInfo = analyzer.analyze(tempDir);

        assertThat(buildInfo.springBootVersion()).isEqualTo("3.4.8");
        assertThat(buildInfo.springBootVersionSource()).isEqualTo("Maven BOM");
    }
}
