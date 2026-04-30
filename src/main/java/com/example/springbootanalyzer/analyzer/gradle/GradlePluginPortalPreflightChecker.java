package com.example.springbootanalyzer.analyzer.gradle;

import com.example.springbootanalyzer.analyzer.model.Finding;
import com.example.springbootanalyzer.analyzer.model.FindingSeverity;
import com.example.springbootanalyzer.analyzer.model.gradle.GradleSettingsPluginModel;
import com.example.springbootanalyzer.config.AnalyzerProperties;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URLConnection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GradlePluginPortalPreflightChecker {

    private static final Logger LOGGER = LoggerFactory.getLogger(GradlePluginPortalPreflightChecker.class);

    public List<Finding> preflight(
            List<GradleSettingsPluginModel> settingsPlugins,
            AnalyzerProperties.GradleProperties properties
    ) {
        if (properties == null || !properties.preflightPluginMarker()) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (GradleSettingsPluginModel plugin : findUnreachableMarkers(settingsPlugins, properties)) {
                findings.add(new Finding(
                        FindingSeverity.INFO,
                        "Gradle Plugin Portal marker artifact could not be reached for %s:%s. Gradle model analysis may fail if the target build requires this settings plugin."
                                .formatted(plugin.pluginId(), plugin.version()),
                        plugin.sourceFile() == null || plugin.line() == null ? null : plugin.sourceFile() + ":" + plugin.line()
                ));
        }
        return List.copyOf(findings);
    }

    public List<GradleSettingsPluginModel> findUnreachableMarkers(
            List<GradleSettingsPluginModel> settingsPlugins,
            AnalyzerProperties.GradleProperties properties
    ) {
        if (properties == null || !properties.preflightPluginMarker()) {
            return List.of();
        }
        List<GradleSettingsPluginModel> unreachable = new ArrayList<>();
        for (GradleSettingsPluginModel plugin : settingsPlugins) {
            if (plugin.pluginId() == null || plugin.version() == null) {
                continue;
            }
            String markerUrl = GradleExecutionSupport.pluginMarkerUrl(properties.pluginRepositoryUrls().getFirst(), plugin.pluginId(), plugin.version());
            boolean reachable = isReachable(markerUrl, properties);
            LOGGER.info("Gradle plugin marker preflight: plugin={} version={} url={} reachable={}", plugin.pluginId(), plugin.version(), markerUrl, reachable);
            if (!reachable) {
                unreachable.add(plugin);
            }
        }
        return List.copyOf(unreachable);
    }

    private boolean isReachable(String markerUrl, AnalyzerProperties.GradleProperties properties) {
        try {
            URI uri = URI.create(markerUrl);
            URLConnection connection = uri.toURL().openConnection(proxy(properties));
            if (connection instanceof HttpURLConnection httpConnection) {
                httpConnection.setRequestMethod("HEAD");
                httpConnection.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
                httpConnection.setReadTimeout((int) Duration.ofSeconds(5).toMillis());
                int status = httpConnection.getResponseCode();
                return status >= 200 && status < 400;
            }
            return true;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private Proxy proxy(AnalyzerProperties.GradleProperties properties) {
        AnalyzerProperties.ProxyProperties proxy = properties.proxy();
        if (proxy != null && proxy.enabled() && proxy.host() != null && proxy.port() != null) {
            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxy.host(), proxy.port()));
        }
        return Proxy.NO_PROXY;
    }
}
