package com.example.springbootanalyzer.git;

public record GitRepositoryCredentials(
        String username,
        String token
) {

    public boolean hasToken() {
        return token != null && !token.isBlank();
    }

    public String safeUsername() {
        if (username == null || username.isBlank()) {
            return "git";
        }
        return username;
    }
}
