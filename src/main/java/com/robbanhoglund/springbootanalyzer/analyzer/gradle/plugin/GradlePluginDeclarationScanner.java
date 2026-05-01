package com.robbanhoglund.springbootanalyzer.analyzer.gradle.plugin;

import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradlePluginDeclaration;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradlePluginDeclarationSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class GradlePluginDeclarationScanner {

    private static final Pattern GROOVY_ID_PATTERN =
            Pattern.compile("id\\s+['\"]([^'\"]+)['\"](?:\\s+version\\s+['\"]([^'\"]+)['\"])?(?:\\s+apply\\s+false)?");
    private static final Pattern METHOD_ID_PATTERN =
            Pattern.compile("id\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)(?:\\s*version\\s*['\"]([^'\"]+)['\"])?(?:\\s*apply\\s+false)?");
    private static final Pattern ALIAS_PATTERN =
            Pattern.compile("alias\\(\\s*libs\\.plugins\\.([A-Za-z0-9_.-]+)\\s*\\)(?:\\s*apply\\s+false)?");
    private static final List<String> FILE_PATTERNS = List.of(
            "settings.gradle",
            "settings.gradle.kts",
            "build.gradle",
            "build.gradle.kts"
    );

    private final GradleVersionCatalogPluginScanner versionCatalogPluginScanner;

    public GradlePluginDeclarationScanner(GradleVersionCatalogPluginScanner versionCatalogPluginScanner) {
        this.versionCatalogPluginScanner = versionCatalogPluginScanner;
    }

    public List<GradlePluginDeclaration> scan(Path repositoryRoot) {
        Map<String, GradleVersionCatalogPluginScanner.CatalogPlugin> aliases = versionCatalogPluginScanner.scan(repositoryRoot);
        List<GradlePluginDeclaration> declarations = new ArrayList<>();
        try (var paths = Files.walk(repositoryRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(this::isGradleBuildFile)
                    .filter(path -> !isExcluded(path))
                    .forEach(path -> declarations.addAll(scanFile(repositoryRoot, path, aliases)));
        } catch (IOException exception) {
            return List.of();
        }
        return deduplicate(declarations);
    }

    private List<GradlePluginDeclaration> scanFile(
            Path repositoryRoot,
            Path file,
            Map<String, GradleVersionCatalogPluginScanner.CatalogPlugin> aliases
    ) {
        List<GradlePluginDeclaration> declarations = new ArrayList<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException exception) {
            return List.of();
        }

        int depth = 0;
        Integer pluginManagementDepth = null;
        Integer pluginsDepth = null;
        GradlePluginDeclarationSource currentSource = GradlePluginDeclarationSource.UNKNOWN;
        boolean settingsFile = file.getFileName().toString().startsWith("settings.gradle");

        for (int index = 0; index < lines.size(); index++) {
            String rawLine = lines.get(index);
            String line = stripLineComment(rawLine);
            String trimmed = line.trim();
            int lineDepthBefore = depth;
            int opens = count(line, '{');
            int closes = count(line, '}');

            if (pluginManagementDepth == null && trimmed.matches(".*\\bpluginManagement\\b.*\\{.*")) {
                pluginManagementDepth = lineDepthBefore + opens - closes;
            }
            if (pluginsDepth == null && trimmed.matches(".*\\bplugins\\b\\s*\\{.*")) {
                pluginsDepth = lineDepthBefore + opens - closes;
                currentSource = pluginManagementDepth != null && lineDepthBefore >= pluginManagementDepth
                        ? GradlePluginDeclarationSource.PLUGIN_MANAGEMENT_PLUGINS_BLOCK
                        : settingsFile
                                ? GradlePluginDeclarationSource.SETTINGS_PLUGINS_BLOCK
                                : GradlePluginDeclarationSource.PROJECT_PLUGINS_BLOCK;
            } else if (pluginsDepth != null && lineDepthBefore >= pluginsDepth) {
                declarations.addAll(extractDeclarations(repositoryRoot, file, trimmed, index + 1, currentSource, aliases));
            }

            depth = Math.max(0, depth + opens - closes);
            if (pluginsDepth != null && depth < pluginsDepth) {
                pluginsDepth = null;
                currentSource = GradlePluginDeclarationSource.UNKNOWN;
            }
            if (pluginManagementDepth != null && depth < pluginManagementDepth) {
                pluginManagementDepth = null;
            }
        }

        return declarations;
    }

    private List<GradlePluginDeclaration> extractDeclarations(
            Path repositoryRoot,
            Path file,
            String line,
            int lineNumber,
            GradlePluginDeclarationSource source,
            Map<String, GradleVersionCatalogPluginScanner.CatalogPlugin> aliases
    ) {
        if (line.isBlank() || line.startsWith("//")) {
            return List.of();
        }
        List<GradlePluginDeclaration> declarations = new ArrayList<>();
        boolean applyFalse = line.contains("apply false");

        Matcher aliasMatcher = ALIAS_PATTERN.matcher(line);
        while (aliasMatcher.find()) {
            String aliasToken = aliasMatcher.group(1);
            String normalizedAlias = aliasToken.replace('.', '-');
            GradleVersionCatalogPluginScanner.CatalogPlugin alias = aliases.get(normalizedAlias);
            if (alias != null) {
                declarations.add(new GradlePluginDeclaration(
                        alias.pluginId(),
                        alias.version(),
                        repositoryRoot.relativize(file).toString().replace('\\', '/'),
                        lineNumber,
                        GradlePluginDeclarationSource.VERSION_CATALOG_ALIAS,
                        applyFalse
                ));
            }
        }

        declarations.addAll(matchIdDeclarations(repositoryRoot, file, line, lineNumber, source, applyFalse, GROOVY_ID_PATTERN));
        declarations.addAll(matchIdDeclarations(repositoryRoot, file, line, lineNumber, source, applyFalse, METHOD_ID_PATTERN));
        return declarations;
    }

    private List<GradlePluginDeclaration> matchIdDeclarations(
            Path repositoryRoot,
            Path file,
            String line,
            int lineNumber,
            GradlePluginDeclarationSource source,
            boolean applyFalse,
            Pattern pattern
    ) {
        List<GradlePluginDeclaration> declarations = new ArrayList<>();
        Matcher matcher = pattern.matcher(line);
        while (matcher.find()) {
            declarations.add(new GradlePluginDeclaration(
                    matcher.group(1),
                    matcher.group(2),
                    repositoryRoot.relativize(file).toString().replace('\\', '/'),
                    lineNumber,
                    source,
                    applyFalse
            ));
        }
        return declarations;
    }

    private List<GradlePluginDeclaration> deduplicate(List<GradlePluginDeclaration> declarations) {
        Map<String, GradlePluginDeclaration> deduplicated = new LinkedHashMap<>();
        for (GradlePluginDeclaration declaration : declarations) {
            String key = String.join("|",
                    declaration.pluginId() == null ? "" : declaration.pluginId(),
                    declaration.version() == null ? "" : declaration.version(),
                    declaration.sourceFile() == null ? "" : declaration.sourceFile(),
                    declaration.line() == null ? "" : declaration.line().toString(),
                    declaration.source() == null ? "" : declaration.source().name());
            deduplicated.putIfAbsent(key, declaration);
        }
        return List.copyOf(deduplicated.values());
    }

    private boolean isGradleBuildFile(Path file) {
        String name = file.getFileName().toString();
        return FILE_PATTERNS.contains(name);
    }

    private boolean isExcluded(Path path) {
        for (Path part : path) {
            String name = part.toString();
            if (List.of(".gradle", "build", "target", "node_modules", ".git").contains(name)) {
                return true;
            }
        }
        return false;
    }

    private String stripLineComment(String line) {
        int index = line.indexOf("//");
        return index >= 0 ? line.substring(0, index) : line;
    }

    private int count(String value, char needle) {
        int total = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == needle) {
                total++;
            }
        }
        return total;
    }
}
