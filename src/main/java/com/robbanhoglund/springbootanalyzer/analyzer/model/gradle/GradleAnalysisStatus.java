package com.robbanhoglund.springbootanalyzer.analyzer.model.gradle;

public enum GradleAnalysisStatus {
    NOT_REQUESTED,
    DISABLED,
    SKIPPED,
    SUCCESS,
    SUCCESS_WITH_WORKAROUND,
    PARTIAL,
    FAILED,
    TIMED_OUT
}
