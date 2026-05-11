package com.robbanhoglund.springbootanalyzer.application;

import com.robbanhoglund.springbootanalyzer.analyzer.model.HighlightRange;
import com.robbanhoglund.springbootanalyzer.analyzer.model.SourceLocation;
import com.robbanhoglund.springbootanalyzer.api.dto.SourceSnippetLine;
import com.robbanhoglund.springbootanalyzer.api.dto.SourceSnippetResponse;
import com.robbanhoglund.springbootanalyzer.git.GitHubLinkBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class SourceSnippetService {

    private static final int MAX_CONTEXT_LINES = 30;
    private static final int MAX_RETURNED_LINES = 200;
    private static final long MAX_FILE_SIZE_BYTES = 1_048_576L;
    private static final Set<String> SENSITIVE_KEY_MARKERS =
            Set.of(
                    "password",
                    "passwd",
                    "pwd",
                    "secret",
                    "token",
                    "api-key",
                    "apikey",
                    "private-key",
                    "credential",
                    "client-secret",
                    "access-key");

    private final AnalysisSessionRegistry analysisSessionRegistry;
    private final GitHubLinkBuilder gitHubLinkBuilder;

    public SourceSnippetService(
            AnalysisSessionRegistry analysisSessionRegistry, GitHubLinkBuilder gitHubLinkBuilder) {
        this.analysisSessionRegistry = analysisSessionRegistry;
        this.gitHubLinkBuilder = gitHubLinkBuilder;
    }

    public SourceSnippetResponse loadSnippet(
            String analysisId,
            String relativePath,
            Integer startLine,
            Integer endLine,
            int context) {
        if (analysisId == null || analysisId.isBlank()) {
            throw new InvalidSourceSnippetRequestException("Analysis ID is required.");
        }
        if (relativePath == null || relativePath.isBlank()) {
            throw new InvalidSourceSnippetRequestException("Source path is required.");
        }
        boolean hasExactRange = startLine != null && endLine != null;
        if ((startLine == null) != (endLine == null)) {
            throw new InvalidSourceSnippetRequestException(
                    "Source line range must include both start and end line values.");
        }
        if (hasExactRange && (startLine <= 0 || endLine <= 0 || endLine < startLine)) {
            throw new InvalidSourceSnippetRequestException("Line range is invalid.");
        }
        if (context < 0 || context > MAX_CONTEXT_LINES) {
            throw new InvalidSourceSnippetRequestException(
                    "Context must be between 0 and " + MAX_CONTEXT_LINES + " lines.");
        }

        AnalysisSessionRegistry.AnalysisSession session =
                analysisSessionRegistry
                        .find(analysisId)
                        .orElseThrow(
                                () ->
                                        new SourceSnippetNotFoundException(
                                                "Analysis session was not found."));

        Path repositoryRoot = session.repositoryRoot().normalize();
        Path resolvedPath = resolvePath(repositoryRoot, relativePath);

        ensureReadableSourceFile(resolvedPath);

        List<String> originalLines;
        try {
            originalLines = Files.readAllLines(resolvedPath, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new SourceSnippetNotFoundException("Source snippet could not be read.");
        }

        int totalLines = originalLines.size();
        if (totalLines == 0) {
            throw new SourceSnippetNotFoundException("Source file is empty.");
        }
        if (hasExactRange && startLine > totalLines) {
            throw new InvalidSourceSnippetRequestException(
                    "Requested start line is outside the file.");
        }

        int requestedStartLine = hasExactRange ? startLine : 1;
        int requestedEndLine =
                hasExactRange
                        ? Math.min(endLine, totalLines)
                        : Math.min(totalLines, Math.max(40, context * 2 + 12));
        int snippetStartLine = hasExactRange ? Math.max(1, requestedStartLine - context) : 1;
        int snippetEndLine =
                hasExactRange ? Math.min(totalLines, requestedEndLine + context) : requestedEndLine;
        if ((snippetEndLine - snippetStartLine + 1) > MAX_RETURNED_LINES) {
            snippetEndLine = Math.min(totalLines, snippetStartLine + MAX_RETURNED_LINES - 1);
        }

        List<SourceSnippetLine> snippetLines = new ArrayList<>();
        for (int index = snippetStartLine; index <= snippetEndLine; index++) {
            String rawLine = originalLines.get(index - 1);
            snippetLines.add(
                    new SourceSnippetLine(
                            index,
                            redactLineIfNeeded(relativePath, rawLine),
                            hasExactRange
                                    && index >= requestedStartLine
                                    && index <= requestedEndLine));
        }

        String githubUrl =
                gitHubLinkBuilder.buildBlobUrl(
                        session.repositoryUrl(),
                        session.commitSha(),
                        relativePath.replace('\\', '/'),
                        hasExactRange ? requestedStartLine : null,
                        hasExactRange ? requestedEndLine : null);

        return new SourceSnippetResponse(
                relativePath.replace('\\', '/'),
                SourceLocation.inferLanguage(relativePath),
                snippetStartLine,
                snippetEndLine,
                githubUrl,
                List.copyOf(snippetLines),
                hasExactRange
                        ? List.of(
                                new HighlightRange(
                                        requestedStartLine, requestedEndLine, null, null, "issue"))
                        : List.of());
    }

    private Path resolvePath(Path repositoryRoot, String relativePath) {
        Path candidate;
        try {
            candidate = Path.of(relativePath).normalize();
        } catch (Exception exception) {
            throw new InvalidSourceSnippetRequestException("Source path is invalid.");
        }
        if (candidate.isAbsolute() || candidate.startsWith("..")) {
            throw new InvalidSourceSnippetRequestException(
                    "Source path must stay inside the analyzed repository.");
        }
        for (Path segment : candidate) {
            if (".git".equals(segment.toString())) {
                throw new InvalidSourceSnippetRequestException("Git internals are not available.");
            }
        }
        Path resolved = repositoryRoot.resolve(candidate).normalize();
        if (!resolved.startsWith(repositoryRoot)) {
            throw new InvalidSourceSnippetRequestException(
                    "Source path must stay inside the analyzed repository.");
        }
        return resolved;
    }

    private void ensureReadableSourceFile(Path resolvedPath) {
        try {
            if (!Files.isRegularFile(resolvedPath)) {
                throw new SourceSnippetNotFoundException("Source file was not found.");
            }
            if (Files.size(resolvedPath) > MAX_FILE_SIZE_BYTES) {
                throw new InvalidSourceSnippetRequestException(
                        "Source file is too large to display safely.");
            }
            byte[] bytes = Files.readAllBytes(resolvedPath);
            for (byte value : bytes) {
                if (value == 0) {
                    throw new InvalidSourceSnippetRequestException(
                            "Binary files cannot be displayed as source snippets.");
                }
            }
        } catch (IOException exception) {
            throw new SourceSnippetNotFoundException("Source file metadata could not be read.");
        }
    }

    private String redactLineIfNeeded(String relativePath, String rawLine) {
        if (!isRedactedFileType(relativePath)) {
            return rawLine;
        }
        return redactPropertyStyleLine(rawLine);
    }

    private boolean isRedactedFileType(String relativePath) {
        String normalized = relativePath.toLowerCase(Locale.ROOT);
        return normalized.endsWith(".properties")
                || normalized.endsWith(".yaml")
                || normalized.endsWith(".yml")
                || normalized.endsWith(".env")
                || normalized.contains("/.env")
                || normalized.endsWith(".env.example");
    }

    private String redactPropertyStyleLine(String rawLine) {
        String trimmed = rawLine.trim();
        if (trimmed.isBlank() || trimmed.startsWith("#")) {
            return rawLine;
        }
        int separatorIndex = findSeparatorIndex(rawLine);
        if (separatorIndex < 0) {
            return redactYamlLine(rawLine);
        }
        String keyPart = rawLine.substring(0, separatorIndex).trim();
        if (!looksSensitiveKey(keyPart)) {
            return rawLine;
        }
        char separator = rawLine.charAt(separatorIndex);
        return rawLine.substring(0, separatorIndex + 1)
                + preserveLeadingWhitespace(rawLine.substring(separatorIndex + 1))
                + "[redacted]";
    }

    private String redactYamlLine(String rawLine) {
        int colonIndex = rawLine.indexOf(':');
        if (colonIndex < 0) {
            return rawLine;
        }
        String keyPart = rawLine.substring(0, colonIndex).trim();
        if (!looksSensitiveKey(keyPart)) {
            return rawLine;
        }
        return rawLine.substring(0, colonIndex + 1)
                + preserveLeadingWhitespace(rawLine.substring(colonIndex + 1))
                + "[redacted]";
    }

    private int findSeparatorIndex(String rawLine) {
        int equalsIndex = rawLine.indexOf('=');
        int colonIndex = rawLine.indexOf(':');
        if (equalsIndex < 0) {
            return colonIndex;
        }
        if (colonIndex < 0) {
            return equalsIndex;
        }
        return Math.min(equalsIndex, colonIndex);
    }

    private boolean looksSensitiveKey(String keyPart) {
        String normalized = keyPart.toLowerCase(Locale.ROOT);
        for (String marker : SENSITIVE_KEY_MARKERS) {
            if (normalized.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private String preserveLeadingWhitespace(String valuePart) {
        int index = 0;
        while (index < valuePart.length() && Character.isWhitespace(valuePart.charAt(index))) {
            index++;
        }
        return valuePart.substring(0, index);
    }
}
