package com.robbanhoglund.springbootanalyzer.analyzer.configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PropertiesFileParser {

    public List<ParsedConfigurationProperty> parse(Path path, String relativePath, String profile) {
        try {
            List<String> lines = Files.readAllLines(path);
            List<ParsedConfigurationProperty> properties = new ArrayList<>();
            for (int index = 0; index < lines.size(); index++) {
                String rawLine = lines.get(index);
                String line = rawLine.trim();
                if (line.isBlank() || line.startsWith("#") || line.startsWith("!")) {
                    continue;
                }

                int separatorIndex = separatorIndex(line);
                if (separatorIndex < 0) {
                    continue;
                }

                String name = line.substring(0, separatorIndex).trim();
                String value = line.substring(separatorIndex + 1).trim();
                if (!name.isBlank()) {
                    properties.add(
                            new ParsedConfigurationProperty(
                                    name, value, relativePath, index + 1, profile));
                }
            }
            return properties;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to parse properties file: " + path, exception);
        }
    }

    private int separatorIndex(String line) {
        int equalsIndex = line.indexOf('=');
        int colonIndex = line.indexOf(':');
        if (equalsIndex < 0) {
            return colonIndex;
        }
        if (colonIndex < 0) {
            return equalsIndex;
        }
        return Math.min(equalsIndex, colonIndex);
    }
}
