package com.robbanhoglund.springbootanalyzer.analyzer.gradle;

import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradleSettingsPluginModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class GradleSettingsPluginScanner {

    private static final Pattern GROOVY_ID_WITH_VERSION =
            Pattern.compile("id\\s+['\"]([^'\"]+)['\"]\\s+version\\s+['\"]([^'\"]+)['\"]");
    private static final Pattern KOTLIN_ID_WITH_VERSION =
            Pattern.compile("id\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)\\s*version\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern ID_WITHOUT_VERSION =
            Pattern.compile("id(?:\\(|\\s+)\\s*['\"]([^'\"]+)['\"]\\s*\\)?");

    public List<GradleSettingsPluginModel> scan(Path repositoryRoot) {
        List<GradleSettingsPluginModel> plugins = new ArrayList<>();
        scanFile(repositoryRoot.resolve("settings.gradle"), plugins);
        scanFile(repositoryRoot.resolve("settings.gradle.kts"), plugins);
        return List.copyOf(plugins);
    }

    private void scanFile(Path file, List<GradleSettingsPluginModel> plugins) {
        if (Files.notExists(file)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(file);
            for (int index = 0; index < lines.size(); index++) {
                String line = stripComments(lines.get(index));
                if (line.isBlank()) {
                    continue;
                }
                Matcher matcher = GROOVY_ID_WITH_VERSION.matcher(line);
                boolean found = matcher.find();
                if (!found) {
                    matcher = KOTLIN_ID_WITH_VERSION.matcher(line);
                    found = matcher.find();
                }
                if (found) {
                    plugins.add(new GradleSettingsPluginModel(
                            matcher.group(1),
                            matcher.group(2),
                            file.getFileName().toString(),
                            index + 1
                    ));
                    continue;
                }

                Matcher withoutVersionMatcher = ID_WITHOUT_VERSION.matcher(line);
                boolean withoutVersionFound = withoutVersionMatcher.find();
                if (withoutVersionFound && line.contains("plugins")) {
                    continue;
                }
                if (withoutVersionFound && (line.contains("id(") || line.contains("id "))) {
                    plugins.add(new GradleSettingsPluginModel(
                            withoutVersionMatcher.group(1),
                            null,
                            file.getFileName().toString(),
                            index + 1
                    ));
                }
            }
        } catch (IOException ignored) {
        }
    }

    private String stripComments(String line) {
        String withoutSlash = line.replaceAll("//.*$", "");
        return withoutSlash.replaceAll("#.*$", "").trim();
    }
}
