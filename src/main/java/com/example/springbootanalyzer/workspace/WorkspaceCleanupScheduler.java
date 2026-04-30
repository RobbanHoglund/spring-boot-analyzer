package com.example.springbootanalyzer.workspace;

import com.example.springbootanalyzer.config.AnalyzerProperties;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

@Component
public class WorkspaceCleanupScheduler implements SchedulingConfigurer {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceCleanupScheduler.class);

    private final WorkspaceService workspaceService;
    private final AnalyzerProperties analyzerProperties;

    public WorkspaceCleanupScheduler(WorkspaceService workspaceService, AnalyzerProperties analyzerProperties) {
        this.workspaceService = workspaceService;
        this.analyzerProperties = analyzerProperties;
    }

    public void deleteStaleWorkspaces() {
        AnalyzerProperties.ScheduledWorkspaceCleanupProperties cleanup = analyzerProperties.scheduledWorkspaceCleanup();
        if (!cleanup.enabled()) {
            return;
        }

        WorkspaceService.WorkspaceCleanupResult result = workspaceService.deleteWorkspacesOlderThan(cleanup.maxAge());
        if (result.deletedCount() > 0 || result.failedCount() > 0) {
            LOGGER.info(
                    "Scheduled workspace cleanup completed: scanned={}, deleted={}, failed={}, maxAge={}, runsPerDay={}",
                    result.scannedCount(),
                    result.deletedCount(),
                    result.failedCount(),
                    cleanup.maxAge(),
                    cleanup.runsPerDay()
            );
        } else {
            LOGGER.debug(
                    "Scheduled workspace cleanup found nothing to delete: scanned={}, maxAge={}, runsPerDay={}",
                    result.scannedCount(),
                    cleanup.maxAge(),
                    cleanup.runsPerDay()
            );
        }
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.addFixedDelayTask(new IntervalTask(
                this::deleteStaleWorkspaces,
                Duration.ofMillis(analyzerProperties.scheduledWorkspaceCleanupIntervalMillis()),
                Duration.ofMillis(analyzerProperties.scheduledWorkspaceCleanupInitialDelayMillis())
        ));
    }
}
