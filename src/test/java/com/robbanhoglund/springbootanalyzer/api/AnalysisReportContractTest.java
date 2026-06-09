package com.robbanhoglund.springbootanalyzer.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.robbanhoglund.springbootanalyzer.analyzer.BuildFileAnalyzer;
import com.robbanhoglund.springbootanalyzer.analyzer.CachingPracticeFindingAnalyzer;
import com.robbanhoglund.springbootanalyzer.analyzer.ConfigurationFindingAnalyzer;
import com.robbanhoglund.springbootanalyzer.analyzer.JavaSourceAnalyzer;
import com.robbanhoglund.springbootanalyzer.analyzer.MigrationPracticeFindingAnalyzer;
import com.robbanhoglund.springbootanalyzer.analyzer.ObservabilityFindingAnalyzer;
import com.robbanhoglund.springbootanalyzer.analyzer.ObservabilityGapFindingAnalyzer;
import com.robbanhoglund.springbootanalyzer.analyzer.ScalabilityPracticeFindingAnalyzer;
import com.robbanhoglund.springbootanalyzer.analyzer.SecurityPracticeFindingAnalyzer;
import com.robbanhoglund.springbootanalyzer.analyzer.SpringBootProjectAnalyzer;
import com.robbanhoglund.springbootanalyzer.analyzer.StaticPracticeFindingAnalyzer;
import com.robbanhoglund.springbootanalyzer.analyzer.TestingPracticeFindingAnalyzer;
import com.robbanhoglund.springbootanalyzer.analyzer.TransactionPracticeFindingAnalyzer;
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
import com.robbanhoglund.springbootanalyzer.analyzer.messaging.MessagingAnalyzer;
import com.robbanhoglund.springbootanalyzer.analyzer.model.AnalysisResult;
import com.robbanhoglund.springbootanalyzer.analyzer.model.DetectedClass;
import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingCategory;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingConfidence;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingRuntimeDetection;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingSeverity;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleExecutionMode;
import com.robbanhoglund.springbootanalyzer.analyzer.runtime.RuntimeStackAnalyzer;
import com.robbanhoglund.springbootanalyzer.analyzer.scheduling.SchedulingAnalyzer;
import com.robbanhoglund.springbootanalyzer.config.AnalyzerProperties;
import com.robbanhoglund.springbootanalyzer.git.GitRepositoryReference;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contract tests for the analysis report shape.
 *
 * <p>These tests verify that rule-based findings carry all fields that the frontend
 * and any future integration depend on: ruleId, severity, title, category,
 * runtimeDetection, confidence, whyBadPractice, possibleImpact, recommendation,
 * and evidence. They also verify that the component inventory, HTTP surface, and
 * configuration analysis carry their expected fields.
 *
 * <p>These are intentionally coarse-grained — they are not rule-accuracy tests.
 * They exist to catch accidental regressions where a field is dropped from the
 * response or serialisation is broken.
 */
