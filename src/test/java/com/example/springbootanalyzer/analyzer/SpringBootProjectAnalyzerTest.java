package com.example.springbootanalyzer.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.springbootanalyzer.analyzer.configuration.ConfigurationAnalyzer;
import com.example.springbootanalyzer.analyzer.configuration.ConfigurationFileScanner;
import com.example.springbootanalyzer.analyzer.configuration.ConfigurationPropertiesClassAnalyzer;
import com.example.springbootanalyzer.analyzer.configuration.PropertiesFileParser;
import com.example.springbootanalyzer.analyzer.configuration.PropertyNameNormalizer;
import com.example.springbootanalyzer.analyzer.configuration.PropertyReferenceAnalyzer;
import com.example.springbootanalyzer.analyzer.configuration.SensitivePropertyValueRedactor;
import com.example.springbootanalyzer.analyzer.configuration.SpringConfigurationMetadataCatalog;
import com.example.springbootanalyzer.analyzer.configuration.YamlConfigurationParser;
import com.example.springbootanalyzer.analyzer.http.HttpSurfaceAnalyzer;
import com.example.springbootanalyzer.analyzer.model.FindingSeverity;
import com.example.springbootanalyzer.analyzer.runtime.RuntimeStackAnalyzer;
import com.example.springbootanalyzer.git.GitRepositoryReference;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpringBootProjectAnalyzerTest {

    private final PropertyNameNormalizer propertyNameNormalizer = new PropertyNameNormalizer();
    private final ConfigurationAnalyzer configurationAnalyzer = new ConfigurationAnalyzer(
            new ConfigurationFileScanner(),
            new PropertiesFileParser(),
            new YamlConfigurationParser(),
            new SpringConfigurationMetadataCatalog(),
            new ConfigurationPropertiesClassAnalyzer(propertyNameNormalizer),
            new PropertyReferenceAnalyzer(propertyNameNormalizer),
            new SensitivePropertyValueRedactor(),
            propertyNameNormalizer
    );
    private final SpringBootProjectAnalyzer analyzer =
            new SpringBootProjectAnalyzer(
                    new BuildFileAnalyzer(),
                    new JavaSourceAnalyzer(),
                    configurationAnalyzer,
                    new RuntimeStackAnalyzer(),
                    new HttpSurfaceAnalyzer()
            );

    @TempDir
    Path tempDir;

    @Test
    void reportsComponentsOutsideMainApplicationPackage() throws IOException {
        Files.writeString(tempDir.resolve("build.gradle"), """
                plugins {
                    id 'org.springframework.boot' version '3.5.13'
                }

                dependencies {
                    implementation 'org.springframework.boot:spring-boot-starter-web'
                }
                """);

        Path mainPackage = Files.createDirectories(tempDir.resolve("src/main/java/com/example/demo"));
        Files.writeString(mainPackage.resolve("DemoApplication.java"), """
                package com.example.demo;

                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class DemoApplication {
                }
                """);
        Files.writeString(Files.createDirectories(tempDir.resolve("src/main/java/com/example/demo/service"))
                .resolve("GreetingService.java"), """
                package com.example.demo.service;

                import org.springframework.stereotype.Service;

                @Service
                public class GreetingService {
                }
                """);
        Files.writeString(Files.createDirectories(tempDir.resolve("src/main/java/com/example/external"))
                .resolve("ExternalComponent.java"), """
                package com.example.external;

                import org.springframework.stereotype.Component;

                @Component
                public class ExternalComponent {
                }
                """);

        var result = analyzer.analyze(
                new GitRepositoryReference("https://github.com/example/demo.git", "main"),
                tempDir,
                "workspace-123"
        );

        assertThat(result.mainApplicationClasses()).containsExactly("com.example.demo.DemoApplication");
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
        Path sourceRoot = Files.createDirectories(tempDir.resolve("src/main/java/com/example/demo"));
        Files.writeString(sourceRoot.resolve("GreetingService.java"), """
                package com.example.demo;

                import org.springframework.stereotype.Service;

                @Service
                public class GreetingService {
                }
                """);

        var result = analyzer.analyze(
                new GitRepositoryReference("https://github.com/example/demo.git", null),
                tempDir,
                "workspace-456"
        );

        assertThat(result.mainApplicationClasses()).isEmpty();
        assertThat(result.findings())
                .extracting(finding -> finding.message())
                .anyMatch(message -> message.contains("No @SpringBootApplication class was found"));
    }
}
