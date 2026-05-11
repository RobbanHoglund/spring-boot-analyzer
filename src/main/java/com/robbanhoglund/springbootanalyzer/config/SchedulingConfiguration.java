package com.robbanhoglund.springbootanalyzer.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Activates Spring's scheduling support.
 *
 * <p>Scheduling is restricted to web-application contexts so that CLI-mode runs
 * (which disable the embedded server) do not start the workspace-cleanup scheduler,
 * avoiding unnecessary background threads in a short-lived process.
 */
@Configuration
@EnableScheduling
@ConditionalOnWebApplication
public class SchedulingConfiguration {}
