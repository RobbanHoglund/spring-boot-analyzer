package com.example.springbootanalyzer.analyzer.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.springbootanalyzer.analyzer.model.BuildInfo;
import com.example.springbootanalyzer.analyzer.model.BuildTool;
import com.example.springbootanalyzer.analyzer.model.FindingSeverity;
import com.example.springbootanalyzer.analyzer.model.configuration.PropertyKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationAnalyzerTest {

    private final PropertyNameNormalizer propertyNameNormalizer = new PropertyNameNormalizer();
    private final ConfigurationAnalyzer analyzer = new ConfigurationAnalyzer(
            new ConfigurationFileScanner(),
            new PropertiesFileParser(),
            new YamlConfigurationParser(),
            new SpringConfigurationMetadataCatalog(),
            new ConfigurationPropertiesClassAnalyzer(propertyNameNormalizer),
            new PropertyReferenceAnalyzer(propertyNameNormalizer),
            new SensitivePropertyValueRedactor(),
            propertyNameNormalizer
    );

    @TempDir
    Path tempDir;

    @Test
    void analyzesConfigurationFilesMetadataCustomPropertiesAndReferences() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main/resources/META-INF"));
        Files.writeString(tempDir.resolve("src/main/resources/application.properties"), """
                server.port=8080
                trading.enabled=true
                trading.api-key=secret-value
                legacy.flag=true
                mystery.value=surprise
                spring.mail.properties.mail.smtp.auth=true
                """);
        Files.writeString(tempDir.resolve("src/main/resources/application-prod.properties"), """
                spring.jpa.hibernate.ddl-auto=update
                management.endpoints.web.exposure.include=*
                management.endpoint.health.show-details=always
                springdoc.api-docs.enabled=true
                trading.api-key=${TRADING_API_KEY}
                """);
        Files.writeString(tempDir.resolve("src/main/resources/application-dev.yml"), """
                management:
                  endpoints:
                    web:
                      exposure:
                        include:
                          - health
                          - info
                trading:
                  risk-per-trade: 2
                """);
        Files.writeString(tempDir.resolve("src/main/resources/META-INF/additional-spring-configuration-metadata.json"), """
                {
                  "properties": [
                    {
                      "name": "legacy.flag",
                      "type": "java.lang.Boolean",
                      "description": "Legacy toggle.",
                      "deprecated": true,
                      "deprecation": {
                        "reason": "Replaced by newer behavior."
                      }
                    }
                  ]
                }
                """);

        Path sourceRoot = Files.createDirectories(tempDir.resolve("src/main/java/com/example/demo"));
        Files.writeString(sourceRoot.resolve("TradingProperties.java"), """
                package com.example.demo;

                import jakarta.validation.constraints.NotNull;
                import org.springframework.boot.context.properties.ConfigurationProperties;

                @ConfigurationProperties(prefix = "trading")
                public record TradingProperties(
                        @NotNull Boolean enabled,
                        Integer riskPerTrade,
                        Notifications notifications
                ) {
                    public static class Notifications {
                        Events events;
                    }

                    public static class Events {
                        boolean orderFills;
                        boolean stopLoss;
                    }
                }
                """);
        Files.writeString(sourceRoot.resolve("TradingService.java"), """
                package com.example.demo;

                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.core.env.Environment;
                import org.springframework.stereotype.Component;

                @Component
                @ConditionalOnProperty(prefix = "trading", name = "enabled", havingValue = "true", matchIfMissing = true)
                public class TradingService {

                    @Value("${trading.enabled:false}")
                    private boolean enabled;

                    @Scheduled(fixedDelayString = "${trading.jobs.strategy.fixed-delay:PT5M}")
                    void runJob() {
                    }

                    public boolean isEnabled(Environment environment) {
                        return environment.getProperty("trading.enabled", "false").equals("true")
                                && environment.containsProperty("trading.missing")
                                && environment.getRequiredProperty("trading.api-key") != null;
                    }
                }
                """);

        var result = analyzer.analyze(
                tempDir,
                new BuildInfo(
                        BuildTool.GRADLE,
                        true,
                        "25",
                        java.util.List.of(
                                "org.springframework.boot:spring-boot-starter-mail",
                                "org.springframework.boot:spring-boot-starter-data-jpa",
                                "org.springdoc:springdoc-openapi-starter-webmvc-ui:2.3.0"
                        ),
                        "3.5.13",
                        "build.gradle plugin",
                        "HIGH"
                )
        );
        var analysis = result.configurationAnalysis();

        assertThat(analysis.files()).extracting(file -> file.path())
                .contains("src/main/resources/application.properties", "src/main/resources/application-dev.yml");
        assertThat(analysis.files()).extracting(file -> file.profile()).contains("default", "dev");

        assertThat(analysis.properties()).extracting(property -> property.name())
                .contains(
                        "server.port",
                        "management.endpoints.web.exposure.include[0]",
                        "trading.enabled",
                        "trading.risk-per-trade",
                        "trading.notifications.events.order-fills",
                        "trading.notifications.events.stop-loss"
                );

        var serverPort = analysis.properties().stream()
                .filter(property -> property.name().equals("server.port"))
                .findFirst()
                .orElseThrow();
        assertThat(serverPort.documentation().known()).isTrue();
        assertThat(serverPort.kind()).isEqualTo(PropertyKind.SPRING_BOOT);

        var customProperty = analysis.properties().stream()
                .filter(property -> property.name().equals("trading.enabled"))
                .findFirst()
                .orElseThrow();
        assertThat(customProperty.kind()).isEqualTo(PropertyKind.CUSTOM_CONFIGURATION_PROPERTIES);
        assertThat(customProperty.references()).extracting(reference -> reference.referenceType())
                .contains("@Value", "Environment#getProperty", "@ConditionalOnProperty");
        assertThat(customProperty.references())
                .anyMatch(reference -> "true".equals(reference.expectedValue()) && Boolean.TRUE.equals(reference.matchIfMissing()));

        var sensitiveProperty = analysis.properties().stream()
                .filter(property -> property.name().equals("trading.api-key"))
                .findFirst()
                .orElseThrow();
        assertThat(sensitiveProperty.valueRedacted()).isTrue();
        assertThat(sensitiveProperty.value()).isEqualTo("[redacted]");

        var mailMapProperty = analysis.properties().stream()
                .filter(property -> property.name().equals("spring.mail.properties.mail.smtp.auth"))
                .findFirst()
                .orElseThrow();
        assertThat(mailMapProperty.kind()).isEqualTo(PropertyKind.SPRING_BOOT_MAP_PROPERTY);
        assertThat(mailMapProperty.documentation().sourceType()).contains("MailProperties");

        var springdocProperty = analysis.properties().stream()
                .filter(property -> property.name().equals("springdoc.api-docs.enabled"))
                .findFirst()
                .orElseThrow();
        assertThat(springdocProperty.kind()).isEqualTo(PropertyKind.THIRD_PARTY);

        assertThat(analysis.codeReferences()).extracting(reference -> reference.propertyName())
                .contains("trading.enabled", "trading.missing", "trading.api-key", "trading.jobs.strategy.fixed-delay");
        assertThat(analysis.codeReferences())
                .anyMatch(reference -> "@Scheduled".equals(reference.referenceType()));

        assertThat(analysis.summary().configuredPropertyCount()).isGreaterThanOrEqualTo(5);
        assertThat(analysis.summary().profiles()).contains("default", "dev", "prod");

        assertThat(result.findings()).extracting(finding -> finding.severity())
                .contains(FindingSeverity.WARNING, FindingSeverity.INFO);
        assertThat(result.findings()).extracting(finding -> finding.message())
                .anyMatch(message -> message.contains("could not be matched"))
                .anyMatch(message -> message.contains("trading.missing"))
                .anyMatch(message -> message.contains("Deprecated configuration property"))
                .anyMatch(message -> message.contains("literal value"))
                .anyMatch(message -> message.contains("Profile-specific configuration files"))
                .anyMatch(message -> message.contains("ddl-auto=update"))
                .anyMatch(message -> message.contains("exposure.include=*"))
                .anyMatch(message -> message.contains("health.show-details=always"));
    }
}
