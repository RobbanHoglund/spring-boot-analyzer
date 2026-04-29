package com.example.springbootanalyzer.analyzer.configuration;

import com.example.springbootanalyzer.analyzer.model.configuration.PropertyDocumentation;
import com.example.springbootanalyzer.analyzer.model.configuration.PropertyValueHint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SpringConfigurationMetadataCatalog {

    private static final List<String> REPOSITORY_METADATA_PATHS = List.of(
            "src/main/resources/META-INF/spring-configuration-metadata.json",
            "src/main/resources/META-INF/additional-spring-configuration-metadata.json"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    public MetadataCatalog load(Path repositoryRoot) {
        Map<String, MetadataProperty> properties = new LinkedHashMap<>();

        try {
            Enumeration<java.net.URL> resources = getClass().getClassLoader()
                    .getResources("META-INF/spring-configuration-metadata.json");
            while (resources.hasMoreElements()) {
                try (InputStream stream = resources.nextElement().openStream()) {
                    mergeMetadata(properties, stream, false);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load Spring configuration metadata from classpath.", exception);
        }

        for (String metadataPath : REPOSITORY_METADATA_PATHS) {
            Path path = repositoryRoot.resolve(metadataPath);
            if (Files.notExists(path)) {
                continue;
            }
            try (InputStream stream = Files.newInputStream(path)) {
                mergeMetadata(properties, stream, true);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to load project configuration metadata: " + path, exception);
            }
        }

        return new MetadataCatalog(Collections.unmodifiableMap(properties));
    }

    private void mergeMetadata(Map<String, MetadataProperty> target, InputStream inputStream, boolean customSource)
            throws IOException {
        JsonNode root = objectMapper.readTree(inputStream);

        Map<String, List<PropertyValueHint>> hintsByName = new LinkedHashMap<>();
        for (JsonNode hintNode : root.path("hints")) {
            String name = textValue(hintNode, "name");
            if (name == null || name.isBlank()) {
                continue;
            }
            List<PropertyValueHint> hints = new ArrayList<>();
            for (JsonNode valueNode : hintNode.path("values")) {
                hints.add(new PropertyValueHint(
                        textValue(valueNode, "value"),
                        textValue(valueNode, "description")
                ));
            }
            hintsByName.put(name, List.copyOf(hints));
        }

        for (JsonNode propertyNode : root.path("properties")) {
            String name = textValue(propertyNode, "name");
            if (name == null || name.isBlank()) {
                continue;
            }

            JsonNode deprecationNode = propertyNode.path("deprecation");
            boolean deprecated = propertyNode.path("deprecated").asBoolean(false) || !deprecationNode.isMissingNode();
            String deprecationReason = textValue(deprecationNode, "reason");

            PropertyDocumentation documentation = new PropertyDocumentation(
                    true,
                    textValue(propertyNode, "type"),
                    textValue(propertyNode, "description"),
                    textValue(propertyNode, "defaultValue"),
                    textValue(propertyNode, "sourceType"),
                    deprecated,
                    deprecationReason,
                    hintsByName.getOrDefault(name, List.of())
            );
            target.put(name, new MetadataProperty(name, documentation, customSource));
        }
    }

    private String textValue(JsonNode node, String fieldName) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        JsonNode field = node.path(fieldName);
        if (field.isMissingNode() || field.isNull()) {
            return null;
        }
        return field.isValueNode() ? field.asText() : field.toString();
    }

    public record MetadataCatalog(Map<String, MetadataProperty> properties) {
        public MetadataProperty find(String propertyName) {
            return properties.get(propertyName);
        }

        public List<String> names() {
            return new ArrayList<>(new LinkedHashSet<>(properties.keySet()));
        }
    }

    public record MetadataProperty(
            String name,
            PropertyDocumentation documentation,
            boolean custom
    ) {
    }
}
