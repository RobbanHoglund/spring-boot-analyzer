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
import com.example.springbootanalyzer.analyzer.model.BuildInfo;
import com.example.springbootanalyzer.analyzer.model.BuildTool;
import com.example.springbootanalyzer.analyzer.model.Finding;
import com.example.springbootanalyzer.analyzer.model.FindingCategory;
import com.example.springbootanalyzer.analyzer.model.FindingConfidence;
import com.example.springbootanalyzer.analyzer.model.FindingRuntimeDetection;
import com.example.springbootanalyzer.analyzer.model.FindingRules;
import com.example.springbootanalyzer.analyzer.model.FindingSeverity;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleAnalysisStatus;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleModelAnalysis;
import com.example.springbootanalyzer.analyzer.model.runtime.RuntimeStackAnalysis;
import com.example.springbootanalyzer.analyzer.model.runtime.WebStack;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StaticPracticeFindingAnalyzerTest {

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
    private final JavaSourceAnalyzer javaSourceAnalyzer = new JavaSourceAnalyzer();
    private final HttpSurfaceAnalyzer httpSurfaceAnalyzer = new HttpSurfaceAnalyzer();
    private final StaticPracticeFindingAnalyzer analyzer = new StaticPracticeFindingAnalyzer();

    @TempDir
    Path tempDir;

    @Test
    void emitsRichStaticFindingsForPrioritizedPracticeRisks() throws IOException {
        writeFixtureProject(tempDir);

        BuildInfo buildInfo = new BuildInfo(
                BuildTool.GRADLE,
                true,
                "25",
                List.of(
                        "org.springframework.boot:spring-boot-starter-web",
                        "org.springframework.boot:spring-boot-starter-data-jpa",
                        "org.flywaydb:flyway-core:11.20.3"
                ),
                "3.5.13",
                "build.gradle plugin",
                "HIGH"
        );

        var configurationResult = configurationAnalyzer.analyze(tempDir, buildInfo);
        var sourceAnalysis = javaSourceAnalyzer.analyze(tempDir);
        HttpSurfaceAnalyzer.Result httpResult = httpSurfaceAnalyzer.analyze(
                tempDir,
                configurationResult.configurationAnalysis(),
                buildInfo,
                WebStack.SERVLET_MVC
        );

        List<Finding> findings = analyzer.analyze(
                tempDir,
                buildInfo,
                configurationResult.configurationAnalysis(),
                GradleModelAnalysis.empty(GradleAnalysisStatus.NOT_REQUESTED, "TOOLING_API", List.of()),
                new RuntimeStackAnalysis("3.5.13", "build.gradle", "25", WebStack.SERVLET_MVC, "Static servlet signals", null, "com.example.demo.DemoApplication"),
                httpResult.httpSurfaceAnalysis(),
                sourceAnalysis.detectedClasses()
        );

        assertRichFinding(findings, FindingRules.SPRING_SECRET_MULTI_PROFILE.ruleId(), FindingCategory.SECURITY, FindingRuntimeDetection.ACTIVE_PROFILE_RUNTIME_MAY_DETECT);
        assertRichFinding(findings, FindingRules.SPRING_PROFILE_DRIFT.ruleId(), FindingCategory.PROFILE_DRIFT, FindingRuntimeDetection.ACTIVE_PROFILE_RUNTIME_MAY_DETECT);
        assertRichFinding(findings, FindingRules.SPRING_CONDITIONAL_VALUE_MISMATCH.ruleId(), FindingCategory.CONDITIONAL_BEAN, FindingRuntimeDetection.ACTIVE_PROFILE_RUNTIME_MAY_DETECT);
        assertRichFinding(findings, FindingRules.SPRING_FLYWAY_DDL_AUTO_MIX.ruleId(), FindingCategory.PERSISTENCE, FindingRuntimeDetection.NOT_NORMALLY_DETECTED);
        assertRichFinding(findings, FindingRules.SPRING_STARTUP_SIDE_EFFECT.ruleId(), FindingCategory.STARTUP, FindingRuntimeDetection.NOT_NORMALLY_DETECTED);
        assertRichFinding(findings, FindingRules.SPRING_SCHEDULED_SIDE_EFFECT.ruleId(), FindingCategory.SCHEDULING, FindingRuntimeDetection.NOT_NORMALLY_DETECTED);
        assertRichFinding(findings, FindingRules.SPRING_HTTP_CLIENT_NO_TIMEOUT.ruleId(), FindingCategory.HTTP, FindingRuntimeDetection.NOT_NORMALLY_DETECTED);
        assertRichFinding(findings, FindingRules.SPRING_TRANSACTION_SELF_INVOCATION.ruleId(), FindingCategory.TRANSACTION, FindingRuntimeDetection.NOT_NORMALLY_DETECTED);
        assertRichFinding(findings, FindingRules.SPRING_TRANSACTION_PRIVATE_METHOD.ruleId(), FindingCategory.TRANSACTION, FindingRuntimeDetection.NOT_NORMALLY_DETECTED);
        assertRichFinding(findings, FindingRules.SPRING_REQUEST_BODY_NO_VALID.ruleId(), FindingCategory.VALIDATION, FindingRuntimeDetection.NOT_NORMALLY_DETECTED);

        assertThat(findings)
                .extracting(Finding::target)
                .contains("trading.provider", "schema management");
        assertThat(findings)
                .allMatch(finding -> finding.message() == null || !finding.message().contains("super-secret"));
    }

    @Test
    void configurationAnalyzerProducesRedactedEducationalFindingsForSensitiveAndRiskyConfig() throws IOException {
        writeFixtureProject(tempDir);

        BuildInfo buildInfo = new BuildInfo(
                BuildTool.GRADLE,
                true,
                "25",
                List.of("org.springframework.boot:spring-boot-starter-data-jpa"),
                "3.5.13",
                "build.gradle plugin",
                "HIGH"
        );

        var result = configurationAnalyzer.analyze(tempDir, buildInfo);

        Finding secretFinding = result.findings().stream()
                .filter(finding -> FindingRules.SPRING_SECRET_LITERAL.ruleId().equals(finding.ruleId()))
                .findFirst()
                .orElseThrow();
        assertThat(secretFinding.category()).isEqualTo(FindingCategory.SECURITY);
        assertThat(secretFinding.runtimeDetection()).isEqualTo(FindingRuntimeDetection.NOT_NORMALLY_DETECTED);
        assertThat(secretFinding.confidence()).isEqualTo(FindingConfidence.HIGH);
        assertThat(secretFinding.whyBadPractice()).isNotBlank();
        assertThat(secretFinding.possibleImpact()).isNotBlank();
        assertThat(secretFinding.recommendation()).isNotBlank();
        assertThat(secretFinding.evidence()).contains("trading.api-secret").doesNotContain("super-secret");

        Finding riskyFinding = result.findings().stream()
                .filter(finding -> FindingRules.SPRING_RISKY_PROD_CONFIG.ruleId().equals(finding.ruleId()))
                .findFirst()
                .orElseThrow();
        assertThat(riskyFinding.category()).isEqualTo(FindingCategory.CONFIGURATION);
        assertThat(riskyFinding.whyBadPractice()).isNotBlank();
        assertThat(riskyFinding.recommendation()).isNotBlank();
    }

    @Test
    void doesNotTreatHttpClientBuildersAsConstructorSideEffects() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/resources"));
        Path sourceRoot = Files.createDirectories(tempDir.resolve("src/main/java/com/example/demo"));
        Files.writeString(sourceRoot.resolve("ClientHolder.java"), """
                package com.example.demo;

                import org.springframework.stereotype.Component;
                import org.springframework.web.client.RestClient;
                import org.springframework.web.reactive.function.client.WebClient;

                @Component
                class ClientHolder {

                    private final RestClient restClient;
                    private final WebClient webClient;

                    ClientHolder() {
                        this.restClient = RestClient.builder().baseUrl("https://api.example.com").build();
                        this.webClient = WebClient.builder().baseUrl("https://api.example.com").build();
                    }
                }
                """);

        List<Finding> findings = analyzeStaticPractice(tempDir, emptyBuildInfo(List.of("org.springframework.boot:spring-boot-starter-web")));

        assertThat(findings).noneMatch(finding ->
                FindingRules.SPRING_STARTUP_SIDE_EFFECT.ruleId().equals(finding.ruleId())
                        && (finding.target() != null && finding.target().contains("ClientHolder"))
        );
    }

    @Test
    void flagsRealConstructorSideEffectsAndFiltersValidationNoise() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/resources"));
        Path sourceRoot = Files.createDirectories(tempDir.resolve("src/main/java/com/example/demo"));
        Files.writeString(sourceRoot.resolve("StartupBean.java"), """
                package com.example.demo;

                import java.nio.file.Files;
                import java.nio.file.Path;
                import org.springframework.stereotype.Component;
                import org.springframework.web.client.RestClient;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RestController;
                import jakarta.validation.constraints.NotBlank;

                @Component
                class StartupBean {
                    StartupBean() throws Exception {
                        RestClient.create().get().uri("https://api.example.com").retrieve().body(String.class);
                        Files.writeString(Path.of("marker.txt"), "hello");
                    }
                }

                @RestController
                class ApiController {
                    @PostMapping("/typed")
                    void typed(@RequestBody CreateRequest request) {
                    }

                    @PostMapping("/raw")
                    void raw(@RequestBody java.util.Map<String, Object> payload) {
                    }
                }

                record CreateRequest(@NotBlank String symbol, int quantity) {
                }
                """);

        List<Finding> findings = analyzeStaticPractice(tempDir, emptyBuildInfo(List.of("org.springframework.boot:spring-boot-starter-web")));

        assertThat(findings).anyMatch(finding ->
                FindingRules.SPRING_STARTUP_SIDE_EFFECT.ruleId().equals(finding.ruleId())
                        && finding.evidence() != null
                        && finding.evidence().contains("outbound HTTP execution")
        );
        assertThat(findings).anyMatch(finding ->
                FindingRules.SPRING_REQUEST_BODY_NO_VALID.ruleId().equals(finding.ruleId())
                        && finding.severity() != null
                        && finding.severity().name().equals("INFO")
                        && finding.evidence() != null
                        && finding.evidence().contains("CreateRequest")
        );
        assertThat(findings).noneMatch(finding ->
                FindingRules.SPRING_REQUEST_BODY_NO_VALID.ruleId().equals(finding.ruleId())
                        && finding.evidence() != null
                        && finding.evidence().contains("payload")
        );
    }

    @Test
    void avoidsMissingTransactionBoundaryForSingleRepositoryWrite() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/resources"));
        Path sourceRoot = Files.createDirectories(tempDir.resolve("src/main/java/com/example/demo"));
        Files.writeString(sourceRoot.resolve("RepositoryAndService.java"), """
                package com.example.demo;

                import org.springframework.stereotype.Repository;
                import org.springframework.stereotype.Service;

                @Repository
                class OrderRepository {
                    void upsert() {
                        update();
                    }

                    void update() {
                    }
                }

                @Service
                class OrderService {
                    private final OrderRepository orderRepository = new OrderRepository();

                    void store() {
                        orderRepository.upsert();
                    }
                }
                """);

        List<Finding> findings = analyzeStaticPractice(tempDir, emptyBuildInfo(List.of("org.springframework.boot:spring-boot-starter-data-jpa")));

        assertThat(findings).noneMatch(finding ->
                FindingRules.SPRING_TRANSACTION_MISSING_BOUNDARY.ruleId().equals(finding.ruleId())
        );
    }

    @Test
    void ignoresTransactionTemplateAndFlagsMultiWriteWithoutBoundary() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/resources"));
        Path sourceRoot = Files.createDirectories(tempDir.resolve("src/main/java/com/example/demo"));
        Files.writeString(sourceRoot.resolve("TransactionalExamples.java"), """
                package com.example.demo;

                import org.springframework.jdbc.core.JdbcTemplate;
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.support.TransactionTemplate;

                @Service
                class TransactionalExamples {
                    private final JdbcTemplate jdbcTemplate = null;
                    private final TransactionTemplate transactionTemplate = null;

                    void withTemplate() {
                        transactionTemplate.execute(status -> {
                            jdbcTemplate.update("update a set v=1");
                            jdbcTemplate.update("update b set v=2");
                            return null;
                        });
                    }

                    void withoutBoundary() {
                        jdbcTemplate.update("update a set v=1");
                        jdbcTemplate.update("update b set v=2");
                    }
                }
                """);

        List<Finding> findings = analyzeStaticPractice(tempDir, emptyBuildInfo(List.of("org.springframework.boot:spring-boot-starter-jdbc")));

        assertThat(findings).noneMatch(finding ->
                FindingRules.SPRING_TRANSACTION_MISSING_BOUNDARY.ruleId().equals(finding.ruleId())
                        && finding.target() != null
                        && finding.target().contains("withTemplate")
        );
        assertThat(findings).anyMatch(finding ->
                FindingRules.SPRING_TRANSACTION_MISSING_BOUNDARY.ruleId().equals(finding.ruleId())
                        && finding.severity() != null
                        && finding.severity().name().equals("INFO")
                        && finding.target() != null
                        && finding.target().contains("withoutBoundary")
        );
    }

    @Test
    void doesNotFlagTransactionalMultiWriteMethod() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/resources"));
        Path sourceRoot = Files.createDirectories(tempDir.resolve("src/main/java/com/example/demo"));
        Files.writeString(sourceRoot.resolve("TransactionalService.java"), """
                package com.example.demo;

                import org.springframework.jdbc.core.JdbcTemplate;
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;

                @Service
                class TransactionalService {
                    private final JdbcTemplate jdbcTemplate = null;

                    @Transactional
                    void updateBoth() {
                        jdbcTemplate.update("update a set v=1");
                        jdbcTemplate.update("update b set v=2");
                    }
                }
                """);

        List<Finding> findings = analyzeStaticPractice(tempDir, emptyBuildInfo(List.of("org.springframework.boot:spring-boot-starter-jdbc")));

        assertThat(findings).noneMatch(finding ->
                FindingRules.SPRING_TRANSACTION_MISSING_BOUNDARY.ruleId().equals(finding.ruleId())
        );
    }

    @Test
    void respectsValidRequestBodyAnnotations() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/resources"));
        Path sourceRoot = Files.createDirectories(tempDir.resolve("src/main/java/com/example/demo"));
        Files.writeString(sourceRoot.resolve("ValidatedController.java"), """
                package com.example.demo;

                import jakarta.validation.Valid;
                import jakarta.validation.constraints.NotBlank;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                class ValidatedController {
                    @PostMapping("/typed")
                    void typed(@Valid @RequestBody CreateRequest request) {
                    }

                    @PostMapping("/raw")
                    void raw(@RequestBody String payload) {
                    }
                }

                record CreateRequest(@NotBlank String symbol) {
                }
                """);

        List<Finding> findings = analyzeStaticPractice(tempDir, emptyBuildInfo(List.of("org.springframework.boot:spring-boot-starter-web")));

        assertThat(findings).noneMatch(finding ->
                FindingRules.SPRING_REQUEST_BODY_NO_VALID.ruleId().equals(finding.ruleId())
        );
    }

    @Test
    void doesNotTreatRegexCompilationAsConstructorSideEffect() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/resources"));
        Path sourceRoot = Files.createDirectories(tempDir.resolve("src/main/java/com/example/demo"));
        Files.writeString(sourceRoot.resolve("RegexHolder.java"), """
                package com.example.demo;

                import java.util.regex.Pattern;
                import org.springframework.stereotype.Component;

                @Component
                class RegexHolder {
                    private final Pattern pattern;

                    RegexHolder() {
                        this.pattern = Pattern.compile("^[A-Z]+$");
                    }
                }
                """);

        List<Finding> findings = analyzeStaticPractice(tempDir, emptyBuildInfo(List.of("org.springframework.boot:spring-boot-starter-web")));

        assertThat(findings).noneMatch(finding ->
                FindingRules.SPRING_STARTUP_SIDE_EFFECT.ruleId().equals(finding.ruleId())
                        && finding.target() != null
                        && finding.target().contains("RegexHolder")
        );
    }

    @Test
    void flagsEmptyCatchBlocksAndCommentOnlyCatchBlocks() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/resources"));
        Path sourceRoot = Files.createDirectories(tempDir.resolve("src/main/java/com/example/demo"));
        Files.writeString(sourceRoot.resolve("EmptyCatchExample.java"), """
                package com.example.demo;

                import java.io.IOException;

                class EmptyCatchExample {
                    void run() {
                        try {
                            work();
                        } catch (Exception e) {
                        }
                    }

                    void commentOnly() {
                        try {
                            work();
                        } catch (IOException e) {
                            // TODO
                        }
                    }

                    void work() {
                    }
                }
                """);

        List<Finding> findings = analyzeStaticPractice(tempDir, emptyBuildInfo(List.of()));

        assertThat(findings)
                .filteredOn(finding -> FindingRules.SPRING_EMPTY_CATCH_BLOCK.ruleId().equals(finding.ruleId()))
                .hasSize(2)
                .allSatisfy(finding -> {
                    assertThat(finding.severity()).isEqualTo(FindingSeverity.WARNING);
                    assertThat(finding.category()).isEqualTo(FindingCategory.EXCEPTION_HANDLING);
                    assertThat(finding.runtimeDetection()).isEqualTo(FindingRuntimeDetection.NOT_NORMALLY_DETECTED);
                    assertThat(finding.confidence()).isEqualTo(FindingConfidence.HIGH);
                    assertThat(finding.evidence()).contains("Catch block for");
                    assertThat(finding.line()).isNotNull();
                });
    }

    @Test
    void skipsIntentionalIgnoredCleanupCatch() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/resources"));
        Path sourceRoot = Files.createDirectories(tempDir.resolve("src/main/java/com/example/demo"));
        Files.writeString(sourceRoot.resolve("CleanupExample.java"), """
                package com.example.demo;

                import java.io.IOException;

                class CleanupExample {
                    void closeQuietly() {
                        try {
                            work();
                        } catch (IOException ignored) {
                            // Best effort cleanup; close failure is ignored intentionally.
                        }
                    }

                    void work() {
                    }
                }
                """);

        List<Finding> findings = analyzeStaticPractice(tempDir, emptyBuildInfo(List.of()));

        assertThat(findings).noneMatch(finding ->
                FindingRules.SPRING_EMPTY_CATCH_BLOCK.ruleId().equals(finding.ruleId())
        );
    }

    @Test
    void flagsSwallowedFallbackInterruptedAndPrintStackTracePatterns() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/resources"));
        Path sourceRoot = Files.createDirectories(tempDir.resolve("src/main/java/com/example/demo"));
        Files.writeString(sourceRoot.resolve("OrderService.java"), """
                package com.example.demo;

                import java.util.Optional;
                import org.springframework.stereotype.Service;

                @Service
                class OrderService {
                    Optional<String> load() {
                        try {
                            return Optional.of("ok");
                        } catch (Exception e) {
                            return Optional.empty();
                        }
                    }

                    void waitForSignal() {
                        try {
                            Thread.sleep(5);
                        } catch (InterruptedException e) {
                            logWarn(e);
                        }
                    }

                    void printFailure() {
                        try {
                            fail();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }

                    void fail() {
                    }

                    void logWarn(Exception e) {
                    }
                }
                """);

        List<Finding> findings = analyzeStaticPractice(tempDir, emptyBuildInfo(List.of()));

        assertThat(findings).anyMatch(finding ->
                FindingRules.SPRING_SWALLOWED_EXCEPTION_FALLBACK.ruleId().equals(finding.ruleId())
                        && finding.severity() == FindingSeverity.WARNING
                        && "com.example.demo.OrderService#load".equals(finding.target())
        );
        assertThat(findings).anyMatch(finding ->
                FindingRules.SPRING_INTERRUPTED_EXCEPTION_SWALLOWED.ruleId().equals(finding.ruleId())
                        && finding.severity() == FindingSeverity.WARNING
                        && finding.recommendation() != null
                        && finding.recommendation().contains("Thread.currentThread().interrupt()")
        );
        assertThat(findings).anyMatch(finding ->
                FindingRules.SPRING_PRINT_STACK_TRACE.ruleId().equals(finding.ruleId())
                        && finding.severity() == FindingSeverity.WARNING
                        && "com.example.demo.OrderService#printFailure".equals(finding.target())
        );
    }

    @Test
    void doesNotFlagProperInterruptedExceptionHandling() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/resources"));
        Path sourceRoot = Files.createDirectories(tempDir.resolve("src/main/java/com/example/demo"));
        Files.writeString(sourceRoot.resolve("InterruptAwareJob.java"), """
                package com.example.demo;

                class InterruptAwareJob {
                    void run() {
                        try {
                            Thread.sleep(5);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
                """);

        List<Finding> findings = analyzeStaticPractice(tempDir, emptyBuildInfo(List.of()));

        assertThat(findings).noneMatch(finding ->
                FindingRules.SPRING_INTERRUPTED_EXCEPTION_SWALLOWED.ruleId().equals(finding.ruleId())
        );
    }

    @Test
    void flagsRawExceptionMessageReturnedFromController() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/resources"));
        Path sourceRoot = Files.createDirectories(tempDir.resolve("src/main/java/com/example/demo"));
        Files.writeString(sourceRoot.resolve("ApiController.java"), """
                package com.example.demo;

                import org.springframework.http.ResponseEntity;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                class ApiController {
                    @GetMapping("/x")
                    ResponseEntity<String> get() {
                        try {
                            return ResponseEntity.ok("ok");
                        } catch (Exception e) {
                            return ResponseEntity.status(500).body(e.getMessage());
                        }
                    }
                }
                """);

        List<Finding> findings = analyzeStaticPractice(tempDir, emptyBuildInfo(List.of("org.springframework.boot:spring-boot-starter-web")));

        assertThat(findings).anyMatch(finding ->
                FindingRules.SPRING_RAW_EXCEPTION_MESSAGE_HTTP.ruleId().equals(finding.ruleId())
                        && finding.severity() == FindingSeverity.WARNING
                        && finding.category() == FindingCategory.SECURITY
                        && "com.example.demo.ApiController#get".equals(finding.target())
        );
    }

    @Test
    void flagsBroadSpringExceptionHandler() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/resources"));
        Path sourceRoot = Files.createDirectories(tempDir.resolve("src/main/java/com/example/demo"));
        Files.writeString(sourceRoot.resolve("GlobalErrors.java"), """
                package com.example.demo;

                import org.springframework.http.ResponseEntity;
                import org.springframework.web.bind.annotation.ExceptionHandler;
                import org.springframework.web.bind.annotation.RestControllerAdvice;

                @RestControllerAdvice
                class GlobalErrors {
                    @ExceptionHandler(Exception.class)
                    ResponseEntity<String> handle(Exception ex) {
                        return ResponseEntity.status(500).body("nope");
                    }
                }
                """);

        List<Finding> findings = analyzeStaticPractice(tempDir, emptyBuildInfo(List.of("org.springframework.boot:spring-boot-starter-web")));

        assertThat(findings).anyMatch(finding ->
                FindingRules.SPRING_BROAD_EXCEPTION_HANDLER.ruleId().equals(finding.ruleId())
                        && finding.category() == FindingCategory.EXCEPTION_HANDLING
                        && finding.runtimeDetection() == FindingRuntimeDetection.NOT_NORMALLY_DETECTED
        );
    }

    private void writeFixtureProject(Path root) throws IOException {
        Files.createDirectories(root.resolve("src/main/resources"));
        Files.writeString(root.resolve("src/main/resources/application.properties"), """
                trading.provider=stub
                trading.api-secret=super-secret
                spring.flyway.enabled=true
                client.base-url=https://api.example.com
                spring.datasource.url=jdbc:postgresql://localhost:5432/demo
                """);
        Files.writeString(root.resolve("src/main/resources/application-prod.properties"), """
                trading.provider=real
                trading.api-secret=prod-secret
                spring.jpa.hibernate.ddl-auto=update
                """);

        Path sourceRoot = Files.createDirectories(root.resolve("src/main/java/com/example/demo"));
        Files.writeString(sourceRoot.resolve("DemoApplication.java"), """
                package com.example.demo;

                import org.springframework.boot.autoconfigure.SpringBootApplication;
                import org.springframework.scheduling.annotation.EnableScheduling;

                @SpringBootApplication
                @EnableScheduling
                class DemoApplication {
                }
                """);
        Files.writeString(sourceRoot.resolve("ProviderConfig.java"), """
                package com.example.demo;

                import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                class ProviderConfig {

                    @Bean
                    @ConditionalOnProperty(prefix = "trading", name = "provider", havingValue = "stub", matchIfMissing = true)
                    Object stubProvider() {
                        return new Object();
                    }

                    @Bean
                    @ConditionalOnProperty(prefix = "trading", name = "provider", havingValue = "alpaca")
                    Object alpacaProvider() {
                        return new Object();
                    }
                }
                """);
        Files.writeString(sourceRoot.resolve("StartupSyncRunner.java"), """
                package com.example.demo;

                import org.springframework.boot.CommandLineRunner;
                import org.springframework.stereotype.Component;
                import org.springframework.web.client.RestTemplate;

                @Component
                class StartupSyncRunner implements CommandLineRunner {

                    private final RestTemplate restTemplate = new RestTemplate();
                    private final OrderRepository orderRepository = new OrderRepository();

                    @Override
                    public void run(String... args) {
                        restTemplate.getForObject("https://api.example.com/bootstrap", String.class);
                        orderRepository.save(new Object());
                    }
                }
                """);
        Files.writeString(sourceRoot.resolve("PriceRefreshJob.java"), """
                package com.example.demo;

                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;
                import org.springframework.web.client.RestTemplate;

                @Component
                class PriceRefreshJob {

                    private final RestTemplate restTemplate = new RestTemplate();

                    @Scheduled(fixedRate = 5000)
                    void refreshPrices() {
                        restTemplate.postForObject("https://api.example.com/prices/refresh", null, String.class);
                    }
                }
                """);
        Files.writeString(sourceRoot.resolve("TradingService.java"), """
                package com.example.demo;

                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;

                @Service
                class TradingService {

                    private final OrderRepository orderRepository = new OrderRepository();
                    private final JdbcGateway jdbcGateway = new JdbcGateway();

                    void processOrders() {
                        this.persistOrder();
                    }

                    void applyWrites() {
                        orderRepository.save(new Object());
                        jdbcGateway.update("update orders set status='DONE'");
                    }

                    @Transactional
                    private void persistOrder() {
                        orderRepository.save(new Object());
                    }
                }
                """);
        Files.writeString(sourceRoot.resolve("TradingController.java"), """
                package com.example.demo;

                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                class TradingController {

                    @PostMapping("/orders")
                    void create(@RequestBody CreateOrderRequest request) {
                    }
                }
                """);
        Files.writeString(sourceRoot.resolve("ExternalApiClient.java"), """
                package com.example.demo;

                import org.springframework.stereotype.Component;
                import org.springframework.web.reactive.function.client.WebClient;

                @Component
                class ExternalApiClient {

                    private final WebClient client = WebClient.builder()
                            .baseUrl("https://api.example.com")
                            .build();

                    void send() {
                        client.post().uri("/orders");
                    }
                }
                """);
        Files.writeString(sourceRoot.resolve("SupportTypes.java"), """
                package com.example.demo;

                class OrderRepository {
                    void save(Object value) {
                    }
                }

                class JdbcGateway {
                    void update(String sql) {
                    }
                }

                record CreateOrderRequest(String symbol, int quantity) {
                }
                """);
    }

    private List<Finding> analyzeStaticPractice(Path repositoryRoot, BuildInfo buildInfo) {
        var configurationResult = configurationAnalyzer.analyze(repositoryRoot, buildInfo);
        var sourceAnalysis = javaSourceAnalyzer.analyze(repositoryRoot);
        HttpSurfaceAnalyzer.Result httpResult = httpSurfaceAnalyzer.analyze(
                repositoryRoot,
                configurationResult.configurationAnalysis(),
                buildInfo,
                WebStack.SERVLET_MVC
        );
        return analyzer.analyze(
                repositoryRoot,
                buildInfo,
                configurationResult.configurationAnalysis(),
                GradleModelAnalysis.empty(GradleAnalysisStatus.NOT_REQUESTED, "TOOLING_API", List.of()),
                new RuntimeStackAnalysis("3.5.13", "build.gradle", "25", WebStack.SERVLET_MVC, "Static servlet signals", null, "com.example.demo.DemoApplication"),
                httpResult.httpSurfaceAnalysis(),
                sourceAnalysis.detectedClasses()
        );
    }

    private BuildInfo emptyBuildInfo(List<String> dependencies) {
        return new BuildInfo(
                BuildTool.GRADLE,
                true,
                "25",
                dependencies,
                "3.5.13",
                "build.gradle plugin",
                "HIGH"
        );
    }

    private void assertRichFinding(
            List<Finding> findings,
            String ruleId,
            FindingCategory category,
            FindingRuntimeDetection runtimeDetection
    ) {
        Finding finding = findings.stream()
                .filter(candidate -> ruleId.equals(candidate.ruleId()))
                .findFirst()
                .orElseThrow();
        assertThat(finding.category()).isEqualTo(category);
        assertThat(finding.runtimeDetection()).isEqualTo(runtimeDetection);
        assertThat(finding.confidence()).isIn(FindingConfidence.HIGH, FindingConfidence.MEDIUM, FindingConfidence.LOW);
        assertThat(finding.whyBadPractice()).isNotBlank();
        assertThat(finding.possibleImpact()).isNotBlank();
        assertThat(finding.recommendation()).isNotBlank();
        assertThat(finding.evidence()).isNotBlank();
        assertThat(finding.limitations()).isNotBlank();
    }
}
