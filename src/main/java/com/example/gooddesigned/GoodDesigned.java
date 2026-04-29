package com.example.gooddesigned;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/good-designed")
public class GoodDesigned {

    private static final Logger LOG = LoggerFactory.getLogger(GoodDesigned.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @PostMapping("/orders")
    ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateOrderRequest request) {
        String downstreamResponse = notifyExternalSystem(request);
        return ResponseEntity.ok(Map.of(
                "status", "accepted",
                "customerId", request.customerId(),
                "quantity", request.quantity(),
                "downstream", downstreamResponse
        ));
    }

    private String notifyExternalSystem(CreateOrderRequest request) {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create("https://api.example.com/orders"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"customerId\":\"" + request.customerId() + "\"}"))
                    .build();
            return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString()).body();
        } catch (java.io.IOException exception) {
            LOG.warn("Order notification failed for customer {}", request.customerId(), exception);
            throw new IllegalStateException("Downstream order notification failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Order notification was interrupted", exception);
        }
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException exception) {
        LOG.error("Request handling failed", exception);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("message", "The order could not be processed at this time."));
    }

    public record CreateOrderRequest(
            @NotBlank String customerId,
            @Min(1) int quantity
    ) {
    }
}
