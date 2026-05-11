package com.robbanhoglund.springbootanalyzer.analyzer.model;

import java.util.List;

public record DetectedClass(
        String fullyQualifiedClassName,
        String simpleClassName,
        String packageName,
        String filePath,
        SpringComponentType componentType,
        List<String> annotationNames) {}
