package com.robbanhoglund.springbootanalyzer.analyzer.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.PropertyDocumentation;
import com.robbanhoglund.springbootanalyzer.analyzer.model.configuration.PropertyValueHint;
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

    private static final List<String> REPOSITORY_METADATA_PATHS =
            List.of(
                    "src/main/resources/META-INF/spring-configuration-metadata.json",
                    "src/main/resources/META-INF/additional-spring-configuration-metadata.json");

    /**
     * Metadata of the Spring Boot 4 modules, generated from the published 4.0 artifacts. The
     * analyzer itself runs on Spring Boot 3.5, so its classpath only describes 3.5 properties; this
     * overlay adds the 4.x names and deprecations for projects on Spring Boot 4.
     */
    private static final String BOOT_4_METADATA =
            "metadata/spring-boot-4.0-configuration-metadata.json";

    /** Starts the deprecation reason of keys Spring Boot no longer binds (level "error"). */
    static final String UNSUPPORTED_PREFIX = "No longer supported.";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Whether the metadata says Spring Boot no longer binds the property at all. */
    public static boolean isUnsupported(PropertyDocumentation documentation) {
        return documentation != null
                && documentation.deprecated()
                && documentation.deprecationReason() != null
                && documentation.deprecationReason().startsWith(UNSUPPORTED_PREFIX);
    }

    public MetadataCatalog load(Path repositoryRoot) {
        return load(repositoryRoot, null);
    }

    /**
     * Loads the configuration metadata that applies to a project.
     *
     * @param repositoryRoot the analyzed repository
     * @param springBootVersion the project's Spring Boot version, or null when unknown
     */
    public MetadataCatalog load(Path repositoryRoot, String springBootVersion) {
        Map<String, MetadataProperty> properties = new LinkedHashMap<>();

        try {
            Enumeration<java.net.URL> resources =
                    getClass()
                            .getClassLoader()
                            .getResources("META-INF/spring-configuration-metadata.json");
            while (resources.hasMoreElements()) {
                try (InputStream stream = resources.nextElement().openStream()) {
                    mergeMetadata(properties, stream, false);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to load Spring configuration metadata from classpath.", exception);
        }
        if (SpringBootMajor.of(springBootVersion) >= 4) {
            try (InputStream stream =
                    getClass().getClassLoader().getResourceAsStream(BOOT_4_METADATA)) {
                if (stream != null) {
                    mergeMetadata(properties, stream, false);
                }
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "Failed to load bundled Spring Boot 4 configuration metadata.", exception);
            }
        }

        for (String metadataPath : REPOSITORY_METADATA_PATHS) {
            Path path = repositoryRoot.resolve(metadataPath);
            if (Files.notExists(path)) {
                continue;
            }
            try (InputStream stream = Files.newInputStream(path)) {
                mergeMetadata(properties, stream, true);
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "Failed to load project configuration metadata: " + path, exception);
            }
        }

        return new MetadataCatalog(Collections.unmodifiableMap(properties));
    }

    private void mergeMetadata(
            Map<String, MetadataProperty> target, InputStream inputStream, boolean customSource)
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
                hints.add(
                        new PropertyValueHint(
                                textValue(valueNode, "value"),
                                textValue(valueNode, "description")));
            }
            hintsByName.put(name, List.copyOf(hints));
        }

        for (JsonNode propertyNode : root.path("properties")) {
            String name = textValue(propertyNode, "name");
            if (name == null || name.isBlank()) {
                continue;
            }

            JsonNode deprecationNode = propertyNode.path("deprecation");
            boolean deprecated =
                    propertyNode.path("deprecated").asBoolean(false)
                            || !deprecationNode.isMissingNode();
            String deprecationReason = textValue(deprecationNode, "reason");
            String replacement = textValue(deprecationNode, "replacement");
            if (deprecationReason == null && replacement != null) {
                deprecationReason = "Replaced by '" + replacement + "'.";
            }
            // Level "error" means Spring Boot no longer binds the key at all.
            if ("error".equals(textValue(deprecationNode, "level"))) {
                deprecationReason =
                        deprecationReason == null
                                ? UNSUPPORTED_PREFIX
                                : UNSUPPORTED_PREFIX + " " + deprecationReason;
            }
            // A later source that only deprecates a key (Spring Boot 4 for spring.jackson.parser)
            // keeps the type the earlier source declared, so map entries still find their owner.
            String type = textValue(propertyNode, "type");
            MetadataProperty previous = target.get(name);
            if (type == null && previous != null && previous.documentation() != null) {
                type = previous.documentation().type();
            }

            PropertyDocumentation documentation =
                    new PropertyDocumentation(
                            true,
                            type,
                            textValue(propertyNode, "description"),
                            textValue(propertyNode, "defaultValue"),
                            textValue(propertyNode, "sourceType"),
                            deprecated,
                            deprecationReason,
                            hintsByName.getOrDefault(name, List.of()));
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

        /** The map-typed property whose entries include {@code propertyName}, or null. */
        public MetadataProperty findMapOwner(String propertyName) {
            MetadataProperty owner = null;
            for (MetadataProperty candidate : properties.values()) {
                String type =
                        candidate.documentation() == null ? null : candidate.documentation().type();
                if (type == null
                        || !type.startsWith("java.util.Map<")
                        || !propertyName.startsWith(candidate.name() + ".")) {
                    continue;
                }
                if (owner == null || candidate.name().length() > owner.name().length()) {
                    owner = candidate;
                }
            }
            return owner;
        }

        public List<String> names() {
            return new ArrayList<>(new LinkedHashSet<>(properties.keySet()));
        }
    }

    public record MetadataProperty(
            String name, PropertyDocumentation documentation, boolean custom) {}

    /** Major version of a Spring Boot version string; -1 when unknown. */
    private static final class SpringBootMajor {
        private SpringBootMajor() {}

        static int of(String version) {
            if (version == null || version.isBlank()) {
                return -1;
            }
            try {
                return Integer.parseInt(version.trim().split("[^0-9]")[0]);
            } catch (NumberFormatException exception) {
                return -1;
            }
        }
    }
}
