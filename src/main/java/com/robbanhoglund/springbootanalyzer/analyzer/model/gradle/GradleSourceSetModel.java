package com.robbanhoglund.springbootanalyzer.analyzer.model.gradle;

import java.util.List;

public record GradleSourceSetModel(
        String projectPath,
        String name,
        List<String> javaDirs,
        List<String> resourceDirs
) {
}
