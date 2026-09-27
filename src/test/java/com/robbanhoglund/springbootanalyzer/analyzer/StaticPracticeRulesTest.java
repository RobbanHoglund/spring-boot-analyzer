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
import com.robbanhoglund.springbootanalyzer.analyzer.http.HttpSurfaceAnalyzer;
import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildInfo;
import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildTool;
import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingSeverity;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleAnalysisStatus;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleModelAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.runtime.RuntimeStackAnalysis;
import com.robbanhoglund.springbootanalyzer.analyzer.model.runtime.WebStack;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Persistence rules and false-positive guards of {@link StaticPracticeFindingAnalyzer}. */
class StaticPracticeRulesTest {

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
    private final JavaSourceAnalyzer javaSourceAnalyzer = new JavaSourceAnalyzer();
    private final HttpSurfaceAnalyzer httpSurfaceAnalyzer = new HttpSurfaceAnalyzer();
    private final StaticPracticeFindingAnalyzer analyzer = new StaticPracticeFindingAnalyzer();

    @TempDir Path repoRoot;

    private void write(String fileName, String content) throws IOException {
        Path file = repoRoot.resolve("src/main/java/com/example").resolve(fileName);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private List<Finding> findings(String ruleId, String bootVersion) {
        BuildInfo buildInfo =
                new BuildInfo(
                        BuildTool.GRADLE,
                        true,
                        "21",
                        List.of("org.springframework.boot:spring-boot-starter-web"),
                        bootVersion,
                        "build.gradle plugin",
                        "HIGH");
        var configuration = configurationAnalyzer.analyze(repoRoot, buildInfo);
        var http =
                httpSurfaceAnalyzer.analyze(
                        repoRoot,
                        configuration.configurationAnalysis(),
                        buildInfo,
                        WebStack.SERVLET_MVC);
        return analyzer
                .analyze(
                        repoRoot,
                        buildInfo,
                        configuration.configurationAnalysis(),
                        GradleModelAnalysis.empty(
                                GradleAnalysisStatus.NOT_REQUESTED, "TOOLING_API", List.of()),
                        new RuntimeStackAnalysis(
                                bootVersion,
                                "build.gradle",
                                "21",
                                WebStack.SERVLET_MVC,
                                "Static servlet signals",
                                null,
                                "com.example.Application"),
                        http.httpSurfaceAnalysis(),
                        javaSourceAnalyzer.analyze(repoRoot).detectedClasses())
                .stream()
                .filter(finding -> ruleId.equals(finding.ruleId()))
                .toList();
    }

    private List<Finding> findings(String ruleId) {
        return findings(ruleId, "3.5.13");
    }

    // ── SPRING_QUERY_DML_WITHOUT_MODIFYING ────────────────────────────────────

    @Test
    void flagsJpaDmlQueriesWithoutModifying() throws IOException {
        write(
                "OrderRepository.java",
                """
                package com.example;

                import org.springframework.data.jpa.repository.JpaRepository;
                import org.springframework.data.jpa.repository.Modifying;
                import org.springframework.data.jpa.repository.Query;

                interface OrderRepository extends JpaRepository<Order, Long> {
                    @Query("update Order o set o.status = :status where o.id = :id")
                    int updateStatus(Long id, String status);

                    @Query(value = "DELETE FROM orders WHERE created < now()", nativeQuery = true)
                    void purge();

                    @Query(\"""
                            /* archive */
                            INSERT INTO archived_orders SELECT * FROM orders
                            \""")
                    void archive();

                    @Modifying
                    @Query("update Order o set o.status = 'DONE'")
                    int complete();

                    @Query("select o from Order o where o.status = 'NEW'")
                    java.util.List<Order> fresh();
                }

                class Order {
                }
                """);

        List<Finding> findings = findings("SPRING_QUERY_DML_WITHOUT_MODIFYING");

        assertThat(findings)
                .extracting(Finding::target)
                .containsExactlyInAnyOrder(
                        "OrderRepository#updateStatus",
                        "OrderRepository#purge",
                        "OrderRepository#archive");
        assertThat(findings)
                .filteredOn(finding -> "OrderRepository#purge".equals(finding.target()))
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.severity()).isEqualTo(FindingSeverity.ERROR);
                            assertThat(finding.message()).contains("runs a DELETE statement");
                        });
    }

    @Test
    void ignoresQueryAnnotationsOfOtherSpringDataModules() throws IOException {
        write(
                "EventRepository.java",
                """
                package com.example;

                import org.springframework.data.cassandra.repository.CassandraRepository;
                import org.springframework.data.cassandra.repository.Query;

                interface EventRepository extends CassandraRepository<Event, String> {
                    @Query("DELETE FROM events WHERE id = ?0")
                    void remove(String id);
                }

                class Event {
                }
                """);

        assertThat(findings("SPRING_QUERY_DML_WITHOUT_MODIFYING")).isEmpty();
    }

    // ── SPRING_JPA_ENUM_ORDINAL ───────────────────────────────────────────────

    @Test
    void flagsEnumsPersistedByOrdinal() throws IOException {
        write(
                "Status.java",
                """
                package com.example;

                enum Status { NEW, PAID, SHIPPED }
                """);
        write(
                "Role.java",
                """
                package com.example;

                public enum Role { USER, ADMIN }
                """);
        write(
                "Order.java",
                """
                package com.example;

                import jakarta.persistence.Convert;
                import jakarta.persistence.ElementCollection;
                import jakarta.persistence.Entity;
                import jakarta.persistence.EnumType;
                import jakarta.persistence.Enumerated;
                import jakarta.persistence.Id;
                import java.time.DayOfWeek;
                import java.util.Set;

                @Entity
                class Order {
                    @Id
                    Long id;

                    Status status;

                    @Enumerated(EnumType.STRING)
                    Status previousStatus;

                    @Enumerated(EnumType.ORDINAL)
                    Status legacyStatus;

                    @Convert(converter = StatusConverter.class)
                    Status convertedStatus;

                    @ElementCollection
                    Set<Role> roles;

                    DayOfWeek deliveryDay;
                }

                class StatusConverter {
                }
                """);
        write(
                "OrderView.java",
                """
                package com.example;

                class OrderView {
                    Status status;
                }
                """);

        List<Finding> findings = findings("SPRING_JPA_ENUM_ORDINAL");

        assertThat(findings)
                .extracting(Finding::target)
                .containsExactlyInAnyOrder("Order.status", "Order.legacyStatus", "Order.roles");
        assertThat(findings)
                .filteredOn(finding -> "Order.status".equals(finding.target()))
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.severity()).isEqualTo(FindingSeverity.WARNING);
                            assertThat(finding.primaryLocation().startLine()).isEqualTo(17);
                        });
        assertThat(findings)
                .filteredOn(finding -> "Order.legacyStatus".equals(finding.target()))
                .singleElement()
                .extracting(Finding::severity)
                .isEqualTo(FindingSeverity.INFO);
    }

    // ── SPRING_APPLICATION_CONTEXT_INJECTED ───────────────────────────────────

    @Test
    void reportsApplicationContextOnlyWhenItLooksUpBeans() throws IOException {
        write(
                "Locator.java",
                """
                package com.example;

                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.context.ApplicationContext;
                import org.springframework.context.ConfigurableApplicationContext;
                import org.springframework.stereotype.Component;

                @Component
                class Locator {
                    @Autowired
                    private ApplicationContext context;

                    Object find() {
                        return context.getBean("orderService");
                    }
                }

                @Component
                class Shutdown {
                    @Autowired
                    private ConfigurableApplicationContext context;

                    void stop() {
                        context.close();
                    }
                }
                """);

        assertThat(findings("SPRING_APPLICATION_CONTEXT_INJECTED"))
                .singleElement()
                .extracting(Finding::target)
                .isEqualTo("Locator.context");
    }

    // ── SPRING_TRANSACTIONAL_SELF_INVOCATION ──────────────────────────────────

    @Test
    void reportsSelfInvocationOnlyWhenTransactionSettingsAreLost() throws IOException {
        write(
                "BillingService.java",
                """
                package com.example;

                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Propagation;
                import org.springframework.transaction.annotation.Transactional;

                @Service
                class BillingService {
                    @Transactional
                    public void bill() {
                        record();
                        audit();
                    }

                    public void untransactional() {
                        record();
                    }

                    @Transactional
                    public void record() {
                    }

                    @Transactional(propagation = Propagation.REQUIRES_NEW)
                    public void audit() {
                    }
                }
                """);

        assertThat(findings("SPRING_TRANSACTIONAL_SELF_INVOCATION"))
                .extracting(Finding::message)
                .containsExactlyInAnyOrder(
                        "Transactional method appears to be called from the same class:"
                                + " BillingService#audit",
                        "Transactional method appears to be called from the same class:"
                                + " BillingService#record");
    }

    // ── SPRING_TRANSACTIONAL_READONLY_WITH_WRITES ─────────────────────────────

    @Test
    void reportsReadOnlyWritesOnlyThroughPersistenceReceivers() throws IOException {
        write(
                "ReportService.java",
                """
                package com.example;

                import java.util.Map;
                import java.util.concurrent.ConcurrentHashMap;
                import java.util.concurrent.Executor;
                import org.springframework.jdbc.core.JdbcTemplate;
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;

                @Service
                class ReportService {
                    private final Map<String, Integer> counts = new ConcurrentHashMap<>();
                    private final Executor executor;
                    private final JdbcTemplate jdbcTemplate;

                    ReportService(Executor executor, JdbcTemplate jdbcTemplate) {
                        this.executor = executor;
                        this.jdbcTemplate = jdbcTemplate;
                    }

                    @Transactional(readOnly = true)
                    public void count(String key) {
                        counts.merge(key, 1, Integer::sum);
                        executor.execute(() -> { });
                    }

                    @Transactional(readOnly = true)
                    public void touch(String key) {
                        jdbcTemplate.update("update reports set seen = true where id = ?", key);
                    }
                }
                """);

        assertThat(findings("SPRING_TRANSACTIONAL_READONLY_WITH_WRITES"))
                .singleElement()
                .extracting(Finding::target)
                .asString()
                .contains("touch");
    }

    // ── SPRING_CSRF_DISABLED ──────────────────────────────────────────────────

    @Test
    void reportsDisabledCsrfOnlyWhereBrowsersAuthenticateAutomatically() throws IOException {
        write(
                "SecurityConfig.java",
                """
                package com.example;

                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.security.config.Customizer;
                import org.springframework.security.config.annotation.web.builders.HttpSecurity;
                import org.springframework.security.config.http.SessionCreationPolicy;
                import org.springframework.security.web.SecurityFilterChain;

                @Configuration
                class SecurityConfig {
                    @Bean
                    SecurityFilterChain api(HttpSecurity http) throws Exception {
                        return http.securityMatcher("/api/**")
                                .csrf(csrf -> csrf.disable())
                                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
                                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                                .build();
                    }

                    @Bean
                    SecurityFilterChain tokens(HttpSecurity http) throws Exception {
                        return http.securityMatcher("/tokens/**")
                                .csrf(csrf -> csrf.disable())
                                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                                .build();
                    }

                    @Bean
                    SecurityFilterChain web(HttpSecurity http) throws Exception {
                        return http.csrf(csrf -> csrf.disable())
                                .formLogin(Customizer.withDefaults())
                                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                                .build();
                    }
                }
                """);

        assertThat(findings("SPRING_CSRF_DISABLED"))
                .singleElement()
                .extracting(Finding::target)
                .isEqualTo("SecurityConfig#web");
    }

    // ── SPRING_LOGGING_PII_EXPOSURE ───────────────────────────────────────────

    @Test
    void ignoresLoggedPresenceChecksOfSecrets() throws IOException {
        write(
                "LoginService.java",
                """
                package com.example;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.stereotype.Service;

                @Service
                class LoginService {
                    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

                    void login(String user, String password) {
                        log.info("password supplied: {}", password != null);
                        log.debug("password blank: {}", password.isBlank());
                        log.info("login {} with {}", user, password);
                    }
                }
                """);

        assertThat(findings("SPRING_LOGGING_PII_EXPOSURE"))
                .singleElement()
                .extracting(finding -> finding.primaryLocation().startLine())
                .isEqualTo(14);
    }

    // ── SPRING_DUPLICATE_EXCEPTION_HANDLER ────────────────────────────────────

    @Test
    void flagsHandlerThatDuplicatesAnInheritedResponseEntityExceptionHandlerMapping()
            throws IOException {
        write(
                "ApiErrors.java",
                """
                package com.example;

                import org.springframework.http.ResponseEntity;
                import org.springframework.web.bind.MethodArgumentNotValidException;
                import org.springframework.web.bind.annotation.ExceptionHandler;
                import org.springframework.web.bind.annotation.RestControllerAdvice;
                import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

                @RestControllerAdvice
                class ApiErrors extends ResponseEntityExceptionHandler {
                    @ExceptionHandler(MethodArgumentNotValidException.class)
                    ResponseEntity<String> invalid(MethodArgumentNotValidException exception) {
                        return ResponseEntity.badRequest().build();
                    }

                    @ExceptionHandler(IllegalStateException.class)
                    ResponseEntity<String> conflict(IllegalStateException exception) {
                        return ResponseEntity.status(409).build();
                    }
                }
                """);

        assertThat(findings("SPRING_DUPLICATE_EXCEPTION_HANDLER"))
                .singleElement()
                .extracting(Finding::message)
                .asString()
                .contains(
                        "MethodArgumentNotValidException",
                        "ResponseEntityExceptionHandler.handleException (inherited)",
                        "invalid");
        assertThat(findings("SPRING_DUPLICATE_EXCEPTION_HANDLER", "2.7.18")).isEmpty();
    }

    // ── SPRING_BROAD_EXCEPTION_HANDLER ────────────────────────────────────────

    @Test
    void acceptsCatchAllHandlersThatReturnAnyServerError() throws IOException {
        write(
                "ReportController.java",
                """
                package com.example;

                import org.springframework.http.HttpStatus;
                import org.springframework.http.ResponseEntity;
                import org.springframework.web.bind.annotation.ExceptionHandler;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                class ReportController {
                    @ExceptionHandler(RuntimeException.class)
                    ResponseEntity<String> unavailable(RuntimeException failure) {
                        return error(HttpStatus.SERVICE_UNAVAILABLE, "temporarily unavailable");
                    }

                    private static ResponseEntity<String> error(HttpStatus status, String code) {
                        return ResponseEntity.status(status).body(code);
                    }
                }
                """);

        assertThat(findings("SPRING_BROAD_EXCEPTION_HANDLER")).isEmpty();
    }

    @Test
    void doesNotMistakeACommentForDisabledCsrf() throws IOException {
        write(
                "WebSecurity.java",
                """
                package com.example;

                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.security.config.Customizer;
                import org.springframework.security.config.annotation.web.builders.HttpSecurity;
                import org.springframework.security.web.SecurityFilterChain;
                import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

                @Configuration
                class WebSecurity {
                    @Bean
                    SecurityFilterChain web(HttpSecurity http) throws Exception {
                        return http.csrf(csrf -> csrf
                                        // CSRF stays disabled only for the public API.
                                        .ignoringRequestMatchers("/api/public/**")
                                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
                                .formLogin(Customizer.withDefaults())
                                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                                .build();
                    }
                }
                """);

        assertThat(findings("SPRING_CSRF_DISABLED")).isEmpty();
    }
}
