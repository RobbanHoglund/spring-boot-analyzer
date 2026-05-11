package com.robbanhoglund.springbootanalyzer.git;

public record GitRepositoryCredentials(String username, String token) {

    public boolean hasToken() {
        return token != null && !token.isBlank();
    }

    public String safeUsername() {
        if (username == null || username.isBlank()) {
            return "git";
        }
        return username;
    }

    /**
     * Returns a redacted representation. The token is never included so that this record
     * cannot accidentally expose credentials when logged or serialized as a string.
     */
    @Override
    public String toString() {
        return "GitRepositoryCredentials[username=" + safeUsername() + ", token=***]";
    }
}
