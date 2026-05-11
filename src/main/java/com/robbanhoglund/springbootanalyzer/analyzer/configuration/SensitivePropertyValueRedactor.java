package com.robbanhoglund.springbootanalyzer.analyzer.configuration;

import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class SensitivePropertyValueRedactor {

    private static final Set<String> SENSITIVE_MARKERS =
            Set.of(
                    "password",
                    "passwd",
                    "secret",
                    "client-secret",
                    "api-key",
                    "apikey",
                    "access-key",
                    "private-key",
                    "credential",
                    "credentials",
                    "authorization",
                    "api-token",
                    "access-token",
                    "refresh-token",
                    "bearer-token",
                    "auth-token",
                    "oauth-token",
                    "github-token",
                    "signing-key",
                    "pat",
                    "jwt-secret");
    private static final Set<String> NON_SECRET_TOKEN_MARKERS =
            Set.of(
                    "max-output-tokens",
                    "max-tokens",
                    "token-limit",
                    "token-count",
                    "token-budget",
                    "tokens-per-minute",
                    "tokens-per-request",
                    "tokenizer",
                    "token-window",
                    "output-tokens",
                    "input-tokens",
                    "input-token-budget",
                    "output-token-budget");

    public boolean isSensitive(String propertyName) {
        String normalized = propertyName == null ? "" : propertyName.toLowerCase(Locale.ROOT);
        if (NON_SECRET_TOKEN_MARKERS.stream().anyMatch(normalized::contains)) {
            return false;
        }
        if (SENSITIVE_MARKERS.stream().anyMatch(normalized::contains)) {
            return true;
        }
        return normalized.endsWith(".token")
                || normalized.endsWith("-token")
                || normalized.contains(".token.")
                || normalized.contains("-token-");
    }

    public String redact(String value) {
        if (value == null || value.isBlank()) {
            return "[redacted]";
        }
        return "[redacted]";
    }
}
