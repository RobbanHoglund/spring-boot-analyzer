package com.example.springbootanalyzer.analyzer.configuration;

import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class SensitivePropertyValueRedactor {

    private static final List<String> SENSITIVE_MARKERS = List.of(
            "password",
            "passwd",
            "secret",
            "token",
            "api-key",
            "apikey",
            "private-key",
            "credential",
            "authorization",
            "client-secret",
            "access-key",
            "refresh-token"
    );

    public boolean isSensitive(String propertyName) {
        String normalized = propertyName == null ? "" : propertyName.toLowerCase(Locale.ROOT);
        return SENSITIVE_MARKERS.stream().anyMatch(normalized::contains);
    }

    public String redact(String value) {
        if (value == null || value.isBlank()) {
            return "[redacted]";
        }
        return "[redacted]";
    }
}
