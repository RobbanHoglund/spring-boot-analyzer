package com.robbanhoglund.springbootanalyzer.analyzer.model.gradle;

public record GradlePluginDeclaration(
        String pluginId,
        String version,
        String sourceFile,
        Integer line,
        GradlePluginDeclarationSource source,
        boolean applyFalse
) {
}
