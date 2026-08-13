package com.robbanhoglund.springbootanalyzer.analyzer.configuration;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class SensitivePropertyValueRedactor {

    private static final Pattern ENVIRONMENT_VARIABLE_PLACEHOLDER =
            Pattern.compile("^\\$\\{[A-Z][A-Z0-9_]*}$");

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
        // PAT means "personal access token" only when it is a complete property-name segment.
        // A substring check also matched ordinary names such as app.path and compatibility-mode,
        // causing both unnecessary redaction and secret-literal findings.
        if (hasSegment(normalized, "pat")) {
            return true;
        }
        if (SENSITIVE_MARKERS.stream().anyMatch(normalized::contains)) {
            return true;
        }
        return normalized.endsWith(".token")
                || normalized.endsWith("-token")
                || normalized.contains(".token.")
                || normalized.contains("-token-");
    }

    /**
     * Returns whether the property's final name segment describes a value that is likely to be
     * the credential itself. This deliberately has a higher precision than {@link #isSensitive}
     * because redaction should remain conservative while warning rules should not treat metadata
     * such as {@code password-policy}, {@code token-limit}, or {@code secret-rotation-period} as a
     * committed credential.
     */
    public boolean isLikelySecretValue(String propertyName) {
        String normalized =
                propertyName == null ? "" : propertyName.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()
                || NON_SECRET_TOKEN_MARKERS.stream().anyMatch(normalized::contains)) {
            return false;
        }

        String leaf = leafSegment(normalized);
        return leaf.equals("password")
                || leaf.equals("passwd")
                || leaf.equals("secret")
                || leaf.equals("credential")
                || leaf.equals("credentials")
                || leaf.equals("authorization")
                || leaf.equals("token")
                || leaf.equals("pat")
                || leaf.equals("apikey")
                || leaf.equals("clientsecret")
                || leaf.endsWith("-password")
                || leaf.endsWith("_password")
                || leaf.endsWith("-passwd")
                || leaf.endsWith("_passwd")
                || leaf.endsWith("-secret")
                || leaf.endsWith("_secret")
                || leaf.endsWith("-token")
                || leaf.endsWith("_token")
                || leaf.equals("api-key")
                || leaf.equals("api_key")
                || leaf.endsWith("-api-key")
                || leaf.endsWith("_api_key")
                || leaf.equals("access-key")
                || leaf.equals("access_key")
                || leaf.endsWith("-access-key")
                || leaf.endsWith("_access_key")
                || leaf.equals("private-key")
                || leaf.equals("private_key")
                || leaf.endsWith("-private-key")
                || leaf.endsWith("_private_key")
                || leaf.equals("signing-key")
                || leaf.equals("signing_key")
                || leaf.endsWith("-signing-key")
                || leaf.endsWith("_signing_key");
    }

    private String leafSegment(String propertyName) {
        int dot = propertyName.lastIndexOf('.');
        int bracket = propertyName.lastIndexOf(']');
        int separator = Math.max(dot, bracket);
        return separator >= 0 && separator + 1 < propertyName.length()
                ? propertyName.substring(separator + 1)
                : propertyName;
    }

    private boolean hasSegment(String propertyName, String segment) {
        String[] segments = propertyName.split("[.\\-_\\[\\]]+");
        for (String candidate : segments) {
            if (segment.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    public String redact(String value) {
        // A variable-only placeholder contains a reference name, not the credential value. Keep
        // that name so the API/UI can positively identify environment-backed secrets. Defaults,
        // nested property references, and every literal remain fully redacted.
        if (isEnvironmentVariablePlaceholder(value)) {
            return value.trim();
        }
        return "[redacted]";
    }

    public boolean isEnvironmentVariablePlaceholder(String value) {
        return value != null && ENVIRONMENT_VARIABLE_PLACEHOLDER.matcher(value.trim()).matches();
    }
}
