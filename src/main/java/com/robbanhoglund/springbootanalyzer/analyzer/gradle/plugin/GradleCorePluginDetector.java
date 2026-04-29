package com.robbanhoglund.springbootanalyzer.analyzer.gradle.plugin;

import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class GradleCorePluginDetector {

    private static final Set<String> CORE_PLUGIN_IDS = Set.of(
            "java",
            "java-library",
            "application",
            "groovy",
            "scala",
            "maven-publish",
            "signing",
            "jacoco",
            "checkstyle",
            "pmd",
            "idea",
            "eclipse",
            "war",
            "ear",
            "base",
            "distribution"
    );

    public boolean isCorePlugin(String pluginId) {
        return pluginId != null && CORE_PLUGIN_IDS.contains(pluginId);
    }
}
