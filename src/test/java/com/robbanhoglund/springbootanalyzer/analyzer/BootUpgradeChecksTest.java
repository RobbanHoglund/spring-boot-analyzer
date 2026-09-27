package com.robbanhoglund.springbootanalyzer.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildInfo;
import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildTool;
import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingSeverity;
import com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.ApplicationProperty;
import com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.ConfigurationAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.ConfigurationSummary;
import com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.PropertyKind;
import com.robbanhoglund.springbootanalyzer.analyzer.model.runtime.RuntimeStackAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.source.JavaSources;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The version-specific upgrade checks run by {@link MigrationPracticeFindingAnalyzer}. */
class BootUpgradeChecksTest {

    private static final String PARAMETER_NAMES = "SPRING_PARAMETER_NAMES_NOT_RETAINED";
    private static final String JACKSON2 = "SPRING_JACKSON2_OBJECTMAPPER_IGNORED_FOR_HTTP";
    private static final String TEST_CLIENT = "SPRING_BOOT4_TEST_CLIENT_NOT_AUTOCONFIGURED";

    private static final String GRADLE_WITHOUT_BOOT_PLUGIN =
            """
            plugins {
                id 'java'
                id 'io.spring.dependency-management' version '1.1.7'
            }
            dependencyManagement {
                imports { mavenBom 'org.springframework.boot:spring-boot-dependencies:3.5.13' }
            }
            """;

    private static final String ORDER_CONTROLLER =
            """
            package com.example;

            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.PathVariable;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            class OrderController {
                @GetMapping("/orders/{id}")
                String find(@PathVariable Long id) {
                    return "";
                }
            }
            """;

    @TempDir Path repoRoot;

    private final MigrationPracticeFindingAnalyzer analyzer =
            new MigrationPracticeFindingAnalyzer();

