package com.robbanhoglund.springbootanalyzer.analyzer.model;

public record SourceLocation(
        String filePath,
        int startLine,
        int endLine,
        Integer startColumn,
        Integer endColumn,
        String symbol,
        String language,
        String githubUrl
) {

    public SourceLocation {
        language = language == null || language.isBlank() ? inferLanguage(filePath) : language;
    }

    public SourceLocation withGithubUrl(String githubUrl) {
        return new SourceLocation(filePath, startLine, endLine, startColumn, endColumn, symbol, language, githubUrl);
    }

    public static String inferLanguage(String filePath) {
        if (filePath == null) {
            return "text";
        }
        String lowerCasePath = filePath.toLowerCase();
        if (lowerCasePath.endsWith(".java")) {
            return "java";
        }
        if (lowerCasePath.endsWith(".properties")) {
            return "properties";
        }
        if (lowerCasePath.endsWith(".yaml") || lowerCasePath.endsWith(".yml")) {
            return "yaml";
        }
        if (lowerCasePath.endsWith(".xml")) {
            return "xml";
        }
        if (lowerCasePath.endsWith(".gradle") || lowerCasePath.endsWith(".gradle.kts")) {
            return "gradle";
        }
        return "text";
    }
}
