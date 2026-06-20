package com.robbanhoglund.springbootanalyzer.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScalabilityPracticeFindingAnalyzerTest {

    @TempDir Path repoRoot;

    private ScalabilityPracticeFindingAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new ScalabilityPracticeFindingAnalyzer();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void writeSourceFile(String relativePath, String content) throws IOException {
        Path file = repoRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private List<Finding> findings() {
        return analyzer.analyze(repoRoot);
    }

    private static Finding byRule(List<Finding> findings, String ruleId) {
        return findings.stream().filter(f -> ruleId.equals(f.ruleId())).findFirst().orElse(null);
    }

    private static List<Finding> allByRule(List<Finding> findings, String ruleId) {
        return findings.stream().filter(f -> ruleId.equals(f.ruleId())).toList();
    }

    // ── No sources ────────────────────────────────────────────────────────────

    @Test
    void returnsEmptyListWhenNoMainDirectory() {
        assertThat(findings()).isEmpty();
    }

    // ── SPRING_HARDCODED_FILE_PATH ────────────────────────────────────────────

    @Test
    void flagsAbsolutePathInNewFile() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/FileService.java",
                """
                package com.example;
                import java.io.File;
                import org.springframework.stereotype.Service;
                @Service
                public class FileService {
                    public void process() {
                        File f = new File("/var/data/uploads");
                    }
                }
                """);

        Finding f = byRule(findings(), "SPRING_HARDCODED_FILE_PATH");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("/var/data/uploads");
    }

    @Test
    void flagsAbsolutePathInPathsGet() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/FileService.java",
                """
                package com.example;
                import java.nio.file.Paths;
                import org.springframework.stereotype.Service;
                @Service
                public class FileService {
                    public void process() {
                        var p = Paths.get("/tmp/work");
                    }
                }
                """);

        Finding f = byRule(findings(), "SPRING_HARDCODED_FILE_PATH");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("/tmp/work");
    }

    @Test
    void flagsAbsolutePathInPathOf() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/FileService.java",
                """
                package com.example;
                import java.nio.file.Path;
                import org.springframework.stereotype.Service;
                @Service
                public class FileService {
                    public void process() {
                        var p = Path.of("/etc/config/app.properties");
                    }
                }
                """);

        Finding f = byRule(findings(), "SPRING_HARDCODED_FILE_PATH");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("/etc/config");
    }

    @Test
    void doesNotFlagRelativePath() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/FileService.java",
                """
                package com.example;
                import java.io.File;
                import org.springframework.stereotype.Service;
                @Service
                public class FileService {
                    public void process() {
                        File f = new File("relative/path/file.txt");
                    }
                }
                """);

        assertThat(byRule(findings(), "SPRING_HARDCODED_FILE_PATH")).isNull();
    }

    // ── SPRING_LOMBOK_DATA_ON_ENTITY ──────────────────────────────────────────

    @Test
    void flagsDataAnnotationOnEntity() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/Order.java",
                """
                package com.example;
                import jakarta.persistence.Entity;
                import lombok.Data;
                @Entity
                @Data
                public class Order {
                    private Long id;
                }
                """);

        Finding f = byRule(findings(), "SPRING_LOMBOK_DATA_ON_ENTITY");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("Order");
    }

    @Test
    void doesNotFlagDataWithoutEntity() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/OrderDto.java",
                """
                package com.example;
                import lombok.Data;
                @Data
                public class OrderDto {
                    private Long id;
                }
                """);

        assertThat(byRule(findings(), "SPRING_LOMBOK_DATA_ON_ENTITY")).isNull();
    }

    @Test
    void doesNotFlagEntityWithoutData() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/Order.java",
                """
                package com.example;
                import jakarta.persistence.Entity;
                import lombok.Getter;
                import lombok.Setter;
                @Entity
                @Getter
                @Setter
                public class Order {
                    private Long id;
                }
                """);

        assertThat(byRule(findings(), "SPRING_LOMBOK_DATA_ON_ENTITY")).isNull();
    }

    // ── SPRING_REST_TEMPLATE_NO_TIMEOUT ───────────────────────────────────────

    @Test
    void flagsNoArgRestTemplateConstructor() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ApiClient.java",
                """
                package com.example;
                import org.springframework.stereotype.Service;
                import org.springframework.web.client.RestTemplate;
                @Service
                public class ApiClient {
                    private final RestTemplate restTemplate = new RestTemplate();
                }
                """);

        Finding f = byRule(findings(), "SPRING_REST_TEMPLATE_NO_TIMEOUT");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("RestTemplate");
    }

    @Test
    void doesNotFlagRestTemplateWithFactory() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ApiClient.java",
                """
                package com.example;
                import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
                import org.springframework.stereotype.Service;
                import org.springframework.web.client.RestTemplate;
                @Service
                public class ApiClient {
                    private final RestTemplate restTemplate =
                        new RestTemplate(new HttpComponentsClientHttpRequestFactory());
                }
                """);

        assertThat(byRule(findings(), "SPRING_REST_TEMPLATE_NO_TIMEOUT")).isNull();
    }

    // ── SPRING_PROTOTYPE_BEAN_IN_SINGLETON ────────────────────────────────────

    @Test
    void flagsPrototypeBeanInjectedAsSingletonField() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/RequestHandler.java",
                """
                package com.example;
                import org.springframework.context.annotation.Scope;
                import org.springframework.stereotype.Component;
                @Component
                @Scope("prototype")
                public class RequestHandler {}
                """);
        writeSourceFile(
                "src/main/java/com/example/OrderService.java",
                """
                package com.example;
                import org.springframework.stereotype.Service;
                @Service
                public class OrderService {
                    private final RequestHandler handler;
                    public OrderService(RequestHandler handler) {
                        this.handler = handler;
                    }
                }
                """);

        Finding f = byRule(findings(), "SPRING_PROTOTYPE_BEAN_IN_SINGLETON");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("RequestHandler");
    }

    @Test
    void doesNotFlagPrototypeClassOnItsOwn() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/RequestHandler.java",
                """
                package com.example;
                import org.springframework.context.annotation.Scope;
                import org.springframework.stereotype.Component;
                @Component
                @Scope("prototype")
                public class RequestHandler {}
                """);

        assertThat(byRule(findings(), "SPRING_PROTOTYPE_BEAN_IN_SINGLETON")).isNull();
    }

    // ── SPRING_FILTER_COMPONENT_REGISTRATION_LEAK ────────────────────────────

    @Test
    void flagsFilterWithComponentAnnotation() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/AuditFilter.java",
                """
                package com.example;
                import jakarta.servlet.Filter;
                import jakarta.servlet.FilterChain;
                import jakarta.servlet.ServletRequest;
                import jakarta.servlet.ServletResponse;
                import org.springframework.stereotype.Component;
                @Component
                public class AuditFilter implements Filter {
                    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) {}
                }
                """);

        Finding f = byRule(findings(), "SPRING_FILTER_COMPONENT_REGISTRATION_LEAK");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("AuditFilter");
    }

    @Test
    void flagsFilterWithServiceAnnotation() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/AuditFilter.java",
                """
                package com.example;
                import jakarta.servlet.Filter;
                import jakarta.servlet.FilterChain;
                import jakarta.servlet.ServletRequest;
                import jakarta.servlet.ServletResponse;
                import org.springframework.stereotype.Service;
                @Service
                public class AuditFilter implements Filter {
                    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) {}
                }
                """);

        Finding f = byRule(findings(), "SPRING_FILTER_COMPONENT_REGISTRATION_LEAK");
        assertThat(f).isNotNull();
    }

    @Test
    void doesNotFlagFilterWithoutSpringAnnotation() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/AuditFilter.java",
                """
                package com.example;
                import jakarta.servlet.Filter;
                import jakarta.servlet.FilterChain;
                import jakarta.servlet.ServletRequest;
                import jakarta.servlet.ServletResponse;
                public class AuditFilter implements Filter {
                    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) {}
                }
                """);

        assertThat(byRule(findings(), "SPRING_FILTER_COMPONENT_REGISTRATION_LEAK")).isNull();
    }

    // ── SPRING_WEBFLUX_BLOCKING_CALL ─────────────────────────────────────────

    @Test
    void flagsBlockCallInServiceClass() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ReactiveService.java",
                """
                package com.example;
                import org.springframework.stereotype.Service;
                import reactor.core.publisher.Mono;
                @Service
                public class ReactiveService {
                    public String getSync(Mono<String> mono) {
                        return mono.block();
                    }
                }
                """);

        Finding f = byRule(findings(), "SPRING_WEBFLUX_BLOCKING_CALL");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains(".block()");
    }

    @Test
    void flagsBlockWithTimeoutArgument() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ReactiveService.java",
                """
                package com.example;
                import java.time.Duration;
                import org.springframework.stereotype.Service;
                import reactor.core.publisher.Mono;
                @Service
                public class ReactiveService {
                    public String getSync(Mono<String> mono) {
                        return mono.block(Duration.ofSeconds(5));
                    }
                }
                """);

        Finding f = byRule(findings(), "SPRING_WEBFLUX_BLOCKING_CALL");
        assertThat(f).isNotNull();
    }

    @Test
    void flagsBlockFirstOnFlux() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ReactiveService.java",
                """
                package com.example;
                import org.springframework.stereotype.Service;
                import reactor.core.publisher.Flux;
                @Service
                public class ReactiveService {
                    public String first(Flux<String> flux) {
                        return flux.blockFirst();
                    }
                }
                """);

        Finding f = byRule(findings(), "SPRING_WEBFLUX_BLOCKING_CALL");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains(".blockFirst()");
    }

    @Test
    void flagsThreadSleepInComponent() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/PollingService.java",
                """
                package com.example;
                import org.springframework.stereotype.Service;
                @Service
                public class PollingService {
                    public void poll() throws InterruptedException {
                        Thread.sleep(1000);
                    }
                }
                """);

        Finding f = byRule(findings(), "SPRING_WEBFLUX_BLOCKING_CALL");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("Thread.sleep()");
    }

    @Test
    void doesNotFlagBlockCallInNonSpringClass() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ReactiveHelper.java",
                """
                package com.example;
                import reactor.core.publisher.Mono;
                public class ReactiveHelper {
                    public String resolve(Mono<String> mono) {
                        return mono.block();
                    }
                }
                """);

        assertThat(byRule(findings(), "SPRING_WEBFLUX_BLOCKING_CALL")).isNull();
    }

    // ── SPRING_NON_THREAD_SAFE_FORMATTER_FIELD ────────────────────────────────

    @Test
    void flagsSimpleDateFormatFieldInService() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/DateService.java",
                """
                package com.example;
                import java.text.SimpleDateFormat;
                import org.springframework.stereotype.Service;
                @Service
                public class DateService {
                    private final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd");
                }
                """);

        Finding f = byRule(findings(), "SPRING_NON_THREAD_SAFE_FORMATTER_FIELD");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("DateService#fmt");
    }

    @Test
    void doesNotFlagSimpleDateFormatInNonComponent() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/Helper.java",
                """
                package com.example;
                import java.text.SimpleDateFormat;
                public class Helper {
                    private final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd");
                }
                """);

        assertThat(byRule(findings(), "SPRING_NON_THREAD_SAFE_FORMATTER_FIELD")).isNull();
    }

    @Test
    void doesNotFlagDateTimeFormatterField() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/DateService.java",
                """
                package com.example;
                import java.time.format.DateTimeFormatter;
                import org.springframework.stereotype.Service;
                @Service
                public class DateService {
                    private final DateTimeFormatter fmt = DateTimeFormatter.ISO_DATE;
                }
                """);

        assertThat(byRule(findings(), "SPRING_NON_THREAD_SAFE_FORMATTER_FIELD")).isNull();
    }

    // ── SPRING_UNBOUNDED_FINDALL ──────────────────────────────────────────────

    @Test
    void flagsNoArgFindAllOnRepository() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/UserService.java",
                """
                package com.example;
                import java.util.List;
                import org.springframework.stereotype.Service;
                @Service
                public class UserService {
                    private final UserRepository userRepository = null;
                    public List<?> all() {
                        return userRepository.findAll();
                    }
                }
                interface UserRepository {
                    List<?> findAll();
                }
                """);

        assertThat(byRule(findings(), "SPRING_UNBOUNDED_FINDALL")).isNotNull();
    }

    @Test
    void doesNotFlagFindAllWithPageable() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/UserService.java",
                """
                package com.example;
                import org.springframework.data.domain.Pageable;
                import org.springframework.stereotype.Service;
                @Service
                public class UserService {
                    private final UserRepository userRepository = null;
                    public Object page(Pageable pageable) {
                        return userRepository.findAll(pageable);
                    }
                }
                interface UserRepository {
                    Object findAll(Pageable p);
                }
                """);

        assertThat(byRule(findings(), "SPRING_UNBOUNDED_FINDALL")).isNull();
    }

    @Test
    void doesNotFlagFindAllOnNonRepositoryReceiver() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/Cache.java",
                """
                package com.example;
                import java.util.List;
                public class Cache {
                    private final java.util.List<String> items = new java.util.ArrayList<>();
                    public List<String> all() {
                        return items;
                    }
                }
                """);

        assertThat(byRule(findings(), "SPRING_UNBOUNDED_FINDALL")).isNull();
    }

    // ── SPRING_ENTITY_MISSING_ID ──────────────────────────────────────────────

    @Test
    void flagsEntityWithoutId() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/Order.java",
                """
                package com.example;
                import jakarta.persistence.Entity;
                @Entity
                public class Order {
                    private String description;
                }
                """);

        Finding f = byRule(findings(), "SPRING_ENTITY_MISSING_ID");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("Order");
    }

    @Test
    void doesNotFlagEntityWithId() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/Order.java",
                """
                package com.example;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                @Entity
                public class Order {
                    @Id
                    private Long id;
                }
                """);

        assertThat(byRule(findings(), "SPRING_ENTITY_MISSING_ID")).isNull();
    }

    @Test
    void doesNotFlagEntityThatExtendsSuperclass() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/Order.java",
                """
                package com.example;
                import jakarta.persistence.Entity;
                @Entity
                public class Order extends BaseEntity {
                    private String description;
                }
                """);

        assertThat(byRule(findings(), "SPRING_ENTITY_MISSING_ID")).isNull();
    }

    // ── SPRING_RESTTEMPLATE_NEW_PER_REQUEST ───────────────────────────────────

    @Test
    void flagsRestTemplateCreatedInMethodWithFactory() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ApiClient.java",
                """
                package com.example;
                import org.springframework.http.client.SimpleClientHttpRequestFactory;
                import org.springframework.stereotype.Service;
                import org.springframework.web.client.RestTemplate;
                @Service
                public class ApiClient {
                    public String call() {
                        RestTemplate rt = new RestTemplate(new SimpleClientHttpRequestFactory());
                        return rt.getForObject("https://x", String.class);
                    }
                }
                """);

        assertThat(byRule(findings(), "SPRING_RESTTEMPLATE_NEW_PER_REQUEST")).isNotNull();
    }

    @Test
    void flagsRestClientCreateInMethod() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ApiClient.java",
                """
                package com.example;
                import org.springframework.stereotype.Service;
                import org.springframework.web.client.RestClient;
                @Service
                public class ApiClient {
                    public String call() {
                        return RestClient.create().get().uri("https://x").retrieve().body(String.class);
                    }
                }
                """);

        assertThat(byRule(findings(), "SPRING_RESTTEMPLATE_NEW_PER_REQUEST")).isNotNull();
    }

    @Test
    void doesNotFlagRestTemplateBeanField() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ApiClient.java",
                """
                package com.example;
                import org.springframework.http.client.SimpleClientHttpRequestFactory;
                import org.springframework.stereotype.Service;
                import org.springframework.web.client.RestTemplate;
                @Service
                public class ApiClient {
                    private final RestTemplate rt =
                        new RestTemplate(new SimpleClientHttpRequestFactory());
                }
                """);

        assertThat(byRule(findings(), "SPRING_RESTTEMPLATE_NEW_PER_REQUEST")).isNull();
    }

    @Test
    void doesNotFlagRestTemplateInBeanMethod() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ClientConfig.java",
                """
                package com.example;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.http.client.SimpleClientHttpRequestFactory;
                import org.springframework.web.client.RestTemplate;
                @Configuration
                public class ClientConfig {
                    @Bean
                    RestTemplate restTemplate() {
                        return new RestTemplate(new SimpleClientHttpRequestFactory());
                    }
                }
                """);

        assertThat(byRule(findings(), "SPRING_RESTTEMPLATE_NEW_PER_REQUEST")).isNull();
    }

    // ── SPRING_JPA_QUERY_NO_PAGINATION ────────────────────────────────────────

    @Test
    void flagsQueryReturningListWithoutPageable() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/OrderRepository.java",
                """
                package com.example;
                import java.util.List;
                import org.springframework.data.jpa.repository.Query;
                import org.springframework.data.repository.Repository;
                public interface OrderRepository extends Repository<Object, Long> {
                    @Query("SELECT o FROM Order o WHERE o.status = ?1")
                    List<Object> findByStatus(String status);
                }
                """);

        Finding f = byRule(findings(), "SPRING_JPA_QUERY_NO_PAGINATION");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("findByStatus");
    }

    @Test
    void doesNotFlagQueryWithPageable() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/OrderRepository.java",
                """
                package com.example;
                import java.util.List;
                import org.springframework.data.domain.Pageable;
                import org.springframework.data.jpa.repository.Query;
                import org.springframework.data.repository.Repository;
                public interface OrderRepository extends Repository<Object, Long> {
                    @Query("SELECT o FROM Order o WHERE o.status = ?1")
                    List<Object> findByStatus(String status, Pageable pageable);
                }
                """);

        assertThat(byRule(findings(), "SPRING_JPA_QUERY_NO_PAGINATION")).isNull();
    }

    @Test
    void doesNotFlagQueryReturningSingleEntity() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/OrderRepository.java",
                """
                package com.example;
                import org.springframework.data.jpa.repository.Query;
                import org.springframework.data.repository.Repository;
                public interface OrderRepository extends Repository<Object, Long> {
                    @Query("SELECT o FROM Order o WHERE o.id = ?1")
                    Object findOne(Long id);
                }
                """);

        assertThat(byRule(findings(), "SPRING_JPA_QUERY_NO_PAGINATION")).isNull();
    }

    // ── SPRING_REQUIRES_NEW_IN_LOOP ───────────────────────────────────────────

    @Test
    void flagsRequiresNewMethodCalledInLoop() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/BatchService.java",
                """
                package com.example;
                import java.util.List;
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Propagation;
                import org.springframework.transaction.annotation.Transactional;
                @Service
                public class BatchService {
                    private final ItemService itemService = null;
                    public void run(List<String> items) {
                        for (String item : items) {
                            itemService.process(item);
                        }
                    }
                }
                @Service
                class ItemService {
                    @Transactional(propagation = Propagation.REQUIRES_NEW)
                    public void process(String item) {}
                }
                """);

        assertThat(byRule(findings(), "SPRING_REQUIRES_NEW_IN_LOOP")).isNotNull();
    }

    @Test
    void doesNotFlagRequiresNewMethodCalledOutsideLoop() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/BatchService.java",
                """
                package com.example;
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Propagation;
                import org.springframework.transaction.annotation.Transactional;
                @Service
                public class BatchService {
                    private final ItemService itemService = null;
                    public void run(String item) {
                        itemService.process(item);
                    }
                }
                @Service
                class ItemService {
                    @Transactional(propagation = Propagation.REQUIRES_NEW)
                    public void process(String item) {}
                }
                """);

        assertThat(byRule(findings(), "SPRING_REQUIRES_NEW_IN_LOOP")).isNull();
    }

    @Test
    void doesNotFlagPlainTransactionalMethodInLoop() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/BatchService.java",
                """
                package com.example;
                import java.util.List;
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;
                @Service
                public class BatchService {
                    private final ItemService itemService = null;
                    public void run(List<String> items) {
                        for (String item : items) {
                            itemService.process(item);
                        }
                    }
                }
                @Service
                class ItemService {
                    @Transactional
                    public void process(String item) {}
                }
                """);

        assertThat(byRule(findings(), "SPRING_REQUIRES_NEW_IN_LOOP")).isNull();
    }

    // ── SPRING_EXECUTORS_UNBOUNDED_THREAD_POOL ────────────────────────────────

    @Test
    void flagsCachedThreadPool() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/PoolConfig.java",
                """
                package com.example;
                import java.util.concurrent.ExecutorService;
                import java.util.concurrent.Executors;
                public class PoolConfig {
                    private final ExecutorService pool = Executors.newCachedThreadPool();
                }
                """);

        assertThat(byRule(findings(), "SPRING_EXECUTORS_UNBOUNDED_THREAD_POOL")).isNotNull();
    }

    @Test
    void doesNotFlagFixedThreadPool() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/PoolConfig.java",
                """
                package com.example;
                import java.util.concurrent.ExecutorService;
                import java.util.concurrent.Executors;
                public class PoolConfig {
                    private final ExecutorService pool = Executors.newFixedThreadPool(8);
                }
                """);

        assertThat(byRule(findings(), "SPRING_EXECUTORS_UNBOUNDED_THREAD_POOL")).isNull();
    }
}
