package com.robbanhoglund.springbootanalyzer.analyzer.gradle;

import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleExecutionFailureType;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradlePluginResolutionFailure;
import java.nio.file.Path;

public record GradleExecutionResult(
        boolean successful,
        boolean timedOut,
        int exitCode,
        Path reportFile,
        Path initScriptFile,
        String output,
        String executionMode,
        String gradleVersion,
        String javaVersion,
        GradleExecutionFailureType failureType,
        String errorMessage,
        GradlePluginResolutionFailure pluginResolutionFailure) {}
