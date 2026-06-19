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

class ObservabilityGapFindingAnalyzerTest {

    @TempDir Path repoRoot;

    private ObservabilityGapFindingAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new ObservabilityGapFindingAnalyzer();
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

    // ── No sources ────────────────────────────────────────────────────────────

    @Test
    void returnsEmptyListWhenNoMainDirectory() {
        assertThat(findings()).isEmpty();
    }

    // ── SPRING_ASYNC_NO_OBSERVABILITY ─────────────────────────────────────────

    @Test
    void flagsAsyncMethodWithNoObservability() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/NotificationService.java",
                """
                package com.example;
                import org.springframework.scheduling.annotation.Async;
                public class NotificationService {
                    @Async
                    public void sendEmail(String to) {}
                }
                """);

        Finding f = byRule(findings(), "SPRING_ASYNC_NO_OBSERVABILITY");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("NotificationService#sendEmail");
    }

    @Test
    void doesNotFlagAsyncMethodWithObserved() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/NotificationService.java",
                """
                package com.example;
                import org.springframework.scheduling.annotation.Async;
                import io.micrometer.observation.annotation.Observed;
                public class NotificationService {
                    @Async
                    @Observed(name = "send.email")
                    public void sendEmail(String to) {}
                }
                """);

        assertThat(byRule(findings(), "SPRING_ASYNC_NO_OBSERVABILITY")).isNull();
    }

    @Test
    void doesNotFlagAsyncMethodWithTimed() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/NotificationService.java",
                """
                package com.example;
                import org.springframework.scheduling.annotation.Async;
                import io.micrometer.core.annotation.Timed;
                public class NotificationService {
                    @Async
                    @Timed("send.email")
                    public void sendEmail(String to) {}
                }
                """);

        assertThat(byRule(findings(), "SPRING_ASYNC_NO_OBSERVABILITY")).isNull();
    }

    // ── SPRING_EVENT_LISTENER_NO_OBSERVABILITY ────────────────────────────────

    @Test
    void flagsEventListenerWithNoObservability() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/OrderEventHandler.java",
                """
                package com.example;
                import org.springframework.context.event.EventListener;
                public class OrderEventHandler {
                    @EventListener
                    public void onOrderPlaced(OrderPlacedEvent event) {}
                }
                """);

        Finding f = byRule(findings(), "SPRING_EVENT_LISTENER_NO_OBSERVABILITY");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("OrderEventHandler#onOrderPlaced");
    }

    @Test
    void flagsTransactionalEventListenerWithNoObservability() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/OrderEventHandler.java",
                """
                package com.example;
                import org.springframework.transaction.event.TransactionalEventListener;
                public class OrderEventHandler {
                    @TransactionalEventListener
                    public void onOrderCommitted(OrderPlacedEvent event) {}
                }
                """);

        Finding f = byRule(findings(), "SPRING_EVENT_LISTENER_NO_OBSERVABILITY");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("OrderEventHandler#onOrderCommitted");
    }

    @Test
    void doesNotFlagEventListenerWithObserved() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/OrderEventHandler.java",
                """
                package com.example;
                import org.springframework.context.event.EventListener;
                import io.micrometer.observation.annotation.Observed;
                public class OrderEventHandler {
                    @EventListener
                    @Observed(name = "order.placed.handler")
                    public void onOrderPlaced(OrderPlacedEvent event) {}
                }
                """);

        assertThat(byRule(findings(), "SPRING_EVENT_LISTENER_NO_OBSERVABILITY")).isNull();
    }

    // ── SPRING_EXCEPTION_HANDLER_NO_METRICS ───────────────────────────────────

    @Test
    void flagsExceptionHandlerWithNoMetricsInControllerAdvice() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/GlobalExceptionHandler.java",
                """
                package com.example;
                import org.springframework.web.bind.annotation.ControllerAdvice;
                import org.springframework.web.bind.annotation.ExceptionHandler;
                @ControllerAdvice
                public class GlobalExceptionHandler {
                    @ExceptionHandler(RuntimeException.class)
                    public String handle(RuntimeException ex) {
                        return "error";
                    }
                }
                """);

        Finding f = byRule(findings(), "SPRING_EXCEPTION_HANDLER_NO_METRICS");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("GlobalExceptionHandler#handle");
    }

    @Test
    void doesNotFlagExceptionHandlerWhenClassHasMeterRegistryField() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/GlobalExceptionHandler.java",
                """
                package com.example;
                import io.micrometer.core.instrument.MeterRegistry;
                import org.springframework.web.bind.annotation.ControllerAdvice;
                import org.springframework.web.bind.annotation.ExceptionHandler;
                @ControllerAdvice
                public class GlobalExceptionHandler {
                    private final MeterRegistry meterRegistry;
                    public GlobalExceptionHandler(MeterRegistry meterRegistry) {
                        this.meterRegistry = meterRegistry;
                    }
                    @ExceptionHandler(RuntimeException.class)
                    public String handle(RuntimeException ex) {
                        meterRegistry.counter("errors").increment();
                        return "error";
                    }
                }
                """);

        assertThat(byRule(findings(), "SPRING_EXCEPTION_HANDLER_NO_METRICS")).isNull();
    }

    @Test
    void doesNotFlagExceptionHandlerOutsideControllerAdvice() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/SomeController.java",
                """
                package com.example;
                import org.springframework.web.bind.annotation.ExceptionHandler;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                public class SomeController {
                    @ExceptionHandler(RuntimeException.class)
                    public String handle(RuntimeException ex) {
                        return "error";
                    }
                }
                """);

        assertThat(byRule(findings(), "SPRING_EXCEPTION_HANDLER_NO_METRICS")).isNull();
    }

    // ── SPRING_OBSERVED_ON_PRIVATE_METHOD ─────────────────────────────────────

    @Test
    void flagsObservedOnPrivateMethod() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/OrderService.java",
                """
                package com.example;
                import io.micrometer.observation.annotation.Observed;
                public class OrderService {
                    @Observed(name = "compute.total")
                    private double computeTotal(Order order) { return 0; }
                }
                """);

        Finding f = byRule(findings(), "SPRING_OBSERVED_ON_PRIVATE_METHOD");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("OrderService#computeTotal");
        assertThat(f.message()).contains("private");
    }

    @Test
    void doesNotFlagObservedOnPublicMethod() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/OrderService.java",
                """
                package com.example;
                import io.micrometer.observation.annotation.Observed;
                public class OrderService {
                    @Observed(name = "place.order")
                    public void placeOrder(Order order) {}
                }
                """);

        assertThat(byRule(findings(), "SPRING_OBSERVED_ON_PRIVATE_METHOD")).isNull();
    }

    // ── SPRING_WEBCLIENT_MANUALLY_CONSTRUCTED ─────────────────────────────────

    @Test
    void flagsWebClientCreateCall() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ExternalApiClient.java",
                """
                package com.example;
                import org.springframework.web.reactive.function.client.WebClient;
                public class ExternalApiClient {
                    private final WebClient client = WebClient.create("https://api.example.com");
                }
                """);

        Finding f = byRule(findings(), "SPRING_WEBCLIENT_MANUALLY_CONSTRUCTED");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("create");
    }

    @Test
    void flagsWebClientBuilderStaticCall() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ExternalApiClient.java",
                """
                package com.example;
                import org.springframework.web.reactive.function.client.WebClient;
                public class ExternalApiClient {
                    private final WebClient client = WebClient.builder()
                        .baseUrl("https://api.example.com")
                        .build();
                }
                """);

        Finding f = byRule(findings(), "SPRING_WEBCLIENT_MANUALLY_CONSTRUCTED");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("builder");
    }

    @Test
    void doesNotFlagFileWithNoWebClientImport() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/SomeService.java",
                """
                package com.example;
                public class SomeService {
                    public void create(String url) {}
                    public void builder() {}
                }
                """);

        assertThat(byRule(findings(), "SPRING_WEBCLIENT_MANUALLY_CONSTRUCTED")).isNull();
    }

    // ── SPRING_ASYNC_NON_FUTURE_RETURN ────────────────────────────────────────

    @Test
    void flagsAsyncMethodReturningString() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ReportService.java",
                """
                package com.example;
                import org.springframework.scheduling.annotation.Async;
                public class ReportService {
                    @Async
                    public String generateReport() { return "report"; }
                }
                """);

        Finding f = byRule(findings(), "SPRING_ASYNC_NON_FUTURE_RETURN");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("ReportService#generateReport");
        assertThat(f.message()).contains("String");
    }

    @Test
    void flagsAsyncMethodReturningCustomObject() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ReportService.java",
                """
                package com.example;
                import org.springframework.scheduling.annotation.Async;
                public class ReportService {
                    @Async
                    public Object process() { return new Object(); }
                }
                """);

        Finding f = byRule(findings(), "SPRING_ASYNC_NON_FUTURE_RETURN");
        assertThat(f).isNotNull();
    }

    @Test
    void doesNotFlagAsyncMethodReturningVoid() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/NotificationService.java",
                """
                package com.example;
                import org.springframework.scheduling.annotation.Async;
                public class NotificationService {
                    @Async
                    public void sendEmail(String to) {}
                }
                """);

        assertThat(byRule(findings(), "SPRING_ASYNC_NON_FUTURE_RETURN")).isNull();
    }

    @Test
    void doesNotFlagAsyncMethodReturningCompletableFuture() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ReportService.java",
                """
                package com.example;
                import org.springframework.scheduling.annotation.Async;
                import java.util.concurrent.CompletableFuture;
                public class ReportService {
                    @Async
                    public CompletableFuture<String> generateReport() {
                        return CompletableFuture.completedFuture("report");
                    }
                }
                """);

        assertThat(byRule(findings(), "SPRING_ASYNC_NON_FUTURE_RETURN")).isNull();
    }

    @Test
    void doesNotFlagAsyncMethodReturningFullyQualifiedCompletableFuture() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ReportService.java",
                """
                package com.example;
                import org.springframework.scheduling.annotation.Async;
                public class ReportService {
                    @Async
                    public java.util.concurrent.CompletableFuture<String> generateReport() {
                        return java.util.concurrent.CompletableFuture.completedFuture("report");
                    }
                }
                """);

        assertThat(byRule(findings(), "SPRING_ASYNC_NON_FUTURE_RETURN")).isNull();
    }

    @Test
    void doesNotFlagAsyncMethodReturningFuture() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ReportService.java",
                """
                package com.example;
                import org.springframework.scheduling.annotation.Async;
                import java.util.concurrent.Future;
                public class ReportService {
                    @Async
                    public Future<String> generateReport() { return null; }
                }
                """);

        assertThat(byRule(findings(), "SPRING_ASYNC_NON_FUTURE_RETURN")).isNull();
    }

    @Test
    void doesNotFlagNonAsyncMethodReturningString() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ReportService.java",
                """
                package com.example;
                public class ReportService {
                    public String generateReport() { return "report"; }
                }
                """);

        assertThat(byRule(findings(), "SPRING_ASYNC_NON_FUTURE_RETURN")).isNull();
    }
}
