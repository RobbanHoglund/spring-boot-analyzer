package com.robbanhoglund.springbootanalyzer.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleExecutionMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "analyzer")
public record AnalyzerProperties(
        Path workspaceRoot,
        boolean cleanupAfterAnalysis,
        boolean workspaceKeepOnGradleFailure,
        ScheduledWorkspaceCleanupProperties scheduledWorkspaceCleanup,
        GradleProperties gradle
) {

    public AnalyzerProperties {
        scheduledWorkspaceCleanup = scheduledWorkspaceCleanup == null
                ? new ScheduledWorkspaceCleanupProperties(true, Duration.ofDays(7), 4)
                : scheduledWorkspaceCleanup;
    }

    public long scheduledWorkspaceCleanupIntervalMillis() {
        return scheduledWorkspaceCleanup.intervalMillis();
    }

    public long scheduledWorkspaceCleanupInitialDelayMillis() {
        return Math.min(Duration.ofMinutes(5).toMillis(), scheduledWorkspaceCleanupIntervalMillis());
    }

    public record ScheduledWorkspaceCleanupProperties(
            boolean enabled,
            Duration maxAge,
            int runsPerDay
    ) {
        public ScheduledWorkspaceCleanupProperties {
            maxAge = maxAge == null || maxAge.isNegative() || maxAge.isZero()
                    ? Duration.ofDays(7)
                    : maxAge;
            runsPerDay = runsPerDay <= 0 ? 1 : runsPerDay;
        }

        public long intervalMillis() {
            return Duration.ofDays(1).dividedBy(runsPerDay).toMillis();
        }
    }

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
            int maxResolvedDependencies
    ) {
        public GradleProperties {
            timeout = timeout == null ? Duration.ofSeconds(120) : timeout;
            executionMode = executionMode == null ? GradleExecutionMode.TOOLING_API : executionMode;
            diagnosticGradleVersion = diagnosticGradleVersion == null || diagnosticGradleVersion.isBlank()
                    ? "9.5.0"
                    : diagnosticGradleVersion;
            distributionCache = distributionCache == null
                    ? Path.of(System.getProperty("java.io.tmpdir"), "spring-boot-analyzer-gradle-distributions")
                    : distributionCache;
            passThroughEnvironment = passThroughEnvironment == null || passThroughEnvironment.isEmpty()
                    ? List.of("HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "no_proxy")
                    : List.copyOf(passThroughEnvironment);
            proxy = proxy == null ? new ProxyProperties(false, null, null, null, null) : proxy;
            ssl = ssl == null ? new SslProperties(null, null) : ssl;
            pluginRepositoryUrls = pluginRepositoryUrls == null || pluginRepositoryUrls.isEmpty()
                    ? List.of("https://plugins.gradle.org/m2/")
                    : List.copyOf(pluginRepositoryUrls);
            settingsPluginWorkarounds = settingsPluginWorkarounds == null
                    ? new SettingsPluginWorkaroundProperties(
                            false,
                            false,
                            List.of(),
                            1
                    )
                    : settingsPluginWorkarounds;
            pluginResolutionBridge = pluginResolutionBridge == null
                    ? new PluginResolutionBridgeProperties(
                            true,
                            true,
                            true,
                            "Spring Boot Analyzer plugin cache",
                            List.of("https://plugins.gradle.org/m2/", "https://repo.maven.apache.org/maven2/"),
                            Duration.ofSeconds(30),
                            50,
                            500,
                            false,
                            2
                    )
                    : pluginResolutionBridge;
            maxOutputBytes = maxOutputBytes <= 0 ? 1_048_576 : maxOutputBytes;
            maxResolvedDependencies = maxResolvedDependencies <= 0 ? 10_000 : maxResolvedDependencies;
        }
    }

    public record ProxyProperties(
            boolean enabled,
            String host,
            Integer port,
            String username,
            String password
    ) {
    }

    public record SslProperties(
            Path trustStore,
            String trustStorePassword
    ) {
    }

    public record SettingsPluginWorkaroundProperties(
            boolean enabled,
            boolean retryOnFailure,
            List<String> knownNonessentialPlugins,
            int maxRetries
    ) {
        public SettingsPluginWorkaroundProperties {
            knownNonessentialPlugins = knownNonessentialPlugins == null
                    ? List.of()
                    : List.copyOf(knownNonessentialPlugins);
            maxRetries = maxRetries <= 0 ? 1 : maxRetries;
        }
    }

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
            int maxRetries
    ) {
        public PluginResolutionBridgeProperties {
            localRepositoryName = localRepositoryName == null || localRepositoryName.isBlank()
                    ? "Spring Boot Analyzer plugin cache"
                    : localRepositoryName;
            repositories = repositories == null || repositories.isEmpty()
                    ? List.of("https://plugins.gradle.org/m2/", "https://repo.maven.apache.org/maven2/")
                    : List.copyOf(repositories);
            timeout = timeout == null || timeout.isNegative() || timeout.isZero()
                    ? Duration.ofSeconds(30)
                    : timeout;
            maxPlugins = maxPlugins <= 0 ? 50 : maxPlugins;
            maxArtifacts = maxArtifacts <= 0 ? 500 : maxArtifacts;
            maxRetries = maxRetries < 0 ? 0 : maxRetries;
        }
    }
}