class AnalysisReportContractTest {

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
                    new SchedulingAnalyzer(),
                    new MessagingAnalyzer(),
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
                                    Path.of(System.getProperty("java.io.tmpdir"), "gradle-dist"),
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
                                    new AnalyzerProperties.SettingsPluginWorkaroundProperties(
                                            false, false, List.of(), 1),
                                    new AnalyzerProperties.PluginResolutionBridgeProperties(
                                            true,
                                            true,
                                            true,
                                            "cache",
                                            List.of("https://plugins.gradle.org/m2/"),
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

    /**
     * Rule-based findings must carry all fields that the UI and downstream integrations
     * depend on. This test verifies the contract for findings produced by the full pipeline.
     */
    @Test
    void ruleBasedFindingsCarryAllRequiredContractFields() throws IOException {
        writeContractFixture(tempDir);

        AnalysisResult result =
                analyzer.analyze(
                        new GitRepositoryReference("https://github.com/example/demo.git", "main"),
                        tempDir,
                        "ws-contract-001");

        List<Finding> ruleFindings =
                result.findings().stream().filter(finding -> finding.ruleId() != null).toList();

        assertThat(ruleFindings)
                .as(
                        "Analysis should produce at least one rule-based finding from the contract"
                                + " fixture")
                .isNotEmpty();

        for (Finding finding : ruleFindings) {
            assertThat(finding.ruleId())
                    .as(
                            "ruleId must not be blank for rule-based findings (rule: %s)",
                            finding.ruleId())
                    .isNotBlank();
            assertThat(finding.title())
                    .as("title must be populated for rule %s", finding.ruleId())
                    .isNotBlank();
            assertThat(finding.severity())
                    .as("severity must not be null for rule %s", finding.ruleId())
                    .isNotNull()
                    .isIn(FindingSeverity.INFO, FindingSeverity.WARNING, FindingSeverity.ERROR);
            assertThat(finding.category())
                    .as("category must not be null for rule %s", finding.ruleId())
                    .isNotNull()
                    .isInstanceOf(FindingCategory.class);
            assertThat(finding.runtimeDetection())
                    .as("runtimeDetection must not be null for rule %s", finding.ruleId())
                    .isNotNull()
                    .isInstanceOf(FindingRuntimeDetection.class);
            assertThat(finding.confidence())
                    .as("confidence must not be null for rule %s", finding.ruleId())
                    .isNotNull()
                    .isInstanceOf(FindingConfidence.class);
            assertThat(finding.whyBadPractice())
                    .as("whyBadPractice must be populated for rule %s", finding.ruleId())
                    .isNotBlank();
            assertThat(finding.possibleImpact())
                    .as("possibleImpact must be populated for rule %s", finding.ruleId())
                    .isNotBlank();
            assertThat(finding.recommendation())
                    .as("recommendation must be populated for rule %s", finding.ruleId())
                    .isNotBlank();
            assertThat(finding.evidence())
                    .as("evidence must be populated for rule %s", finding.ruleId())
                    .isNotBlank();
            assertThat(finding.highlightRanges())
                    .as("highlightRanges must never be null (rule %s)", finding.ruleId())
                    .isNotNull();
            assertThat(finding.occurrences())
                    .as("occurrences must never be null (rule %s)", finding.ruleId())
                    .isNotNull();
            assertThat(finding.relatedSignals())
                    .as("relatedSignals must never be null (rule %s)", finding.ruleId())
                    .isNotNull();
        }
    }

    /**
     * The component inventory must carry the fields the UI uses for component-type
     * filtering, source-link navigation, and annotation display.
     */
    @Test
    void componentInventoryCarriesRequiredFields() throws IOException {
        writeContractFixture(tempDir);

        AnalysisResult result =
                analyzer.analyze(
                        new GitRepositoryReference("https://github.com/example/demo.git", "main"),
                        tempDir,
                        "ws-contract-002");

        assertThat(result.detectedComponents())
                .as("Contract fixture should produce at least one detected component")
                .isNotEmpty();

        for (DetectedClass component : result.detectedComponents()) {
            assertThat(component.fullyQualifiedClassName())
                    .as("fullyQualifiedClassName must be set for each detected component")
                    .isNotBlank();
            assertThat(component.simpleClassName())
                    .as("simpleClassName must be set for each detected component")
                    .isNotBlank();
            assertThat(component.componentType()).as("componentType must not be null").isNotNull();
        }
    }

    /**
     * The configuration analysis must carry the fields the UI uses for property
     * filtering (kind, name, sourceFile, value).
     */
    @Test
    void configurationAnalysisCarriesRequiredFields() throws IOException {
        writeContractFixture(tempDir);

        AnalysisResult result =
                analyzer.analyze(
                        new GitRepositoryReference("https://github.com/example/demo.git", "main"),
                        tempDir,
                        "ws-contract-003");

        assertThat(result.configurationAnalysis()).isNotNull();
        assertThat(result.configurationAnalysis().properties())
                .as("Contract fixture should produce at least one configuration property")
                .isNotEmpty();

        result.configurationAnalysis()
                .properties()
                .forEach(
                        property -> {
                            assertThat(property.name())
                                    .as("Property name must not be blank")
                                    .isNotBlank();
                            assertThat(property.kind())
                                    .as(
                                            "Property kind must not be null (name: %s)",
                                            property.name())
                                    .isNotNull();
                            assertThat(property.sourceFile())
                                    .as(
                                            "Property sourceFile must not be blank (name: %s)",
                                            property.name())
                                    .isNotBlank();
                        });
    }

    /**
     * The HTTP surface analysis must carry the fields the UI uses for endpoint display.
     */
    @Test
    void httpSurfaceAnalysisCarriesRequiredFields() throws IOException {
        writeContractFixture(tempDir);

        AnalysisResult result =
                analyzer.analyze(
                        new GitRepositoryReference("https://github.com/example/demo.git", "main"),
                        tempDir,
                        "ws-contract-004");

        assertThat(result.httpSurfaceAnalysis()).isNotNull();
        assertThat(result.httpSurfaceAnalysis().inboundEndpoints())
                .as("Contract fixture should produce at least one inbound endpoint")
                .isNotEmpty();

        result.httpSurfaceAnalysis()
                .inboundEndpoints()
                .forEach(
                        endpoint -> {
                            assertThat(endpoint.httpMethod())
                                    .as("Inbound endpoint httpMethod must not be blank")
                                    .isNotBlank();
                            assertThat(endpoint.path())
                                    .as("Inbound endpoint path must not be blank")
                                    .isNotBlank();
                        });
    }

    /**
     * Source file references in findings must be consistent: if sourceFile is set,
     * the path must not be an absolute OS path (it must be relative to the repository root).
     */
    @Test
    void findingSourceFilesAreRelativePaths() throws IOException {
        writeContractFixture(tempDir);

        AnalysisResult result =
                analyzer.analyze(
                        new GitRepositoryReference("https://github.com/example/demo.git", "main"),
                        tempDir,
                        "ws-contract-005");

        result.findings().stream()
                .filter(finding -> finding.sourceFile() != null)
                .forEach(
                        finding -> {
                            assertThat(finding.sourceFile())
                                    .as(
                                            "sourceFile must be relative, not an absolute OS path"
                                                    + " (rule: %s)",
                                            finding.ruleId())
                                    .doesNotStartWith("/")
                                    .doesNotContain(":\\");
                            if (finding.primaryLocation() != null
                                    && finding.primaryLocation().filePath() != null) {
                                assertThat(finding.primaryLocation().filePath())
                                        .as(
                                                "primaryLocation.filePath must be relative (rule:"
                                                        + " %s)",
                                                finding.ruleId())
                                        .doesNotStartWith("/")
                                        .doesNotContain(":\\");
                            }
                        });
    }

    // ---------------------------------------------------------------------------
    // Fixture helpers
    // ---------------------------------------------------------------------------

    private static void writeContractFixture(Path root) throws IOException {
        // Minimal Gradle build
        Files.writeString(
                root.resolve("build.gradle"),
                """
                plugins {
                    id 'org.springframework.boot' version '3.5.13'
                    id 'io.spring.dependency-management' version '1.1.7'
                }
                dependencies {
                    implementation 'org.springframework.boot:spring-boot-starter-web'
                    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
                }
                """);

        // Application config — includes a secret literal to ensure a SECURITY finding fires
        Files.createDirectories(root.resolve("src/main/resources"));
        Files.writeString(
                root.resolve("src/main/resources/application.properties"),
                """
                server.port=8080
                spring.datasource.url=jdbc:h2:mem:testdb
                spring.datasource.password=s3cr3t-literal
                spring.jpa.open-in-view=true
                """);

        // Main application class
        Path mainPackage = Files.createDirectories(root.resolve("src/main/java/com/example/demo"));
        Files.writeString(
                mainPackage.resolve("DemoApplication.java"),
                """
                package com.example.demo;
                import org.springframework.boot.autoconfigure.SpringBootApplication;
                @SpringBootApplication
                public class DemoApplication {}
                """);

        // REST controller — provides inbound HTTP surface, @RequestBody without @Valid
        Files.writeString(
                mainPackage.resolve("OrderController.java"),
                """
                package com.example.demo;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/orders")
                public class OrderController {
                    @PostMapping
                    public String create(@RequestBody OrderRequest request) {
                        return "ok";
                    }
                }
                """);
        Files.writeString(
                mainPackage.resolve("OrderRequest.java"),
                """
                package com.example.demo;
                public record OrderRequest(String customerId, int quantity) {}
                """);
    }
}
