package com.robbanhoglund.springbootanalyzer.analyzer.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleExecutionMode;
import com.robbanhoglund.springbootanalyzer.config.AnalyzerProperties;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GradleInitScriptGenerationTest {

    @TempDir Path tempDir;

    @Test
    void generatedInitScriptInjectsPluginRepositories() throws Exception {
        AnalyzerProperties.GradleProperties properties =
                new AnalyzerProperties.GradleProperties(
                        true,
                        Duration.ofSeconds(5),
                        GradleExecutionMode.TOOLING_API,
                        "9.5.0",
                        tempDir.resolve("gradle-cache"),
                        List.of(),
                        null,
                        null,
                        true,
                        List.of(
                                "https://plugins.gradle.org/m2/",
                                "https://repo.example.com/plugins"),
                        true,
                        false,
                        true,
                        false,
                        true,
                        false,
                        false,
                        new AnalyzerProperties.SettingsPluginWorkaroundProperties(
                                false, false, List.of(), 1),
                        new AnalyzerProperties.PluginResolutionBridgeProperties(
                                true,
                                true,
                                true,
                                "Spring Boot Analyzer plugin cache",
                                List.of(
                                        "https://plugins.gradle.org/m2/",
                                        "https://repo.maven.apache.org/maven2/"),
                                Duration.ofSeconds(30),
                                50,
                                500,
                                false,
                                2),
                        false,
                        false,
                        true,
                        null,
                        null,
                        1024,
                        100);

        Path localPluginRepository = tempDir.resolve("plugin-cache/m2");
        java.nio.file.Files.createDirectories(localPluginRepository);
        GradleExecutionSupport.ExecutionFiles files =
                GradleExecutionSupport.prepareExecutionFiles(
                        tempDir, properties, localPluginRepository);
        String script = java.nio.file.Files.readString(files.initScript());

        assertThat(script).contains("beforeSettings");
        assertThat(script).contains("url = uri(\"file:/");
        assertThat(script).contains(localPluginRepository.toUri().toASCIIString());
        assertThat(script).contains("gradlePluginPortal()");
        assertThat(script).contains("https://plugins.gradle.org/m2/");
        assertThat(script).contains("https://repo.example.com/plugins");
        assertThat(script).contains("mavenCentral()");
        assertThat(script).contains("allprojects {");
        assertThat(script).doesNotContain("uri('C:\\");
        assertThat(script).doesNotContain("uri(\"C:\\");
        assertThat(script).contains("tasks.register('springBootAnalyzerModel')");
        assertThat(script).contains("final Closure<String> sbaSanitizeValue");
        assertThat(script).contains("final Closure<String> sbaRelativePath");
        assertThat(script).contains("final Closure<String> sbaSelectionReason");
        assertThat(script).contains("final Closure<String> sbaModuleKey");
        assertThat(script).contains("sbaSanitizeValue.call(");
        assertThat(script).contains("report.resolutionResults << [");
        assertThat(script).contains("configuration.incoming.resolutionResult");
        assertThat(script).contains("configuration.incoming.artifactView");
        assertThat(script).contains("lenient(true)");
        assertThat(script).contains("artifacts.artifacts.each");
        assertThat(script).contains("UnresolvedDependencyResult");
        assertThat(script).contains("dep instanceof ResolvedDependencyResult");
        assertThat(script).contains("id instanceof ModuleComponentIdentifier");
        assertThat(script)
                .contains(
                        "configuration.resolvedConfiguration.lenientConfiguration.allModuleDependencies");
        assertThat(script).contains("declaredDependencyCount");
        assertThat(script).contains("allDependencyCount");
        assertThat(script).contains("errorType: 'DEPENDENCY_RESOLUTION_FAILED'");
        assertThat(script).doesNotContain("sanitizeValue(url)");
        assertThat(script).doesNotContain("sanitizeValue(repo");
        assertThat(script).doesNotContain("Boolean(");
        assertThat(script).doesNotContain("new Boolean(");
    }

    @Test
    void initScriptValidationRejectsUnsafeWindowsPaths() {
        String script = "maven { url = uri('C:\\\\Users\\\\robba\\\\plugin-cache\\\\m2') }";

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> GradleExecutionSupport.validateInitScript(script))
                .isInstanceOf(InvalidGradleInitScriptException.class)
                .hasMessageContaining("unsafe Windows path");
    }
}
