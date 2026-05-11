package com.robbanhoglund.springbootanalyzer.api.dto;

import com.robbanhoglund.springbootanalyzer.analyzer.model.HighlightRange;
import java.util.List;

public record SourceSnippetResponse(
        String filePath,
        String language,
        int startLine,
        int endLine,
        String githubUrl,
        List<SourceSnippetLine> lines,
        List<HighlightRange> highlightRanges) {}
