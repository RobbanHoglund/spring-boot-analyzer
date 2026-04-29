package com.example.springbootanalyzer.analyzer.configuration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class YamlConfigurationParser {

    private final ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());

    public List<ParsedConfigurationProperty> parse(Path path, String relativePath, String profile) {
        try (InputStream inputStream = Files.newInputStream(path)) {
            Object root = objectMapper.readValue(inputStream, new TypeReference<>() {
            });
            Map<String, String> flattened = new LinkedHashMap<>();
            flatten("", root, flattened);
            List<ParsedConfigurationProperty> properties = new ArrayList<>();
            for (Map.Entry<String, String> entry : flattened.entrySet()) {
                properties.add(new ParsedConfigurationProperty(entry.getKey(), entry.getValue(), relativePath, null, profile));
            }
            return properties;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to parse yaml file: " + path, exception);
        }
    }

    @SuppressWarnings("unchecked")
    private void flatten(String prefix, Object value, Map<String, String> result) {
        if (value instanceof Map<?, ?> mapValue) {
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                String childKey = prefix.isBlank()
                        ? String.valueOf(entry.getKey())
                        : prefix + "." + entry.getKey();
                flatten(childKey, entry.getValue(), result);
            }
            return;
        }

        if (value instanceof List<?> listValue) {
            for (int index = 0; index < listValue.size(); index++) {
                flatten(prefix + "[" + index + "]", listValue.get(index), result);
            }
            return;
        }

        if (!prefix.isBlank()) {
            result.put(prefix, value == null ? "" : String.valueOf(value));
        }
    }
}
