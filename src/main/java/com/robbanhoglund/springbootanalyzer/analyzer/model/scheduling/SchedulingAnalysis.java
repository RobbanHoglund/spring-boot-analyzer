package com.robbanhoglund.springbootanalyzer.analyzer.model.scheduling;

import java.util.List;

public record SchedulingAnalysis(
        List<ScheduledTaskEndpoint> scheduledTasks,
        List<AsyncMethodEndpoint> asyncMethods,
        boolean enableSchedulingPresent,
        boolean enableAsyncPresent) {
    public static SchedulingAnalysis empty() {
        return new SchedulingAnalysis(List.of(), List.of(), false, false);
    }
}
