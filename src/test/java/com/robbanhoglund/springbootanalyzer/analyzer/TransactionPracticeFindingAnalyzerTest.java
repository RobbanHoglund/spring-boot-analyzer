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
}
