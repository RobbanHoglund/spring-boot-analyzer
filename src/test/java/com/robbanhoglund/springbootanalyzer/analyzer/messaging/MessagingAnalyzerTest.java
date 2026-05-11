package com.robbanhoglund.springbootanalyzer.analyzer.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.robbanhoglund.springbootanalyzer.analyzer.model.messaging.MessageListenerEndpoint;
import com.robbanhoglund.springbootanalyzer.analyzer.model.messaging.MessagingAnalysis;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessagingAnalyzerTest {

    private final MessagingAnalyzer analyzer = new MessagingAnalyzer();

    @TempDir Path tempDir;

    @Test
    void returnsEmptyWhenNoSourceRoot() {
        MessagingAnalysis result = analyzer.analyze(tempDir);

        assertThat(result.listeners()).isEmpty();
    }

    @Test
    void detectsKafkaListenerWithSingleTopic() throws IOException {
        Path pkg = Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Files.writeString(
                pkg.resolve("OrderConsumer.java"),
                """
                package com.example;
                import org.springframework.kafka.annotation.KafkaListener;
                import org.springframework.stereotype.Component;

                @Component
                public class OrderConsumer {
                    @KafkaListener(topics = "orders", groupId = "order-service")
                    public void onOrder(String message) {}
                }
                """);

        MessagingAnalysis result = analyzer.analyze(tempDir);

        assertThat(result.listeners()).hasSize(1);
        MessageListenerEndpoint endpoint = result.listeners().get(0);
        assertThat(endpoint.listenerType()).isEqualTo("KAFKA");
        assertThat(endpoint.destinations()).containsExactly("orders");
        assertThat(endpoint.groupId()).isEqualTo("order-service");
        assertThat(endpoint.className()).isEqualTo("OrderConsumer");
        assertThat(endpoint.methodName()).isEqualTo("onOrder");
        assertThat(endpoint.sourceFile()).endsWith("OrderConsumer.java");
    }

    @Test
    void detectsKafkaListenerWithMultipleTopics() throws IOException {
        Path pkg = Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Files.writeString(
                pkg.resolve("MultiTopicConsumer.java"),
                """
                package com.example;
                import org.springframework.kafka.annotation.KafkaListener;
                import org.springframework.stereotype.Component;

                @Component
                public class MultiTopicConsumer {
                    @KafkaListener(topics = {"orders", "payments"})
                    public void onEvent(String message) {}
                }
                """);

        MessagingAnalysis result = analyzer.analyze(tempDir);

        assertThat(result.listeners()).hasSize(1);
        assertThat(result.listeners().get(0).destinations())
                .containsExactlyInAnyOrder("orders", "payments");
    }

    @Test
    void detectsRabbitListener() throws IOException {
        Path pkg = Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Files.writeString(
                pkg.resolve("NotificationConsumer.java"),
                """
                package com.example;
                import org.springframework.amqp.rabbit.annotation.RabbitListener;
                import org.springframework.stereotype.Component;

                @Component
                public class NotificationConsumer {
                    @RabbitListener(queues = "notifications")
                    public void onNotification(String message) {}
                }
                """);

        MessagingAnalysis result = analyzer.analyze(tempDir);

        assertThat(result.listeners()).hasSize(1);
        MessageListenerEndpoint endpoint = result.listeners().get(0);
        assertThat(endpoint.listenerType()).isEqualTo("RABBIT");
        assertThat(endpoint.destinations()).containsExactly("notifications");
    }

    @Test
    void detectsJmsListener() throws IOException {
        Path pkg = Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Files.writeString(
                pkg.resolve("JmsConsumer.java"),
                """
                package com.example;
                import org.springframework.jms.annotation.JmsListener;
                import org.springframework.stereotype.Component;

                @Component
                public class JmsConsumer {
                    @JmsListener(destination = "invoice-queue")
                    public void onInvoice(String message) {}
                }
                """);

        MessagingAnalysis result = analyzer.analyze(tempDir);

        assertThat(result.listeners()).hasSize(1);
        MessageListenerEndpoint endpoint = result.listeners().get(0);
        assertThat(endpoint.listenerType()).isEqualTo("JMS");
        assertThat(endpoint.destinations()).containsExactly("invoice-queue");
    }

    @Test
    void detectsSqsListener() throws IOException {
        Path pkg = Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Files.writeString(
                pkg.resolve("SqsConsumer.java"),
                """
                package com.example;
                import io.awspring.cloud.sqs.annotation.SqsListener;
                import org.springframework.stereotype.Component;

                @Component
                public class SqsConsumer {
                    @SqsListener("order-events")
                    public void onOrderEvent(String message) {}
                }
                """);

        MessagingAnalysis result = analyzer.analyze(tempDir);

        assertThat(result.listeners()).hasSize(1);
        MessageListenerEndpoint endpoint = result.listeners().get(0);
        assertThat(endpoint.listenerType()).isEqualTo("SQS");
        assertThat(endpoint.destinations()).containsExactly("order-events");
    }

    @Test
    void collectsListenersAcrossMultipleFiles() throws IOException {
        Path pkg = Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Files.writeString(
                pkg.resolve("KafkaConsumer.java"),
                """
                package com.example;
                import org.springframework.kafka.annotation.KafkaListener;
                @org.springframework.stereotype.Component
                public class KafkaConsumer {
                    @KafkaListener(topics = "topic-a")
                    public void consume(String msg) {}
                }
                """);
        Files.writeString(
                pkg.resolve("RabbitConsumer.java"),
                """
                package com.example;
                import org.springframework.amqp.rabbit.annotation.RabbitListener;
                @org.springframework.stereotype.Component
                public class RabbitConsumer {
                    @RabbitListener(queues = "queue-a")
                    public void consume(String msg) {}
                    @RabbitListener(queues = "queue-b")
                    public void consumeB(String msg) {}
                }
                """);

        MessagingAnalysis result = analyzer.analyze(tempDir);

        assertThat(result.listeners()).hasSize(3);
        assertThat(result.listeners())
                .extracting(MessageListenerEndpoint::listenerType)
                .containsExactlyInAnyOrder("KAFKA", "RABBIT", "RABBIT");
    }
}
