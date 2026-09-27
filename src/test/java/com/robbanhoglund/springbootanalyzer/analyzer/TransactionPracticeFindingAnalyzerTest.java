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

class TransactionPracticeFindingAnalyzerTest {

    @TempDir Path repoRoot;

    private TransactionPracticeFindingAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new TransactionPracticeFindingAnalyzer();
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

    // ── SPRING_ASYNC_TRANSACTIONAL ────────────────────────────────────────────

    @Test
    void flagsAsyncAndTransactionalOnSameMethod() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/NotificationService.java",
                """
                package com.example;
                import org.springframework.scheduling.annotation.Async;
                import org.springframework.transaction.annotation.Transactional;
                public class NotificationService {
                    @Async
                    @Transactional
                    public void sendNotification() {}
                }
                """);

        Finding f = byRule(findings(), "SPRING_ASYNC_TRANSACTIONAL");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("NotificationService#sendNotification");
        assertThat(f.message()).contains("@Async").contains("@Transactional");
    }

    @Test
    void flagsTransactionalThenAsyncOrderDoesNotMatter() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ReportService.java",
                """
                package com.example;
                import org.springframework.scheduling.annotation.Async;
                import org.springframework.transaction.annotation.Transactional;
                public class ReportService {
                    @Transactional
                    @Async
                    public void generateReport() {}
                }
                """);

        Finding f = byRule(findings(), "SPRING_ASYNC_TRANSACTIONAL");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("ReportService#generateReport");
    }

    @Test
    void flagsJakartaTransactionalWithAsync() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/AuditService.java",
                """
                package com.example;
                import org.springframework.scheduling.annotation.Async;
                import jakarta.transaction.Transactional;
                public class AuditService {
                    @Async
                    @Transactional
                    public void logEvent() {}
                }
                """);

        Finding f = byRule(findings(), "SPRING_ASYNC_TRANSACTIONAL");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("AuditService#logEvent");
    }

    @Test
    void doesNotFlagAsyncWithoutTransactional() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/NotificationService.java",
                """
                package com.example;
                import org.springframework.scheduling.annotation.Async;
                public class NotificationService {
                    @Async
                    public void sendNotification() {}
                }
                """);

        assertThat(byRule(findings(), "SPRING_ASYNC_TRANSACTIONAL")).isNull();
    }

    @Test
    void doesNotFlagTransactionalWithoutAsync() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/OrderService.java",
                """
                package com.example;
                import org.springframework.transaction.annotation.Transactional;
                public class OrderService {
                    @Transactional
                    public void saveOrder() {}
                }
                """);

        assertThat(byRule(findings(), "SPRING_ASYNC_TRANSACTIONAL")).isNull();
    }

    // ── SPRING_TRANSACTIONAL_ON_POSTCONSTRUCT ─────────────────────────────────

    @Test
    void flagsTransactionalOnPostConstructMethod() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/CacheWarmer.java",
                """
                package com.example;
                import jakarta.annotation.PostConstruct;
                import org.springframework.transaction.annotation.Transactional;
                import org.springframework.stereotype.Service;
                @Service
                public class CacheWarmer {
                    @PostConstruct
                    @Transactional
                    public void warm() {}
                }
                """);

        Finding f = byRule(findings(), "SPRING_TRANSACTIONAL_ON_POSTCONSTRUCT");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("CacheWarmer#warm");
        assertThat(f.message()).contains("@PostConstruct").contains("@Transactional");
    }

    @Test
    void doesNotFlagPostConstructWithoutTransactional() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/CacheWarmer.java",
                """
                package com.example;
                import jakarta.annotation.PostConstruct;
                import org.springframework.stereotype.Service;
                @Service
                public class CacheWarmer {
                    @PostConstruct
                    public void warm() {}
                }
                """);

        assertThat(byRule(findings(), "SPRING_TRANSACTIONAL_ON_POSTCONSTRUCT")).isNull();
    }

    private static List<Finding> allByRule(List<Finding> findings, String ruleId) {
        return findings.stream().filter(f -> ruleId.equals(f.ruleId())).toList();
    }

    // ── SPRING_TRANSACTIONAL_SYNCHRONIZED ─────────────────────────────────────

    @Test
    void flagsSynchronizedTransactionalMethods() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/StockService.java",
                """
                package com.example;
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;
                @Service
                public class StockService {
                    @Transactional
                    public synchronized void reserve(String sku) {}

                    public synchronized void notTransactional() {}

                    @Transactional
                    public void notSynchronized() {}
                }
                @Service
                @Transactional
                class LedgerService {
                    public synchronized void book() {}

                    private synchronized void helper() {}
                }
                """);

        assertThat(allByRule(findings(), "SPRING_TRANSACTIONAL_SYNCHRONIZED"))
                .extracting(Finding::target)
                .containsExactly("StockService#reserve", "LedgerService#book");
    }

    // ── SPRING_TX_EVENT_LISTENER_NO_TRANSACTION ───────────────────────────────

    private void writeOrderListener(String listenerAnnotation) throws IOException {
        writeSourceFile(
                "src/main/java/com/example/OrderListener.java",
                """
                package com.example;
                import org.springframework.stereotype.Component;
                import org.springframework.transaction.event.TransactionalEventListener;
                @Component
                public class OrderListener {
                    %s
                    public void on(OrderPlaced event) {}
                }
                record OrderPlaced(String id) {}
                """
                        .formatted(listenerAnnotation));
    }

    @Test
    void flagsTransactionalEventPublishedOutsideTransaction() throws IOException {
        writeOrderListener("@TransactionalEventListener");
        writeSourceFile(
                "src/main/java/com/example/OrderService.java",
                """
                package com.example;
                import org.springframework.context.ApplicationEventPublisher;
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;
                @Service
                public class OrderService {
                    private final ApplicationEventPublisher publisher;

                    OrderService(ApplicationEventPublisher publisher) {
                        this.publisher = publisher;
                    }

                    public void place(String id) {
                        publisher.publishEvent(new OrderPlaced(id));
                    }

                    @Transactional
                    public void placeInTransaction(String id) {
                        publisher.publishEvent(new OrderPlaced(id));
                        audit(id);
                    }

                    void audit(String id) {
                        OrderPlaced event = new OrderPlaced(id);
                        publisher.publishEvent(event);
                    }
                }
                """);

        assertThat(allByRule(findings(), "SPRING_TX_EVENT_LISTENER_NO_TRANSACTION"))
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.target()).isEqualTo("OrderService#place");
                            assertThat(finding.message()).contains("publishes OrderPlaced");
                            assertThat(finding.primaryLocation().startLine()).isEqualTo(14);
                        });
    }

    @Test
    void doesNotFlagFallbackListenersOrPublishersCalledFromTransactions() throws IOException {
        writeOrderListener("@TransactionalEventListener(fallbackExecution = true)");
        writeSourceFile(
                "src/main/java/com/example/OrderService.java",
                """
                package com.example;
                import org.springframework.context.ApplicationEventPublisher;
                import org.springframework.stereotype.Service;
                @Service
                public class OrderService {
                    private ApplicationEventPublisher publisher;

                    public void place(String id) {
                        publisher.publishEvent(new OrderPlaced(id));
                    }
                }
                """);

        assertThat(allByRule(findings(), "SPRING_TX_EVENT_LISTENER_NO_TRANSACTION")).isEmpty();

        writeOrderListener("@TransactionalEventListener");
        writeSourceFile(
                "src/main/java/com/example/CheckoutService.java",
                """
                package com.example;
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;
                @Service
                public class CheckoutService {
                    private OrderService orders;

                    @Transactional
                    public void checkout(String id) {
                        orders.place(id);
                    }
                }
                """);

        assertThat(allByRule(findings(), "SPRING_TX_EVENT_LISTENER_NO_TRANSACTION")).isEmpty();
    }
}
