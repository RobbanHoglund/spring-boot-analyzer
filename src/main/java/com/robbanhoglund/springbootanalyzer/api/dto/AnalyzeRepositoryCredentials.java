package com.robbanhoglund.springbootanalyzer.api.dto;

import jakarta.validation.constraints.Size;

public record AnalyzeRepositoryCredentials(
        @Size(max = 255, message = "credentials.username must be 255 characters or fewer")
                String username,
        @Size(max = 4096, message = "credentials.token must be 4096 characters or fewer")
                String token) {

    public boolean hasToken() {
        return token != null && !token.isBlank();
    }
}
