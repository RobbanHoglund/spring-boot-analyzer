package com.robbanhoglund.springbootanalyzer.analyzer.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.robbanhoglund.springbootanalyzer.analyzer.model.scheduling.AsyncMethodEndpoint;
import com.robbanhoglund.springbootanalyzer.analyzer.model.scheduling.ScheduledTaskEndpoint;
import com.robbanhoglund.springbootanalyzer.analyzer.model.scheduling.SchedulingAnalysis;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchedulingAnalyzerTest {

    private final SchedulingAnalyzer analyzer = new SchedulingAnalyzer();

    @TempDir Path tempDir;

    @Test
    void returnsEmptyWhenNoSourceRoot() {
        SchedulingAnalysis result = analyzer.analyze(tempDir);

        assertThat(result.scheduledTasks()).isEmpty();
        assertThat(result.asyncMethods()).isEmpty();
        assertThat(result.enableSchedulingPresent()).isFalse();
        assertThat(result.enableAsyncPresent()).isFalse();
    }

    @Test
    void detectsCronScheduledMethod() throws IOException {
        Path pkg = Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Files.writeString(
                pkg.resolve("ReportJob.java"),
                """
                package com.example;
                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;

                @Component
                public class ReportJob {
                    @Scheduled(cron = "0 0 1 * * *", zone = "Europe/Stockholm")
                    public void generateDailyReport() {}
                }
                """);

        SchedulingAnalysis result = analyzer.analyze(tempDir);

        assertThat(result.scheduledTasks()).hasSize(1);
        ScheduledTaskEndpoint task = result.scheduledTasks().get(0);
        assertThat(task.className()).isEqualTo("ReportJob");
        assertThat(task.methodName()).isEqualTo("generateDailyReport");
        assertThat(task.scheduleType()).isEqualTo("CRON");
        assertThat(task.scheduleValue()).isEqualTo("0 0 1 * * *");
        assertThat(task.zone()).isEqualTo("Europe/Stockholm");
        assertThat(task.sourceFile()).endsWith("ReportJob.java");
    }

    @Test
    void detectsFixedRateScheduledMethod() throws IOException {
        Path pkg = Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Files.writeString(
                pkg.resolve("PollingJob.java"),
                """
                package com.example;
                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;

                @Component
                public class PollingJob {
                    @Scheduled(fixedRate = 5000)
                    public void poll() {}
                }
                """);

        SchedulingAnalysis result = analyzer.analyze(tempDir);

        assertThat(result.scheduledTasks()).hasSize(1);
        ScheduledTaskEndpoint task = result.scheduledTasks().get(0);
        assertThat(task.scheduleType()).isEqualTo("FIXED_RATE");
        assertThat(task.scheduleValue()).isEqualTo("5000");
        assertThat(task.zone()).isNull();
    }

    @Test
    void detectsFixedDelayScheduledMethod() throws IOException {
        Path pkg = Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Files.writeString(
                pkg.resolve("CleanupJob.java"),
                """
                package com.example;
                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;

                @Component
                public class CleanupJob {
                    @Scheduled(fixedDelay = 30000)
                    public void cleanup() {}
                }
                """);

        SchedulingAnalysis result = analyzer.analyze(tempDir);

        assertThat(result.scheduledTasks()).hasSize(1);
        assertThat(result.scheduledTasks().get(0).scheduleType()).isEqualTo("FIXED_DELAY");
    }

    @Test
    void detectsAsyncMethod() throws IOException {
        Path pkg = Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Files.writeString(
                pkg.resolve("EmailService.java"),
                """
                package com.example;
                import org.springframework.scheduling.annotation.Async;
                import org.springframework.stereotype.Service;

                @Service
                public class EmailService {
                    @Async
                    public void sendWelcomeEmail(String address) {}

                    @Async
                    private void internalTask() {}
                }
                """);

        SchedulingAnalysis result = analyzer.analyze(tempDir);

        assertThat(result.asyncMethods()).hasSize(1);
        AsyncMethodEndpoint method = result.asyncMethods().get(0);
        assertThat(method.className()).isEqualTo("EmailService");
        assertThat(method.methodName()).isEqualTo("sendWelcomeEmail");
    }

    @Test
    void detectsEnableSchedulingAndEnableAsync() throws IOException {
        Path pkg = Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Files.writeString(
                pkg.resolve("AppConfig.java"),
                """
                package com.example;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.scheduling.annotation.EnableAsync;
                import org.springframework.scheduling.annotation.EnableScheduling;

                @Configuration
                @EnableScheduling
                @EnableAsync
                public class AppConfig {}
                """);

        SchedulingAnalysis result = analyzer.analyze(tempDir);

        assertThat(result.enableSchedulingPresent()).isTrue();
        assertThat(result.enableAsyncPresent()).isTrue();
    }

    @Test
    void collectsMultipleScheduledTasksAcrossFiles() throws IOException {
        Path pkg = Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Files.writeString(
                pkg.resolve("JobA.java"),
                """
                package com.example;
                import org.springframework.scheduling.annotation.Scheduled;
                @org.springframework.stereotype.Component
                public class JobA {
                    @Scheduled(cron = "0 * * * * *")
                    public void runA() {}
                }
                """);
        Files.writeString(
                pkg.resolve("JobB.java"),
                """
                package com.example;
                import org.springframework.scheduling.annotation.Scheduled;
                @org.springframework.stereotype.Component
                public class JobB {
                    @Scheduled(fixedRate = 60000)
                    public void runB() {}
                    @Scheduled(fixedDelay = 10000)
                    public void runC() {}
                }
                """);

        SchedulingAnalysis result = analyzer.analyze(tempDir);

        assertThat(result.scheduledTasks()).hasSize(3);
    }
}
