package com.example.springbootanalyzer.analyzer.gradle.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class GradleVersionCatalogPluginScanner {

    private static final Pattern SIMPLE_VALUE_PATTERN =
            Pattern.compile("^([A-Za-z0-9_.-]+)\\s*=\\s*\"([^\"]+)\"\\s*$");
    private static final Pattern INLINE_PLUGIN_PATTERN =
            Pattern.compile("^([A-Za-z0-9_.-]+)\\s*=\\s*\\{\\s*id\\s*=\\s*\"([^\"]+)\"\\s*,\\s*(version|version\\.ref)\\s*=\\s*\"([^\"]+)\"\\s*}.*$");

    public Map<String, CatalogPlugin> scan(Path repositoryRoot) {
        Path catalog = repositoryRoot.resolve("gradle").resolve("libs.versions.toml");
        if (Files.notExists(catalog)) {
            return Map.of();
        }
        try {
            return parse(catalog);
        } catch (IOException exception) {
            return Map.of();
        }
    }

    Map<String, CatalogPlugin> parse(Path catalog) throws IOException {
        Map<String, String> versions = new LinkedHashMap<>();
        Map<String, CatalogPlugin> plugins = new LinkedHashMap<>();
        String section = "";
        for (String rawLine : Files.readAllLines(catalog)) {
            String line = stripComment(rawLine).trim();
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line;
                continue;
            }
            if ("[versions]".equals(section)) {
                Matcher matcher = SIMPLE_VALUE_PATTERN.matcher(line);
                if (matcher.matches()) {
                    versions.put(matcher.group(1), matcher.group(2));
                }
                continue;
            }
            if ("[plugins]".equals(section)) {
                Matcher matcher = INLINE_PLUGIN_PATTERN.matcher(line);
                if (matcher.matches()) {
                    String alias = matcher.group(1);
                    String pluginId = matcher.group(2);
                    String key = matcher.group(3);
                    String rawVersion = matcher.group(4);
                    String version = "version.ref".equals(key) ? versions.get(rawVersion) : rawVersion;
                    plugins.put(alias, new CatalogPlugin(alias, pluginId, version));
                }
            }
        }
        return Map.copyOf(plugins);
    }

    private String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash >= 0 ? line.substring(0, hash) : line;
    }

    public record CatalogPlugin(
            String alias,
            String pluginId,
            String version
    ) {
    }
}
