package com.robbanhoglund.springbootanalyzer.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import com.robbanhoglund.springbootanalyzer.analyzer.configuration.ConfigurationAnalyzer;
import com.robbanhoglund.springbootanalyzer.analyzer.configuration.ConfigurationFileScanner;
import com.robbanhoglund.springbootanalyzer.analyzer.configuration.ConfigurationPropertiesClassAnalyzer;
import com.robbanhoglund.springbootanalyzer.analyzer.configuration.PropertiesFileParser;
import com.robbanhoglund.springbootanalyzer.analyzer.configuration.PropertyNameNormalizer;
import com.robbanhoglund.springbootanalyzer.analyzer.configuration.PropertyReferenceAnalyzer;
import com.robbanhoglund.springbootanalyzer.analyzer.configuration.SensitivePropertyValueRedactor;
import com.robbanhoglund.springbootanalyzer.analyzer.configuration.SpringConfigurationMetadataCatalog;
import com.robbanhoglund.springbootanalyzer.analyzer.configuration.YamlConfigurationParser;
import com.robbanhoglund.springbootanalyzer.analyzer.gradle.GradleCommandBuilder;
import com.robbanhoglund.springbootanalyzer.analyzer.gradle.GradleExecutableLocator;
import com.robbanhoglund.springbootanalyzer.analyzer.gradle.GradleExecutionService;
import com.robbanhoglund.springbootanalyzer.analyzer.gradle.GradleFailureClassifier;
import com.robbanhoglund.springbootanalyzer.analyzer.gradle.GradleJavaCompatibilityService;
import com.robbanhoglund.springbootanalyzer.analyzer.gradle.GradleModelAnalyzer;
import com.robbanhoglund.springbootanalyzer.analyzer.gradle.GradleModelReportParser;
import com.robbanhoglund.springbootanalyzer.analyzer.gradle.GradlePluginResolutionFailureParser;
import com.robbanhoglund.springbootanalyzer.analyzer.gradle.GradleSafetyPolicy;
import com.robbanhoglund.springbootanalyzer.analyzer.gradle.GradleSettingsPluginScanner;
import com.robbanhoglund.springbootanalyzer.analyzer.gradle.GradleToolingApiExecutionService;
import com.robbanhoglund.springbootanalyzer.analyzer.gradle.plugin.GradleCorePluginDetector;
import com.robbanhoglund.springbootanalyzer.analyzer.gradle.plugin.GradlePluginDeclarationScanner;
import com.robbanhoglund.springbootanalyzer.analyzer.gradle.plugin.GradlePluginResolutionBridge;
import com.robbanhoglund.springbootanalyzer.analyzer.gradle.plugin.GradleVersionCatalogPluginScanner;
import com.robbanhoglund.springbootanalyzer.analyzer.http.HttpSurfaceAnalyzer;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingSeverity;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleExecutionMode;
import com.robbanhoglund.springbootanalyzer.analyzer.runtime.RuntimeStackAnalyzer;
import com.robbanhoglund.springbootanalyzer.config.AnalyzerProperties;
import com.robbanhoglund.springbootanalyzer.git.GitRepositoryReference;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpringBootProjectAnalyzerTest {

    private final PropertyNameNormalizer propertyNameNormalizer = new PropertyNameNormalizer();
    private final ConfigurationAnalyzer configurationAnalyzer =
            new ConfigurationAnalyzer(
                    new ConfigurationFileScanner(),
                    new PropertiesFileParser(),
                    new YamlConfigurationParser(),
                    new SpringConfigurationMetadataCatalog(),
                    new ConfigurationPropertiesClassAnalyzer(propertyNameNormalizer),
                    new PropertyReferenceAnalyzer(propertyNameNormalizer),
                    new SensitivePropertyValueRedactor(),
                    propertyNameNormalizer);
    private final GradleJavaCompatibilityService gradleJavaCompatibilityService =
            new GradleJavaCompatibilityService();
    private final GradleFailureClassifier gradleFailureClassifier =
            new GradleFailureClassifier(new GradlePluginResolutionFailureParser());
    private final SpringBootProjectAnalyzer analyzer =
            new SpringBootProjectAnalyzer(
                    new BuildFileAnalyzer(),
                    new JavaSourceAnalyzer(),
                    configurationAnalyzer,
                    new GradleModelAnalyzer(
                            new GradleSafetyPolicy(gradleJavaCompatibilityService),
                            gradleJavaCompatibilityService,
                            new GradleToolingApiExecutionService(
                                    gradleJavaCompatibilityService, gradleFailureClassifier),
                            new GradleExecutionService(
                                    new GradleCommandBuilder(),
                                    new GradleExecutableLocator(),
                                    gradleJavaCompatibilityService,
                                    gradleFailureClassifier),
                            new GradleModelReportParser(),
                            new GradleSettingsPluginScanner(),
                            new GradlePluginDeclarationScanner(
                                    new GradleVersionCatalogPluginScanner()),
                            new GradlePluginResolutionBridge(new GradleCorePluginDetector())),
                    new RuntimeStackAnalyzer(),
                    new HttpSurfaceAnalyzer(),
                    new com.robbanhoglund.springbootanalyzer.analyzer.scheduling
                            .SchedulingAnalyzer(),
                    new com.robbanhoglund.springbootanalyzer.analyzer.messaging.MessagingAnalyzer(),
                    new StaticPracticeFindingAnalyzer(),
                    new ConfigurationFindingAnalyzer(),
                    new ObservabilityFindingAnalyzer(),
                    new TestingPracticeFindingAnalyzer(),
                    new CachingPracticeFindingAnalyzer(),
                    new ObservabilityGapFindingAnalyzer(),
                    new TransactionPracticeFindingAnalyzer(),
                    new SecurityPracticeFindingAnalyzer(),
                    new ScalabilityPracticeFindingAnalyzer(),
                    new MigrationPracticeFindingAnalyzer(),
                    new AnalyzerProperties(
                            Path.of("."),
                            true,
                            false,
                            new AnalyzerProperties.ScheduledWorkspaceCleanupProperties(
                                    true, Duration.ofDays(7), 4),
                            new AnalyzerProperties.GradleProperties(
                                    false,
                                    null,
                                    GradleExecutionMode.TOOLING_API,
                                    "9.5.0",
                                    Path.of(
                                            System.getProperty("java.io.tmpdir"),
                                            "spring-boot-analyzer-gradle-distributions"),
                                    java.util.List.of(),
                                    null,
                                    null,
                                    true,
                                    java.util.List.of("https://plugins.gradle.org/m2/"),
                                    true,
                                    false,
                                    true,
                                    false,
                                    true,
                                    false,
                                    false,
                                    new AnalyzerProperties.SettingsPluginWorkaroundProperties(
                                            false, false, java.util.List.of(), 1),
                                    new AnalyzerProperties.PluginResolutionBridgeProperties(
                                            true,
                                            true,
                                            true,
                                            "Spring Boot Analyzer plugin cache",
                                            java.util.List.of(
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
                                    0,
                                    0)));

    @TempDir Path tempDir;

    @Test
    void reportsComponentsOutsideMainApplicationPackage() throws IOException {
        Files.writeString(
                tempDir.resolve("build.gradle"),
                """
                plugins {
                    id 'org.springframework.boot' version '3.5.13'
                }

                dependencies {
                    implementation 'org.springframework.boot:spring-boot-starter-web'
                }
                """);

        Path mainPackage =
                Files.createDirectories(tempDir.resolve("src/main/java/com/example/demo"));
        Files.writeString(
                mainPackage.resolve("DemoApplication.java"),
                """
                package com.example.demo;

                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class DemoApplication {
                }
                """);
        Files.writeString(
                Files.createDirectories(tempDir.resolve("src/main/java/com/example/demo/service"))
                        .resolve("GreetingService.java"),
                """
                package com.example.demo.service;

                import org.springframework.stereotype.Service;

                @Service
                public class GreetingService {
                }
                """);
        Files.writeString(
                Files.createDirectories(tempDir.resolve("src/main/java/com/example/external"))
                        .resolve("ExternalComponent.java"),
                """
                package com.example.external;

                import org.springframework.stereotype.Component;

                @Component
                public class ExternalComponent {
                }
                """);

        var result =
                analyzer.analyze(
                        new GitRepositoryReference("https://github.com/example/demo.git", "main"),
                        tempDir,
                        "workspace-123");

        assertThat(result.mainApplicationClasses())
                .containsExactly("com.example.demo.DemoApplication");
        assertThat(result.detectedComponents()).hasSize(3);
        assertThat(result.findings())
                .extracting(finding -> finding.severity())
                .contains(FindingSeverity.WARNING);
        assertThat(result.findings())
                .extracting(finding -> finding.message())
                .anyMatch(message -> message.contains("component scanning issues"));
        assertThat(result.configurationAnalysis()).isNotNull();
        assertThat(result.runtimeStackAnalysis()).isNotNull();
        assertThat(result.httpSurfaceAnalysis()).isNotNull();
    }

    @Test
    void warnsWhenNoSpringBootApplicationClassIsFound() throws IOException {
        Path sourceRoot =
                Files.createDirectories(tempDir.resolve("src/main/java/com/example/demo"));
        Files.writeString(
                sourceRoot.resolve("GreetingService.java"),
                """
                package com.example.demo;

                import org.springframework.stereotype.Service;

                @Service
                public class GreetingService {
                }
                """);

        var result =
                analyzer.analyze(
                        new GitRepositoryReference("https://github.com/example/demo.git", null),
                        tempDir,
                        "workspace-456");

        assertThat(result.mainApplicationClasses()).isEmpty();
        assertThat(result.findings())
                .extracting(finding -> finding.message())
                .anyMatch(message -> message.contains("No @SpringBootApplication class was found"));
    }
}