    private void write(String relativePath, String content) throws IOException {
        Path file = repoRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private List<Finding> findings(
            String ruleId, String bootVersion, ApplicationProperty... properties) {
        ConfigurationAnalysis configuration =
                new ConfigurationAnalysis(
                        List.of(),
                        List.of(properties),
                        List.of(),
                        List.of(),
                        new ConfigurationSummary(0, 0, 0, 0, 0, 0, List.of()));
        return analyzer
                .analyze(
                        JavaSources.from(repoRoot),
                        new RuntimeStackAnalysis(bootVersion, null, null, null, null, null, null),
                        new BuildInfo(
                                BuildTool.GRADLE,
                                true,
                                "21",
                                List.of(),
                                bootVersion,
                                "build.gradle",
                                "HIGH"),
                        configuration)
                .stream()
                .filter(finding -> ruleId.equals(finding.ruleId()))
                .toList();
    }

    private static ApplicationProperty property(String name, String value) {
        return new ApplicationProperty(
                name,
                value,
                false,
                false,
                "src/main/resources/application.properties",
                1,
                null,
                PropertyKind.SPRING_BOOT,
                null,
                List.of());
    }

    // ── SPRING_PARAMETER_NAMES_NOT_RETAINED ──────────────────────────────────

    @Test
    void flagsUnnamedBindingWhenGradleBuildLacksBootPluginAndParametersFlag() throws IOException {
        write("build.gradle", GRADLE_WITHOUT_BOOT_PLUGIN);
        write("src/main/java/com/example/OrderController.java", ORDER_CONTROLLER);

        assertThat(findings(PARAMETER_NAMES, "3.5.13"))
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.severity()).isEqualTo(FindingSeverity.ERROR);
                            assertThat(finding.target()).isEqualTo("OrderController#find");
                            assertThat(finding.message())
                                    .startsWith("@PathVariable Long id in OrderController#find");
                            assertThat(finding.whyBadPractice())
                                    .contains("build.gradle applies neither");
                            assertThat(finding.primaryLocation().startLine()).isEqualTo(10);
                        });
    }

    @Test
    void doesNotFlagBuildsThatRetainParameterNames() throws IOException {
        write("src/main/java/com/example/OrderController.java", ORDER_CONTROLLER);

        write("build.gradle", "plugins {\n    id 'org.springframework.boot' version '3.5.13'\n}\n");
        assertThat(findings(PARAMETER_NAMES, "3.5.13")).isEmpty();

        write(
                "build.gradle",
                GRADLE_WITHOUT_BOOT_PLUGIN
                        + "tasks.withType(JavaCompile) { options.compilerArgs << '-parameters'"
                        + " }\n");
        assertThat(findings(PARAMETER_NAMES, "3.5.13")).isEmpty();

        Files.delete(repoRoot.resolve("build.gradle"));
        write("build.gradle.kts", "plugins {\n    alias(libs.plugins.spring.boot)\n}\n");
        assertThat(findings(PARAMETER_NAMES, "3.5.13")).isEmpty();

        // The Kotlin build without plugin or flag is reported again.
        write("build.gradle.kts", "plugins {\n    java\n}\n");
        assertThat(findings(PARAMETER_NAMES, "3.5.13")).hasSize(1);
    }

    @Test
    void doesNotFlagBeforeSpringBoot32() throws IOException {
        write("build.gradle", GRADLE_WITHOUT_BOOT_PLUGIN);
        write("src/main/java/com/example/OrderController.java", ORDER_CONTROLLER);

        assertThat(findings(PARAMETER_NAMES, "3.1.12")).isEmpty();
    }

    @Test
    void flagsSpelParameterReferenceInPomWithoutBootParent() throws IOException {
        write(
                "pom.xml",
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>catalog</artifactId>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-dependencies</artifactId>
                        <version>3.5.13</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        write(
                "src/main/java/com/example/CatalogService.java",
                """
                package com.example;

                import org.springframework.cache.annotation.Cacheable;
                import org.springframework.stereotype.Service;

                @Service
                class CatalogService {
                    @Cacheable(cacheNames = "products", key = "#sku")
                    String product(String sku) {
                        return sku;
                    }

                    @Cacheable(cacheNames = "prices", key = "#p0")
                    String price(String sku) {
                        return sku;
                    }
                }
                """);

        assertThat(findings(PARAMETER_NAMES, "3.5.13"))
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.message())
                                    .startsWith("#sku in @Cacheable on CatalogService#product");
                            assertThat(finding.whyBadPractice())
                                    .contains("pom.xml has no spring-boot-starter-parent");
                        });
    }

    @Test
    void doesNotFlagPomsThatEnableParametersOrInheritFromOutsideTheRepository() throws IOException {
        write("src/main/java/com/example/OrderController.java", ORDER_CONTROLLER);

        write(
                "pom.xml",
                """
                <project>
                  <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.5.13</version>
                  </parent>
                </project>
                """);
        assertThat(findings(PARAMETER_NAMES, "3.5.13")).isEmpty();

        write(
                "pom.xml",
                """
                <project>
                  <properties>
                    <maven.compiler.parameters>true</maven.compiler.parameters>
                  </properties>
                </project>
                """);
        assertThat(findings(PARAMETER_NAMES, "3.5.13")).isEmpty();

        write(
                "pom.xml",
                """
                <project>
                  <parent>
                    <groupId>com.corp</groupId>
                    <artifactId>corp-parent</artifactId>
                    <version>7</version>
                    <relativePath/>
                  </parent>
                </project>
                """);
        assertThat(findings(PARAMETER_NAMES, "3.5.13")).isEmpty();
    }

    @Test
    void ignoresNamedAndMapLikeBindings() throws IOException {
        write("build.gradle", GRADLE_WITHOUT_BOOT_PLUGIN);
        write(
                "src/main/java/com/example/SearchController.java",
                """
                package com.example;

                import java.util.Map;
                import org.springframework.http.HttpHeaders;
                import org.springframework.util.MultiValueMap;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.RequestHeader;
                import org.springframework.web.bind.annotation.RequestParam;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                class SearchController {
                    @GetMapping("/search/{scope}")
                    String search(
                            @PathVariable("scope") String scope,
                            @RequestParam(name = "q") String query,
                            @RequestParam Map<String, String> filters,
                            @RequestParam MultiValueMap<String, String> all,
                            @RequestHeader HttpHeaders headers) {
                        return "";
                    }
                }
                """);

        assertThat(findings(PARAMETER_NAMES, "3.5.13")).isEmpty();
    }

    // ── SPRING_JACKSON2_OBJECTMAPPER_IGNORED_FOR_HTTP ─────────────────────────

    private void writeJacksonConfig() throws IOException {
        write(
                "src/main/java/com/example/JacksonConfig.java",
                """
                package com.example;

                import com.fasterxml.jackson.databind.ObjectMapper;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                class JacksonConfig {
                    @Bean
                    ObjectMapper objectMapper() {
                        return new ObjectMapper();
                    }
                }
                """);
    }

    @Test
    void flagsJackson2ObjectMapperBeanOnSpringBoot4() throws IOException {
        writeJacksonConfig();

        assertThat(findings(JACKSON2, "4.0.6"))
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.severity()).isEqualTo(FindingSeverity.INFO);
                            assertThat(finding.target()).isEqualTo("JacksonConfig#objectMapper");
                            assertThat(finding.primaryLocation().startLine()).isEqualTo(9);
                        });
    }

    @Test
    void doesNotFlagJackson2BeanWhenPreferredOrBeforeBoot4() throws IOException {
        writeJacksonConfig();

        assertThat(
                        findings(
                                JACKSON2,
                                "4.0.6",
                                property(
                                        "spring.http.converters.preferred-json-mapper",
                                        "jackson2")))
                .isEmpty();
        assertThat(findings(JACKSON2, "3.5.13")).isEmpty();
    }

    @Test
    void doesNotFlagJackson3Mapper() throws IOException {
        write(
                "src/main/java/com/example/JacksonConfig.java",
                """
                package com.example;

                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import tools.jackson.databind.json.JsonMapper;

                @Configuration
                class JacksonConfig {
                    @Bean
                    JsonMapper jsonMapper() {
                        return JsonMapper.builder().build();
                    }
                }
                """);

        assertThat(findings(JACKSON2, "4.0.6")).isEmpty();
    }

    // ── SPRING_BOOT4_TEST_CLIENT_NOT_AUTOCONFIGURED ───────────────────────────

    @Test
    void flagsInjectedTestClientsWithoutAutoConfigureAnnotationOnBoot4() throws IOException {
        write(
                "src/test/java/com/example/ApiIT.java",
                """
                package com.example;

                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.boot.resttestclient.TestRestTemplate;

                @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
                class ApiIT {
                    @Autowired
                    TestRestTemplate restTemplate;
                }
                """);
        write(
                "src/test/java/com/example/ClientIT.java",
                """
                package com.example;

                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.test.web.servlet.client.RestTestClient;

                @SpringBootTest
                class ClientIT {
                    ClientIT(RestTestClient client) {
                    }
                }
                """);

        List<Finding> findings = findings(TEST_CLIENT, "4.0.6");

        assertThat(findings).extracting(Finding::target).containsExactly("ApiIT", "ClientIT");
        assertThat(findings.get(0).severity()).isEqualTo(FindingSeverity.ERROR);
        assertThat(findings.get(0).message()).contains("@AutoConfigureTestRestTemplate");
        assertThat(findings.get(0).primaryLocation().startLine()).isEqualTo(10);
        assertThat(findings.get(1).message()).contains("@AutoConfigureRestTestClient");
        assertThat(findings(TEST_CLIENT, "3.5.13")).isEmpty();
    }

    @Test
    void doesNotFlagAnnotatedSubclassedOrManuallyBuiltTestClients() throws IOException {
        write(
                "src/test/java/com/example/AnnotatedIT.java",
                """
                package com.example;

                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
                @AutoConfigureTestRestTemplate
                class AnnotatedIT {
                    @Autowired
                    TestRestTemplate restTemplate;
                }
                """);
        write(
                "src/test/java/com/example/SubclassIT.java",
                """
                package com.example;

                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                class SubclassIT extends BaseIT {
                    @Autowired
                    TestRestTemplate restTemplate;
                }
                """);
        write(
                "src/test/java/com/example/ManualIT.java",
                """
                package com.example;

                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                class ManualIT {
                    private final TestRestTemplate restTemplate = new TestRestTemplate();
                }
                """);

        assertThat(findings(TEST_CLIENT, "4.0.6")).isEmpty();
    }
}
