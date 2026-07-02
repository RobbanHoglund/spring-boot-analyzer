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

class SchedulingPracticeFindingAnalyzerTest {

    @TempDir Path repoRoot;

    private SchedulingPracticeFindingAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new SchedulingPracticeFindingAnalyzer();
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

    // ── SPRING_SCHEDULED_WITHOUT_ENABLE_SCHEDULING ────────────────────────────

    @Test
    void flagsScheduledWhenEnableSchedulingMissing() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/CleanupJob.java",
                """
                package com.example;
                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;
                @Component
                public class CleanupJob {
                    @Scheduled(fixedRate = 60000)
                    public void purge() {}
                }
                """);

        Finding f = byRule(findings(), "SPRING_SCHEDULED_WITHOUT_ENABLE_SCHEDULING");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("CleanupJob#purge");
        assertThat(f.message()).contains("@EnableScheduling");
    }

    @Test
    void doesNotFlagScheduledWhenEnableSchedulingPresent() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/CleanupJob.java",
                """
                package com.example;
                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;
                @Component
                public class CleanupJob {
                    @Scheduled(fixedRate = 60000)
                    public void purge() {}
                }
                """);
        writeSourceFile(
                "src/main/java/com/example/SchedulingConfig.java",
                """
                package com.example;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.scheduling.annotation.EnableScheduling;
                @Configuration
                @EnableScheduling
                public class SchedulingConfig {}
                """);

        assertThat(byRule(findings(), "SPRING_SCHEDULED_WITHOUT_ENABLE_SCHEDULING")).isNull();
    }

    @Test
    void doesNotFlagScheduledWhenNoScheduledMethods() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/PlainService.java",
                """
                package com.example;
                import org.springframework.stereotype.Service;
                @Service
                public class PlainService {
                    public void doWork() {}
                }
                """);

        assertThat(byRule(findings(), "SPRING_SCHEDULED_WITHOUT_ENABLE_SCHEDULING")).isNull();
    }

    // ── SPRING_ASYNC_WITHOUT_ENABLE_ASYNC ─────────────────────────────────────

    @Test
    void flagsAsyncWhenEnableAsyncMissing() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/MailService.java",
                """
                package com.example;
                import org.springframework.scheduling.annotation.Async;
                import org.springframework.stereotype.Service;
                @Service
                public class MailService {
                    @Async
                    public void send() {}
                }
                """);

        Finding f = byRule(findings(), "SPRING_ASYNC_WITHOUT_ENABLE_ASYNC");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("MailService#send");
        assertThat(f.message()).contains("@EnableAsync");
    }

    @Test
    void doesNotFlagAsyncWhenEnableAsyncPresent() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/MailService.java",
                """
                package com.example;
                import org.springframework.scheduling.annotation.Async;
                import org.springframework.stereotype.Service;
                @Service
                public class MailService {
                    @Async
                    public void send() {}
                }
                """);
        writeSourceFile(
                "src/main/java/com/example/AsyncConfig.java",
                """
                package com.example;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.scheduling.annotation.EnableAsync;
                @Configuration
                @EnableAsync
                public class AsyncConfig {}
                """);

        assertThat(byRule(findings(), "SPRING_ASYNC_WITHOUT_ENABLE_ASYNC")).isNull();
    }

    @Test
    void doesNotFlagAsyncWhenOnlyPrivateAsyncMethodExists() throws IOException {
        // A private @Async method can never be proxied (covered by SPRING_ASYNC_PROXY_BYPASS), so
        // it must not, on its own, trigger the "missing @EnableAsync" rule.
        writeSourceFile(
                "src/main/java/com/example/MailService.java",
                """
                package com.example;
                import org.springframework.scheduling.annotation.Async;
                import org.springframework.stereotype.Service;
                @Service
                public class MailService {
                    @Async
                    private void send() {}
                }
                """);

        assertThat(byRule(findings(), "SPRING_ASYNC_WITHOUT_ENABLE_ASYNC")).isNull();
    }

    // ── SPRING_SCHEDULED_METHOD_INVALID_SIGNATURE ─────────────────────────────

    @Test
    void flagsScheduledMethodWithParameters() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ReportJob.java",
                """
                package com.example;
                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;
                @Component
                public class ReportJob {
                    @Scheduled(cron = "0 0 * * * *")
                    public void run(String argument) {}
                }
                """);

        Finding f = byRule(findings(), "SPRING_SCHEDULED_METHOD_INVALID_SIGNATURE");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("ReportJob#run");
    }

    @Test
    void doesNotFlagNoArgScheduledMethod() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ReportJob.java",
                """
                package com.example;
                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;
                @Component
                public class ReportJob {
                    @Scheduled(cron = "0 0 * * * *")
                    public void run() {}
                }
                """);

        assertThat(byRule(findings(), "SPRING_SCHEDULED_METHOD_INVALID_SIGNATURE")).isNull();
    }

    // ── SPRING_ASYNC_SELF_INVOCATION ──────────────────────────────────────────

    @Test
    void flagsAsyncMethodCalledViaSelfInvocation() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/OrderService.java",
                """
                package com.example;
                import org.springframework.scheduling.annotation.Async;
                import org.springframework.scheduling.annotation.EnableAsync;
                import org.springframework.stereotype.Service;
                @Service
                @EnableAsync
                public class OrderService {
                    public void placeOrder() {
                        notifyAsync();
                    }
                    @Async
                    public void notifyAsync() {}
                }
                """);

        Finding f = byRule(findings(), "SPRING_ASYNC_SELF_INVOCATION");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("OrderService#placeOrder");
        assertThat(f.message()).contains("notifyAsync");
    }

    // ── SPRING_RETRYABLE_WITHOUT_ENABLE_RETRY ─────────────────────────────────

    @Test
    void flagsRetryableWhenEnableRetryMissing() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/PaymentClient.java",
                """
                package com.example;
                import org.springframework.retry.annotation.Retryable;
                import org.springframework.stereotype.Service;
                @Service
                public class PaymentClient {
                    @Retryable
                    public void charge() {}
                }
                """);

        Finding f = byRule(findings(), "SPRING_RETRYABLE_WITHOUT_ENABLE_RETRY");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("PaymentClient#charge");
        assertThat(f.message()).contains("@EnableRetry");
    }

    @Test
    void doesNotFlagRetryableWhenEnableRetryPresent() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/PaymentClient.java",
                """
                package com.example;
                import org.springframework.retry.annotation.Retryable;
                import org.springframework.stereotype.Service;
                @Service
                public class PaymentClient {
                    @Retryable
                    public void charge() {}
                }
                """);
        writeSourceFile(
                "src/main/java/com/example/RetryConfig.java",
                """
                package com.example;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.retry.annotation.EnableRetry;
                @Configuration
                @EnableRetry
                public class RetryConfig {}
                """);

        assertThat(byRule(findings(), "SPRING_RETRYABLE_WITHOUT_ENABLE_RETRY")).isNull();
    }

    @Test
    void doesNotFlagRetryableFromOtherLibraryWithoutSpringRetryImport() throws IOException {
        // A same-named annotation from another library must not trigger the spring-retry rule.
        writeSourceFile(
                "src/main/java/com/example/PaymentClient.java",
                """
                package com.example;
                import com.acme.resilience.Retryable;
                import org.springframework.stereotype.Service;
                @Service
                public class PaymentClient {
                    @Retryable
                    public void charge() {}
                }
                """);

        assertThat(byRule(findings(), "SPRING_RETRYABLE_WITHOUT_ENABLE_RETRY")).isNull();
    }

    // ── SPRING_SCHEDULED_CRON_INVALID_EXPRESSION ──────────────────────────────

    @Test
    void flagsFiveFieldUnixCronExpression() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/NightlyJob.java",
                """
                package com.example;
                import org.springframework.scheduling.annotation.EnableScheduling;
                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;
                @Component
                @EnableScheduling
                public class NightlyJob {
                    @Scheduled(cron = "0 2 * * *")
                    public void run() {}
                }
                """);

        Finding f = byRule(findings(), "SPRING_SCHEDULED_CRON_INVALID_EXPRESSION");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("NightlyJob#run");
        assertThat(f.message()).contains("5 fields");
    }

    @Test
    void flagsUnknownCronMacro() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/NightlyJob.java",
                """
                package com.example;
                import org.springframework.scheduling.annotation.EnableScheduling;
                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;
                @Component
                @EnableScheduling
                public class NightlyJob {
                    @Scheduled(cron = "@reboot")
                    public void run() {}
                }
                """);

        Finding f = byRule(findings(), "SPRING_SCHEDULED_CRON_INVALID_EXPRESSION");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("@reboot");
    }

    @Test
    void doesNotFlagValidSixFieldCronExpression() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/NightlyJob.java",
                """
                package com.example;
                import org.springframework.scheduling.annotation.EnableScheduling;
                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;
                @Component
                @EnableScheduling
                public class NightlyJob {
                    @Scheduled(cron = "0 0 2 * * *")
                    public void run() {}
                }
                """);

        assertThat(byRule(findings(), "SPRING_SCHEDULED_CRON_INVALID_EXPRESSION")).isNull();
    }

    @Test
    void doesNotFlagCronPlaceholderOrKnownMacroOrDisabledSentinel() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/Jobs.java",
                """
                package com.example;
                import org.springframework.scheduling.annotation.EnableScheduling;
                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;
                @Component
                @EnableScheduling
                public class Jobs {
                    @Scheduled(cron = "${jobs.cleanup.cron}")
                    public void fromProperty() {}

                    @Scheduled(cron = "@daily")
                    public void daily() {}

                    @Scheduled(cron = "-")
                    public void disabled() {}
                }
                """);

        assertThat(byRule(findings(), "SPRING_SCHEDULED_CRON_INVALID_EXPRESSION")).isNull();
    }

    @Test
    void doesNotFlagAsyncMethodWithoutSelfInvocation() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/OrderService.java",
                """
                package com.example;
                import org.springframework.scheduling.annotation.Async;
                import org.springframework.scheduling.annotation.EnableAsync;
                import org.springframework.stereotype.Service;
                @Service
                @EnableAsync
                public class OrderService {
                    @Async
                    public void notifyAsync() {}
                }
                """);

        assertThat(byRule(findings(), "SPRING_ASYNC_SELF_INVOCATION")).isNull();
    }
}
