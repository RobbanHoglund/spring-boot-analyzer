package com.robbanhoglund.springbootanalyzer.analyzer.configuration;

import com.fasterxml.jackson.databind.MappingIterator;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class YamlConfigurationParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(YamlConfigurationParser.class);

    /** Modern (Spring Boot 2.4+) per-document profile activation key. */
    private static final String ACTIVATE_ON_PROFILE = "spring.config.activate.on-profile";

    /** Legacy (pre-2.4) per-document profile activation key. */
    private static final String LEGACY_PROFILES = "spring.profiles";

    private final ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());

    public List<ParsedConfigurationProperty> parse(Path path, String relativePath, String profile) {
        try (InputStream inputStream = Files.newInputStream(path);
                MappingIterator<Object> documents =
                        objectMapper.readValues(
                                objectMapper.createParser(inputStream), Object.class)) {
            List<ParsedConfigurationProperty> properties = new ArrayList<>();
            // A single YAML file may contain multiple "---"-separated documents (Spring Boot's
            // multi-profile pattern). Read every document, not just the first, and tag each one
            // with its in-file activation profile when present.
            while (documents.hasNext()) {
                Object root = documents.next();
                if (root == null) {
                    continue;
                }
                Map<String, String> flattened = new LinkedHashMap<>();
                flatten("", root, flattened);
                String documentProfile = resolveDocumentProfile(flattened, profile);
                for (Map.Entry<String, String> entry : flattened.entrySet()) {
                    properties.add(
                            new ParsedConfigurationProperty(
                                    entry.getKey(),
                                    entry.getValue(),
                                    relativePath,
                                    null,
                                    documentProfile));
                }
            }
            return properties;
        } catch (IOException exception) {
            // Skip an individual unreadable file rather than aborting configuration analysis.
            LOGGER.debug("Failed to parse yaml file {}; skipping", path, exception);
            return List.of();
        }
    }

    /**
     * Determines the profile a YAML document's properties belong to. A document guarded by
     * {@code spring.config.activate.on-profile} (or the legacy {@code spring.profiles}) is bound to
     * that profile; otherwise it falls back to the filename-derived profile.
     */
    private String resolveDocumentProfile(Map<String, String> flattened, String fallbackProfile) {
        String onProfile = flattened.get(ACTIVATE_ON_PROFILE);
        if (onProfile == null || onProfile.isBlank()) {
            onProfile = flattened.get(LEGACY_PROFILES);
        }
        if (onProfile == null || onProfile.isBlank()) {
            return fallbackProfile;
        }
        // on-profile may be a comma-separated list or a profile expression; use the first token.
        String first = onProfile.split(",")[0].trim();
        return first.isBlank() ? fallbackProfile : first;
    }

    @SuppressWarnings("unchecked")
    private void flatten(String prefix, Object value, Map<String, String> result) {
        if (value instanceof Map<?, ?> mapValue) {
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                String childKey =
                        prefix.isBlank()
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
