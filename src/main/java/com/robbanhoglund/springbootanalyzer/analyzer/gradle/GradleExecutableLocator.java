package com.robbanhoglund.springbootanalyzer.analyzer.gradle;

import com.robbanhoglund.springbootanalyzer.config.AnalyzerProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class GradleExecutableLocator {

    public Path findSystemGradleExecutable(AnalyzerProperties.GradleProperties properties) {
        if (properties.executable() != null && Files.isRegularFile(properties.executable())) {
            return properties.executable().toAbsolutePath().normalize();
        }
        String pathValue = System.getenv("PATH");
        if (pathValue == null || pathValue.isBlank()) {
            return null;
        }
        for (String candidateName : executableNames()) {
            for (String directory : pathValue.split(java.io.File.pathSeparator)) {
                if (directory == null || directory.isBlank()) {
                    continue;
                }
                Path candidate = Path.of(directory).resolve(candidateName);
                if (Files.isRegularFile(candidate)) {
                    return candidate.toAbsolutePath().normalize();
                }
            }
        }
        return null;
    }

    private List<String> executableNames() {
        String os = System.getProperty("os.name", "").toLowerCase();
        List<String> names = new ArrayList<>();
        if (os.contains("win")) {
            names.add("gradle.bat");
            names.add("gradle.exe");
        } else {
            names.add("gradle");
        }
        return List.copyOf(names);
    }
}
