package com.robbanhoglund.springbootanalyzer.config;

import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleExecutionMode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized configuration for the Spring Boot Analyzer service, bound from the
 * {@code analyzer.*} property namespace.
 *
 * <p>Top-level fields control workspace lifecycle:
 * <ul>
 *   <li>{@code workspaceRoot} — directory under which per-analysis workspaces are cloned</li>
 *   <li>{@code cleanupAfterAnalysis} — whether to delete the workspace when analysis finishes</li>
 *   <li>{@code workspaceKeepOnGradleFailure} — retain the workspace when Gradle fails so the
 *       build output can be inspected</li>
 *   <li>{@code scheduledWorkspaceCleanup} — periodic cleanup of stale workspaces</li>
 *   <li>{@code gradle} — Gradle tooling integration settings (see {@link GradleProperties})</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "analyzer")
public record AnalyzerProperties(
        Path workspaceRoot,
        boolean cleanupAfterAnalysis,
        boolean workspaceKeepOnGradleFailure,
        ScheduledWorkspaceCleanupProperties scheduledWorkspaceCleanup,
        GradleProperties gradle) {

    public AnalyzerProperties {
        scheduledWorkspaceCleanup =
                scheduledWorkspaceCleanup == null
                        ? new ScheduledWorkspaceCleanupProperties(true, Duration.ofDays(7), 4)
                        : scheduledWorkspaceCleanup;
    }

    /**
     * Returns the interval between consecutive scheduled workspace cleanup runs, in milliseconds.
     * Delegates to {@link ScheduledWorkspaceCleanupProperties#intervalMillis()}.
     */
    public long scheduledWorkspaceCleanupIntervalMillis() {
        return scheduledWorkspaceCleanup.intervalMillis();
    }

    /**
     * Returns the initial delay before the first scheduled cleanup run, in milliseconds.
     *
     * <p>Capped at 5 minutes so that the cleanup task fires reasonably soon after startup
     * even when the configured interval is very long (e.g. once per day). Without this cap
     * the first cleanup could be delayed by 24 hours after a restart.
     */
    public long scheduledWorkspaceCleanupInitialDelayMillis() {
        return Math.min(
                Duration.ofMinutes(5).toMillis(), scheduledWorkspaceCleanupIntervalMillis());
    }

    /**
     * Configuration for the periodic task that deletes stale analysis workspaces.
     *
     * @param enabled     whether periodic cleanup is active
     * @param maxAge      workspaces older than this are eligible for deletion; defaults to 7 days
     * @param runsPerDay  how many times per day the cleanup runs; defaults to 1 if &lt;= 0
     */
    public record ScheduledWorkspaceCleanupProperties(
            boolean enabled, Duration maxAge, int runsPerDay) {
        public ScheduledWorkspaceCleanupProperties {
            maxAge =
                    maxAge == null || maxAge.isNegative() || maxAge.isZero()
                            ? Duration.ofDays(7)
                            : maxAge;
            runsPerDay = runsPerDay <= 0 ? 1 : runsPerDay;
        }

        /** Returns the interval between cleanup runs as milliseconds, derived from {@code runsPerDay}. */
        public long intervalMillis() {
            return Duration.ofDays(1).dividedBy(runsPerDay).toMillis();
        }
    }

    /**
     * Gradle tooling integration settings.
     *
     * <p>Key fields:
     * <ul>
     *   <li>{@code enabled} — whether Gradle analysis is attempted at all</li>
     *   <li>{@code timeout} — per-invocation timeout (default 120 s)</li>
     *   <li>{@code executionMode} — {@code TOOLING_API} (in-process) or {@code CLI}
     *       (subprocess); defaults to {@code TOOLING_API}</li>
     *   <li>{@code diagnosticGradleVersion} — Gradle wrapper version used when the project
     *       does not include its own wrapper; defaults to {@code 9.5.0}</li>
     *   <li>{@code distributionCache} — directory for cached Gradle distributions</li>
     *   <li>{@code pluginResolutionBridge} — pre-fetches Gradle plugins via HTTP before
     *       running Gradle, working around plugin-portal connectivity issues</li>
     *   <li>{@code maxOutputBytes} — truncates Gradle stdout/stderr at this byte count
     *       (default 1 MiB); prevents runaway output from filling memory</li>
     *   <li>{@code maxResolvedDependencies} — caps the number of dependency coordinates
     *       retained in the analysis result (default 10 000)</li>
     * </ul>
     */
    public record GradleProperties(
            boolean enabled,
            Duration timeout,
            GradleExecutionMode executionMode,
            String diagnosticGradleVersion,
            Path distributionCache,
            List<String> passThroughEnvironment,
            ProxyProperties proxy,
            SslProperties ssl,
            boolean injectPluginRepositories,
            List<String> pluginRepositoryUrls,
            boolean addMavenCentralForPluginResolution,
            boolean preflightPluginPortal,
            boolean preflightPluginMarker,
            boolean skipGradleIfRequiredSettingsPluginMarkerUnreachable,
            boolean copyHostGradleProxyProperties,
            boolean copyHostGradleInitScripts,
            boolean copyHostGradleCaches,
            SettingsPluginWorkaroundProperties settingsPluginWorkarounds,
            PluginResolutionBridgeProperties pluginResolutionBridge,
            boolean allowSystemGradleFallback,
            boolean useWrapper,
            boolean allowNetwork,
            Path executable,
            Path javaHome,
            int maxOutputBytes,
            int maxResolvedDependencies) {
        public GradleProperties {
            timeout = timeout == null ? Duration.ofSeconds(120) : timeout;
            executionMode = executionMode == null ? GradleExecutionMode.TOOLING_API : executionMode;
            diagnosticGradleVersion =
                    diagnosticGradleVersion == null || diagnosticGradleVersion.isBlank()
                            ? "9.5.0"
                            : diagnosticGradleVersion;
            distributionCache =
                    distributionCache == null
                            ? Path.of(
                                    System.getProperty("java.io.tmpdir"),
                                    "spring-boot-analyzer-gradle-distributions")
                            : distributionCache;
            passThroughEnvironment =
                    passThroughEnvironment == null || passThroughEnvironment.isEmpty()
                            ? List.of(
                                    "HTTP_PROXY",
                                    "HTTPS_PROXY",
                                    "NO_PROXY",
                                    "http_proxy",
                                    "https_proxy",
                                    "no_proxy")
                            : List.copyOf(passThroughEnvironment);
            proxy = proxy == null ? new ProxyProperties(false, null, null, null, null) : proxy;
            ssl = ssl == null ? new SslProperties(null, null) : ssl;
            pluginRepositoryUrls =
                    pluginRepositoryUrls == null || pluginRepositoryUrls.isEmpty()
                            ? List.of("https://plugins.gradle.org/m2/")
                            : List.copyOf(pluginRepositoryUrls);
            settingsPluginWorkarounds =
                    settingsPluginWorkarounds == null
                            ? new SettingsPluginWorkaroundProperties(false, false, List.of(), 1)
                            : settingsPluginWorkarounds;
            pluginResolutionBridge =
                    pluginResolutionBridge == null
                            ? new PluginResolutionBridgeProperties(
                                    true,
                                    true,
                                    true,
                                    "Spring Boot Analyzer plugin cache",
                                    List.of(
                                            "https://plugins.gradle.org/m2/",
                                            "https://repo.maven.apache.org/maven2/"),
                                    Duration.ofSeconds(30),
                                    50,
                                    500,
                                    false,
                                    2)
                            : pluginResolutionBridge;
            maxOutputBytes = maxOutputBytes <= 0 ? 1_048_576 : maxOutputBytes;
            maxResolvedDependencies =
                    maxResolvedDependencies <= 0 ? 10_000 : maxResolvedDependencies;
        }
    }

    /** HTTP/HTTPS proxy configuration forwarded to Gradle invocations. */
    public record ProxyProperties(
            boolean enabled, String host, Integer port, String username, String password) {}

    /** Trust-store configuration used when Gradle needs to reach HTTPS plugin repositories. */
    public record SslProperties(Path trustStore, String trustStorePassword) {}

    /**
     * Workarounds for projects whose {@code settings.gradle} applies a plugin that cannot be
     * resolved in the analyzer's sandboxed environment.
     *
     * @param enabled                  whether the workaround is active
     * @param retryOnFailure           retry the Gradle invocation after applying the workaround
     * @param knownNonessentialPlugins plugin IDs that are safe to strip from settings.gradle
     * @param maxRetries               maximum retry attempts; defaults to 1 if &lt;= 0
     */
    public record SettingsPluginWorkaroundProperties(
            boolean enabled,
            boolean retryOnFailure,
            List<String> knownNonessentialPlugins,
            int maxRetries) {
        public SettingsPluginWorkaroundProperties {
            knownNonessentialPlugins =
                    knownNonessentialPlugins == null
                            ? List.of()
                            : List.copyOf(knownNonessentialPlugins);
            maxRetries = maxRetries <= 0 ? 1 : maxRetries;
        }
    }

    /**
     * Configuration for the plugin resolution bridge that pre-fetches Gradle plugins via HTTP
     * before the Gradle invocation begins.
     *
     * <p>The bridge downloads plugin JARs and marker artifacts into a local file-system
     * repository ({@code localRepositoryName}) and injects that repository into the Gradle
     * build so that the Gradle process does not need direct internet access to the plugin
     * portal. This is the primary mitigation for plugin-portal connectivity failures in
     * sandboxed or air-gapped environments.
     *
     * @param enabled                          whether the bridge is active
     * @param retryOnPluginResolutionFailure   re-attempt pre-fetch when resolution fails
     * @param prefetchBeforeGradle             fetch plugins before the Gradle invocation starts
     * @param localRepositoryName              display name for the injected local repository
     * @param repositories                     plugin repository URLs to fetch from
     * @param timeout                          per-request HTTP timeout (default 30 s)
     * @param maxPlugins                       maximum number of plugins to pre-fetch (default 50)
     * @param maxArtifacts                     maximum total artifacts to download (default 500)
     * @param redownload                       re-download artifacts even if already cached
     * @param maxRetries                       maximum per-artifact download retry attempts
     */
    public record PluginResolutionBridgeProperties(
            boolean enabled,
            boolean retryOnPluginResolutionFailure,
            boolean prefetchBeforeGradle,
            String localRepositoryName,
            List<String> repositories,
            Duration timeout,
            int maxPlugins,
            int maxArtifacts,
            boolean redownload,
            int maxRetries) {
        public PluginResolutionBridgeProperties {
            localRepositoryName =
                    localRepositoryName == null || localRepositoryName.isBlank()
                            ? "Spring Boot Analyzer plugin cache"
                            : localRepositoryName;
            repositories =
                    repositories == null || repositories.isEmpty()
                            ? List.of(
                                    "https://plugins.gradle.org/m2/",
                                    "https://repo.maven.apache.org/maven2/")
                            : List.copyOf(repositories);
            timeout =
                    timeout == null || timeout.isNegative() || timeout.isZero()
                            ? Duration.ofSeconds(30)
                            : timeout;
            maxPlugins = maxPlugins <= 0 ? 50 : maxPlugins;
            maxArtifacts = maxArtifacts <= 0 ? 500 : maxArtifacts;
            maxRetries = maxRetries < 0 ? 0 : maxRetries;
        }
    }
}
