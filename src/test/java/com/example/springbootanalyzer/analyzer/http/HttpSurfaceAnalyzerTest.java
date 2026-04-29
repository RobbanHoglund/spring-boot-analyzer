package com.example.springbootanalyzer.analyzer.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.springbootanalyzer.analyzer.model.BuildInfo;
import com.example.springbootanalyzer.analyzer.model.BuildTool;
import com.example.springbootanalyzer.analyzer.model.configuration.ApplicationProperty;
import com.example.springbootanalyzer.analyzer.model.configuration.ConfigurationAnalysis;
import com.example.springbootanalyzer.analyzer.model.configuration.ConfigurationSummary;
import com.example.springbootanalyzer.analyzer.model.configuration.PropertyDocumentation;
import com.example.springbootanalyzer.analyzer.model.configuration.PropertyKind;
import com.example.springbootanalyzer.analyzer.model.runtime.WebStack;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpSurfaceAnalyzerTest {

    private final HttpSurfaceAnalyzer analyzer = new HttpSurfaceAnalyzer();

    @TempDir
    Path tempDir;

    @Test
    void detectsInboundOutboundConfiguredUrlsAndActuatorExposure() throws IOException {
        Path sourceRoot = Files.createDirectories(tempDir.resolve("src/main/java/com/example/demo"));
        Files.writeString(sourceRoot.resolve("OrderController.java"), """
                package com.example.demo;

                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestParam;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping("/api/orders")
                class OrderController {

                    @PostMapping(path = "/{id}")
                    void create(@PathVariable String id, @RequestParam String mode, @RequestBody String body) {
                    }
                }
                """);
        Files.writeString(sourceRoot.resolve("ApiClient.java"), """
                package com.example.demo;

                import java.net.URI;
                import java.net.http.HttpRequest;
                import org.springframework.web.client.RestClient;
                import org.springframework.web.client.RestTemplate;
                import org.springframework.web.reactive.function.client.WebClient;

                class ApiClient {
                    void call(RestTemplate restTemplate) {
                        restTemplate.getForObject("https://api.example.com/items", String.class);
                        WebClient.builder().baseUrl("https://inventory.example.com").build().get().uri("/v1/items");
                        RestClient.builder().baseUrl("https://rest.example.com").build().post().uri("/v1/orders");
                        HttpRequest.newBuilder(URI.create("http://legacy.example.com/status")).GET();
                    }
                }
                """);
        Files.writeString(sourceRoot.resolve("AlpacaBrokerGateway.java"), """
                package com.example.demo;

                import org.springframework.web.reactive.function.client.WebClient;

                class AlpacaBrokerGateway {
                    void loadAccount() {
                        WebClient.builder().get().uri("/v2/account");
                    }
                }
                """);
        Files.writeString(sourceRoot.resolve("RemoteServiceClient.java"), """
                package com.example.demo;

                import org.springframework.cloud.openfeign.FeignClient;

                @FeignClient(name = "remoteService", url = "${service.url}")
                interface RemoteServiceClient {
                }
                """);

        ConfigurationAnalysis configurationAnalysis = new ConfigurationAnalysis(
                List.of(),
                List.of(
                        property("service.url", "https://service.example.com"),
                        property("management.endpoints.web.exposure.include", "*"),
                        property("spring.mail.host", "smtp.resend.com"),
                        property("spring.datasource.url", "jdbc:postgresql://${PGHOST}:${PGPORT}/${PGDATABASE}?currentSchema=tradingbot"),
                        property("spring.flyway.url", "${spring.datasource.url}"),
                        property("tradingbot.broker.alpaca.base-url", "https://paper-api.alpaca.markets"),
                        property("tradingbot.marketdata.alpaca.base-url", "https://data.alpaca.markets"),
                        property("tradingbot.broker.alpaca.api-key", "key-value"),
                        property("tradingbot.broker.alpaca.api-secret", "secret-value"),
                        property("tradingbot.broker.provider", "alpaca")
                ),
                List.of(),
                List.of(),
                new ConfigurationSummary(3, 0, 0, 0, 0, 0, List.of("default"))
        );

        BuildInfo buildInfo = new BuildInfo(
                BuildTool.GRADLE,
                true,
                "25",
                List.of("org.springframework.boot:spring-boot-starter-web"),
                "3.5.13",
                "Gradle plugins",
                "HIGH"
        );

        var result = analyzer.analyze(tempDir, configurationAnalysis, buildInfo, WebStack.SERVLET_MVC);

        assertThat(result.httpSurfaceAnalysis().inboundEndpoints()).hasSize(1);
        assertThat(result.httpSurfaceAnalysis().inboundEndpoints().get(0).path()).isEqualTo("/api/orders/{id}");
        assertThat(result.httpSurfaceAnalysis().outboundEndpoints()).extracting(endpoint -> endpoint.clientType())
                .contains("RestTemplate", "WebClient", "HttpClient", "Feign", "RestClient");
        assertThat(result.httpSurfaceAnalysis().outboundEndpoints())
                .anyMatch(endpoint -> "GET".equals(endpoint.method()) && "RestTemplate".equals(endpoint.clientType()))
                .anyMatch(endpoint -> "GET".equals(endpoint.method()) && "HttpClient".equals(endpoint.clientType()))
                .anyMatch(endpoint -> "POST".equals(endpoint.method()) && "RestClient".equals(endpoint.clientType()))
                .anyMatch(endpoint -> "/v2/account".equals(endpoint.urlOrTemplate())
                        && "paper-api.alpaca.markets".equals(endpoint.host())
                        && "tradingbot.broker.alpaca.base-url".equals(endpoint.configurationPropertyName()));
        assertThat(result.httpSurfaceAnalysis().configuredUrls()).extracting(url -> url.propertyName())
                .contains("service.url", "spring.mail.host", "spring.datasource.url", "spring.flyway.url");
        assertThat(result.httpSurfaceAnalysis().configuredUrls()).noneMatch(url ->
                "tradingbot.broker.alpaca.api-key".equals(url.propertyName())
                        || "tradingbot.broker.alpaca.api-secret".equals(url.propertyName())
                        || "tradingbot.broker.provider".equals(url.propertyName())
        );
        assertThat(result.httpSurfaceAnalysis().configuredUrls()).anyMatch(url ->
                "spring.datasource.url".equals(url.propertyName()) && url.host() == null
        );
        assertThat(result.httpSurfaceAnalysis().configuredUrls()).anyMatch(url ->
                "spring.mail.host".equals(url.propertyName())
                        && "MAIL_HOST".equals(url.kind().name())
                        && "smtp.resend.com".equals(url.host())
        );
        assertThat(result.httpSurfaceAnalysis().configuredUrls()).anyMatch(url ->
                "spring.flyway.url".equals(url.propertyName())
                        && "PROPERTY_REFERENCE".equals(url.kind().name())
                        && "spring.datasource.url".equals(url.referencedPropertyName())
        );
        assertThat(result.httpSurfaceAnalysis().actuatorExposures()).extracting(exposure -> exposure.propertyName())
                .contains("management.endpoints.web.exposure.include");
        assertThat(result.findings()).extracting(finding -> finding.message())
                .anyMatch(message -> message.contains("plain http://"))
                .anyMatch(message -> message.contains("publishes every endpoint"));
    }

    private ApplicationProperty property(String name, String value) {
        return new ApplicationProperty(
                name,
                value,
                false,
                "src/main/resources/application.properties",
                1,
                "default",
                PropertyKind.SPRING_BOOT,
                PropertyDocumentation.unknown(),
                List.of()
        );
    }
}
