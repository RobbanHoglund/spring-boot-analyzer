package com.robbanhoglund.springbootanalyzer.analyzer.model;

/**
 * Rich source position for a {@link Finding} or {@link FindingOccurrence}.
 *
 * <p>In addition to the line range, a {@code SourceLocation} carries optional column bounds
 * (for sub-line highlighting), a {@code symbol} (the property key, method name, or class
 * name that is the subject of the finding), a {@code language} hint used by the UI's
 * syntax highlighter, and an optional {@code githubUrl} permalink that is resolved after
 * analysis when a {@link com.robbanhoglund.springbootanalyzer.git.GitRepositoryReference}
 * is available.
 *
 * <p>The compact constructor infers {@code language} from the file extension when the
 * caller passes null or blank, so callers that construct a location from a file path rarely
 * need to supply the language explicitly. See {@link #inferLanguage(String)} for the
 * supported extensions.
 */
public record SourceLocation(
        String filePath,
        int startLine,
        int endLine,
        Integer startColumn,
        Integer endColumn,
        String symbol,
        String language,
        String githubUrl) {

    public SourceLocation {
        language = language == null || language.isBlank() ? inferLanguage(filePath) : language;
    }

    /**
     * Returns a copy of this location with the supplied GitHub permalink applied.
     *
     * @param githubUrl the resolved GitHub permalink (e.g.
     *                  {@code https://github.com/org/repo/blob/abc123/src/…#L12})
     * @return a new {@code SourceLocation} with all other fields unchanged
     */
    public SourceLocation withGithubUrl(String githubUrl) {
        return new SourceLocation(
                filePath, startLine, endLine, startColumn, endColumn, symbol, language, githubUrl);
    }

    /**
     * Infers a language identifier from a file path's extension for use by the UI syntax
     * highlighter.
     *
     * <p>The mapping is:
     * <ul>
     *   <li>{@code .java} → {@code "java"}</li>
     *   <li>{@code .properties} → {@code "properties"}</li>
     *   <li>{@code .yaml} / {@code .yml} → {@code "yaml"}</li>
     *   <li>{@code .xml} → {@code "xml"}</li>
     *   <li>{@code .gradle} / {@code .gradle.kts} → {@code "gradle"}</li>
     *   <li>Anything else (or null) → {@code "text"}</li>
     * </ul>
     *
     * @param filePath the file path to inspect; null returns {@code "text"}
     * @return a lowercase language identifier string
     */
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
