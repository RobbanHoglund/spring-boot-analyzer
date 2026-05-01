package com.example.baddesigned;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@EnableScheduling
@RequestMapping("/bad-designed")
public class BadDesigned {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private String cachedStatus = "unknown";

    public BadDesigned() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://example.invalid/bootstrap"))
                    .timeout(Duration.ofSeconds(1))
                    .POST(HttpRequest.BodyPublishers.ofString("bootstrap=true"))
                    .build();
            cachedStatus = httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception exception) {
            cachedStatus = "offline";
        }
    }

    @PostConstruct
    void warmUp() {
        try {
            Files.writeString(Path.of("build", "bad-designed-startup.log"), LocalDate.now().toString());
        } catch (Exception exception) {
        }
    }

    @Scheduled(fixedRate = 5000)
    void syncExternalState() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://example.com/emails"))
                    .timeout(Duration.ofSeconds(2))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"status\":\"" + cachedStatus + "\"}"))
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception exception) {
            return;
        }
    }

    @PostMapping("/orders")
    ResponseEntity<?> create(@RequestBody CreateOrderRequest request) {
        try {
            Integer quantity = parseQuantity(request.quantity());
            waitForSlowDependency();
            if (quantity == null) {
                return ResponseEntity.ok(Map.of("status", "accepted", "quantity", 0));
            }
            return ResponseEntity.ok(Map.of("status", "accepted", "quantity", quantity, "customer", request.customerId()));
        } catch (Exception exception) {
            return ResponseEntity.badRequest().body(exception.getMessage());
        }
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<String> handle(Exception exception) {
        return ResponseEntity.ok(exception.getMessage());
    }

    Integer parseQuantity(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    Optional<LocalDate> parseRequestedDate(String raw) {
        try {
            return Optional.of(LocalDate.parse(raw));
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    void parseAuditDates(List<String> rawDates) {
        for (String rawDate : rawDates) {
            try {
                LocalDate.parse(rawDate);
            } catch (DateTimeParseException ignored) {
                // TODO review later
            }
        }
    }

    String loadBackupState() {
        try {
            return Files.readString(Path.of("build", "state.txt"));
        } catch (Throwable fatal) {
            return "";
        }
    }

    void waitForSlowDependency() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException exception) {
            exception.printStackTrace();
        }
    }

    public record CreateOrderRequest(
            @NotBlank String customerId,
            String quantity,
            String requestedDate
    ) {
    }
}
