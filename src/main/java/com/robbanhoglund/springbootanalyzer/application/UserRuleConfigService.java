package com.robbanhoglund.springbootanalyzer.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingRule;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingRules;
import com.robbanhoglund.springbootanalyzer.api.dto.RuleInfoDto;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reads and writes the user-level rule configuration stored in
 * {@code ~/.spring-boot-analyzer/rule-config.json}.
 *
 * <p>All rules are enabled by default. A rule is disabled by adding its {@code ruleId} to the
 * {@code disabledRuleIds} list in the JSON file.
 */
@Service
public class UserRuleConfigService {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserRuleConfigService.class);
    private static final String CONFIG_DIR = ".spring-boot-analyzer";
    private static final String CONFIG_FILE = "rule-config.json";

    private final ObjectMapper objectMapper =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private Path configFilePath() {
        return Path.of(System.getProperty("user.home"), CONFIG_DIR, CONFIG_FILE);
    }

    /** Returns rule IDs that the user has explicitly disabled. Never null. */
    public Set<String> getDisabledRuleIds() {
        lock.readLock().lock();
        try {
            Path path = configFilePath();
            if (!Files.exists(path)) {
                return Collections.emptySet();
            }
            RuleConfigFile config = objectMapper.readValue(path.toFile(), RuleConfigFile.class);
            return config.disabledRuleIds() == null
                    ? Collections.emptySet()
                    : Set.copyOf(config.disabledRuleIds());
        } catch (IOException exception) {
            LOGGER.warn(
                    "Failed to read rule config file, treating all rules as enabled", exception);
            return Collections.emptySet();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Persists the given set of disabled rule IDs. */
    public void setDisabledRuleIds(Set<String> disabledRuleIds) {
        lock.writeLock().lock();
        try {
            Path path = configFilePath();
            Files.createDirectories(path.getParent());
            objectMapper.writeValue(
                    path.toFile(), new RuleConfigFile(new ArrayList<>(disabledRuleIds)));
        } catch (IOException exception) {
            LOGGER.error("Failed to write rule config file", exception);
            throw new RuntimeException("Could not save rule configuration", exception);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Returns all known rules with their current enabled/disabled state. */
    public List<RuleInfoDto> getAllRulesWithStatus() {
        Set<String> disabled = getDisabledRuleIds();
        return allRules().stream()
                .map(
                        rule ->
                                new RuleInfoDto(
                                        rule.ruleId(),
                                        rule.title(),
                                        rule.defaultSeverity().name(),
                                        rule.category().name(),
                                        rule.runtimeDetection().name(),
                                        !disabled.contains(rule.ruleId())))
                .toList();
    }

    private static List<FindingRule> allRules() {
        List<FindingRule> rules = new ArrayList<>();
        for (Field field : FindingRules.class.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers())
                    && Modifier.isStatic(field.getModifiers())
                    && FindingRule.class.equals(field.getType())) {
                try {
                    rules.add((FindingRule) field.get(null));
                } catch (IllegalAccessException ignored) {
                    // public field — should not happen
                }
            }
        }
        return rules;
    }

    /** Internal serialization model for the JSON file. */
    public record RuleConfigFile(List<String> disabledRuleIds) {
        /** Jackson no-arg constructor. */
        public RuleConfigFile() {
            this(new ArrayList<>());
        }
    }
}
