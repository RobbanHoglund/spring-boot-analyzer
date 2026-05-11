package com.robbanhoglund.springbootanalyzer.analyzer.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleAnalysisStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GradleModelReportParserTest {

    @TempDir Path tempDir;

    private final GradleModelReportParser parser = new GradleModelReportParser();

    @Test
    void parsesResolutionResultsAndConfigurationCounts() throws Exception {
        Path reportFile = tempDir.resolve("gradle-model.json");
        Files.writeString(
                reportFile,
                """
{
  "gradleVersion": "9.5.0",
  "projects": [{"path": ":", "name": "demo", "projectDir": "/tmp/demo"}],
  "plugins": [],
  "repositories": [{"projectPath": ":", "name": "MavenRepo", "type": "DefaultMavenArtifactRepository", "url": "https://repo.maven.apache.org/maven2/"}],
  "configurations": [{
    "projectPath": ":",
    "name": "runtimeClasspath",
    "resolvable": true,
    "consumable": false,
    "dependencyCount": 1,
    "declaredDependencyCount": 1,
    "allDependencyCount": 4,
    "extendsFrom": ["implementation", "runtimeOnly"]
  }],
  "declaredDependencies": [{
    "projectPath": ":",
    "configuration": "implementation",
    "notation": "org.springframework.boot:spring-boot-starter-web",
    "group": "org.springframework.boot",
    "artifact": "spring-boot-starter-web",
    "version": "3.5.13"
  }],
  "resolvedDependencies": [{
    "projectPath": ":",
    "configuration": "runtimeClasspath",
    "group": "org.springframework.boot",
    "artifact": "spring-boot-starter-web",
    "version": "3.5.13",
    "direct": true,
    "selectedReason": "Requested"
  }],
  "resolutionResults": [{
    "projectPath": ":",
    "configuration": "runtimeClasspath",
    "attempted": true,
    "successful": true,
    "fallbackUsed": false,
    "errorType": null,
    "errorMessage": null,
    "resolvedDependencyCount": 1
  }],
  "dependencyConflicts": [],
  "sourceSets": [{
    "projectPath": ":",
    "name": "main",
    "javaDirs": ["src/main/java"],
    "resourceDirs": ["src/main/resources"]
  }],
  "tasks": [],
  "javaToolchains": []
}
""");

        var analysis = parser.parse(reportFile, "TOOLING_API");

        assertThat(analysis.status()).isEqualTo(GradleAnalysisStatus.SUCCESS);
        assertThat(analysis.configurations())
                .singleElement()
                .satisfies(
                        configuration -> {
                            assertThat(configuration.declaredDependencyCount()).isEqualTo(1);
                            assertThat(configuration.allDependencyCount()).isEqualTo(4);
                            assertThat(configuration.extendsFrom())
                                    .containsExactly("implementation", "runtimeOnly");
                        });
        assertThat(analysis.resolutionResults())
                .singleElement()
                .satisfies(
                        result -> {
                            assertThat(result.configuration()).isEqualTo("runtimeClasspath");
                            assertThat(result.successful()).isTrue();
                            assertThat(result.resolvedDependencyCount()).isEqualTo(1);
                        });
        assertThat(analysis.sourceSets())
                .singleElement()
                .satisfies(
                        sourceSet -> {
                            assertThat(sourceSet.javaDirs()).containsExactly("src/main/java");
                            assertThat(sourceSet.resourceDirs())
                                    .containsExactly("src/main/resources");
                        });
    }
}
