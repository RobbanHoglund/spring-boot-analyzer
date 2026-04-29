package com.example.springbootanalyzer.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "analyzer")
public record AnalyzerProperties(
        Path workspaceRoot,
        boolean cleanupAfterAnalysis
) {
}
