package com.robbanhoglund.springbootanalyzer.suppression;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Applies repository-level finding suppressions declared in {@code .analyzer-suppress.yml}.
 *
 * <p>The suppression file is read from the root of the <em>analyzed</em> repository (not the
 * analyzer's own source tree). If the file is absent or cannot be parsed, all findings are
 * returned unchanged and a warning is logged.
 *
 * <p>Suppressed findings are removed from the result entirely. The count of suppressed findings
 * is logged at INFO level so it is visible in server logs and CI output.
 */
@Service
public class SuppressionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SuppressionService.class);
    private static final String SUPPRESSION_FILE = ".analyzer-suppress.yml";

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    /**
     * Loads the suppression file from {@code repositoryRoot} and removes any findings whose
     * {@code ruleId} matches a suppression entry. Returns the original list unchanged if the file
     * is absent or empty.
     */
    public List<Finding> apply(List<Finding> findings, Path repositoryRoot) {
        SuppressionConfig config = loadConfig(repositoryRoot);
        if (config.suppress().isEmpty()) {
            return findings;
        }

        Set<String> suppressedIds =
                config.suppress().stream()
                        .map(SuppressionEntry::ruleId)
                        .filter(id -> id != null && !id.isBlank())
                        .collect(Collectors.toSet());

        if (suppressedIds.isEmpty()) {
            return findings;
        }

        List<Finding> filtered =
                findings.stream()
                        .filter(f -> f.ruleId() == null || !suppressedIds.contains(f.ruleId()))
                        .toList();

        int removed = findings.size() - filtered.size();
        if (removed > 0) {
            LOGGER.info(
                    "Suppression applied: {} finding(s) suppressed by {} (suppressed rule IDs: {})",
                    removed,
                    SUPPRESSION_FILE,
                    suppressedIds);
        }
        return filtered;
    }

    private SuppressionConfig loadConfig(Path repositoryRoot) {
        Path configFile = repositoryRoot.resolve(SUPPRESSION_FILE);
        if (Files.notExists(configFile)) {
            return SuppressionConfig.empty();
        }
        try {
            SuppressionConfig config =
                    yamlMapper.readValue(configFile.toFile(), SuppressionConfig.class);
            LOGGER.info(
                    "Loaded suppression config: {} entries from {}",
                    config.suppress().size(),
                    configFile);
            return config;
        } catch (IOException exception) {
            LOGGER.warn(
                    "Failed to parse suppression file {} — all findings will be reported",
                    configFile,
                    exception);
            return SuppressionConfig.empty();
        }
    }
}
