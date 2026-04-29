package com.example.springbootanalyzer.analyzer.configuration;

import com.example.springbootanalyzer.analyzer.model.BuildInfo;
import com.example.springbootanalyzer.analyzer.model.Finding;
import com.example.springbootanalyzer.analyzer.model.FindingSeverity;
import com.example.springbootanalyzer.analyzer.model.configuration.ApplicationProperty;
import com.example.springbootanalyzer.analyzer.model.configuration.ConfigurationAnalysis;
import com.example.springbootanalyzer.analyzer.model.configuration.ConfigurationFile;
import com.example.springbootanalyzer.analyzer.model.configuration.ConfigurationPropertiesClass;
import com.example.springbootanalyzer.analyzer.model.configuration.ConfigurationSummary;
import com.example.springbootanalyzer.analyzer.model.configuration.CustomPropertyDefinition;
import com.example.springbootanalyzer.analyzer.model.configuration.PropertyDocumentation;
import com.example.springbootanalyzer.analyzer.model.configuration.PropertyKind;
import com.example.springbootanalyzer.analyzer.model.configuration.PropertyReference;
import com.example.springbootanalyzer.analyzer.model.configuration.PropertySourceType;
import com.example.springbootanalyzer.analyzer.model.configuration.PropertyValueHint;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ConfigurationAnalyzer {

    private static final List<MapPrefixMetadata> SPRING_BOOT_MAP_PREFIXES = List.of(
            new MapPrefixMetadata(
                    "spring.mail.properties.",
                    "org.springframework.boot.autoconfigure.mail.MailProperties",
                    "JavaMail session property passed through via spring.mail.properties.*",
                    "java.lang.String"
            ),
            new MapPrefixMetadata(
                    "spring.jpa.properties.",
                    "org.springframework.boot.autoconfigure.orm.jpa.JpaProperties",
                    "JPA vendor property passed through via spring.jpa.properties.*",
                    "java.lang.String"
            ),
            new MapPrefixMetadata(
                    "spring.kafka.properties.",
                    "org.springframework.boot.autoconfigure.kafka.KafkaProperties",
                    "Kafka client property passed through via spring.kafka.properties.*",
                    "java.lang.String"
            ),
            new MapPrefixMetadata(
                    "management.metrics.tags.",
                    "org.springframework.boot.actuate.autoconfigure.metrics.MetricsProperties",
                    "Actuator metrics tag configured via management.metrics.tags.*",
                    "java.lang.String"
            )
    );

    private static final List<ThirdPartyPrefixMetadata> THIRD_PARTY_PREFIXES = List.of(
            new ThirdPartyPrefixMetadata("springdoc.", "springdoc-openapi"),
            new ThirdPartyPrefixMetadata("resilience4j.", "Resilience4j")
    );

    private final ConfigurationFileScanner configurationFileScanner;
    private final PropertiesFileParser propertiesFileParser;
    private final YamlConfigurationParser yamlConfigurationParser;
    private final SpringConfigurationMetadataCatalog springConfigurationMetadataCatalog;
    private final ConfigurationPropertiesClassAnalyzer configurationPropertiesClassAnalyzer;
    private final PropertyReferenceAnalyzer propertyReferenceAnalyzer;
    private final SensitivePropertyValueRedactor redactor;
    private final PropertyNameNormalizer propertyNameNormalizer;

    public ConfigurationAnalyzer(
            ConfigurationFileScanner configurationFileScanner,
            PropertiesFileParser propertiesFileParser,
            YamlConfigurationParser yamlConfigurationParser,
            SpringConfigurationMetadataCatalog springConfigurationMetadataCatalog,
            ConfigurationPropertiesClassAnalyzer configurationPropertiesClassAnalyzer,
            PropertyReferenceAnalyzer propertyReferenceAnalyzer,
            SensitivePropertyValueRedactor redactor,
            PropertyNameNormalizer propertyNameNormalizer
    ) {
        this.configurationFileScanner = configurationFileScanner;
        this.propertiesFileParser = propertiesFileParser;
        this.yamlConfigurationParser = yamlConfigurationParser;
        this.springConfigurationMetadataCatalog = springConfigurationMetadataCatalog;
        this.configurationPropertiesClassAnalyzer = configurationPropertiesClassAnalyzer;
        this.propertyReferenceAnalyzer = propertyReferenceAnalyzer;
        this.redactor = redactor;
        this.propertyNameNormalizer = propertyNameNormalizer;
    }

    public Result analyze(Path repositoryRoot, BuildInfo buildInfo) {
        SpringConfigurationMetadataCatalog.MetadataCatalog metadataCatalog =
                springConfigurationMetadataCatalog.load(repositoryRoot);
        List<ConfigurationPropertiesClass> customConfigurationClasses =
                configurationPropertiesClassAnalyzer.analyze(repositoryRoot);
        List<PropertyReference> propertyReferences = propertyReferenceAnalyzer.analyze(repositoryRoot);

        Map<String, CustomPropertyContext> customPropertyDefinitions = indexCustomPropertyDefinitions(customConfigurationClasses);
        List<Finding> findings = new ArrayList<>();

        List<ConfigurationFileScanner.ConfigurationCandidate> candidates = configurationFileScanner.scan(repositoryRoot);
        List<ConfigurationFile> files = new ArrayList<>();
        List<ApplicationProperty> configuredProperties = new ArrayList<>();
        Map<String, ParsedConfigurationProperty> rawConfiguredProperties = new LinkedHashMap<>();

        for (ConfigurationFileScanner.ConfigurationCandidate candidate : candidates) {
            List<ParsedConfigurationProperty> parsedProperties = parse(candidate);
            files.add(new ConfigurationFile(
                    candidate.relativePath(),
                    candidate.profile(),
                    candidate.sourceType(),
                    parsedProperties.size()
            ));

            for (ParsedConfigurationProperty parsedProperty : parsedProperties) {
                String normalizedName = propertyNameNormalizer.normalize(parsedProperty.name());
                rawConfiguredProperties.put(normalizedName + "@" + candidate.relativePath() + "@" + candidate.profile(), parsedProperty);
                configuredProperties.add(toApplicationProperty(
                        parsedProperty,
                        metadataCatalog,
                        customPropertyDefinitions,
                        propertyReferences,
                        buildInfo
                ));
            }
        }

        Map<String, ApplicationProperty> propertiesByName = new LinkedHashMap<>();
        for (ApplicationProperty property : configuredProperties) {
            propertiesByName.put(propertyNameNormalizer.normalize(property.name()), property);
        }

        List<PropertyReference> referencedOnly = new ArrayList<>();
        for (PropertyReference reference : propertyReferences) {
            String normalizedName = propertyNameNormalizer.normalize(reference.propertyName());
            if (!propertiesByName.containsKey(normalizedName)) {
                referencedOnly.add(reference);
            }
        }

        List<ApplicationProperty> allProperties = new ArrayList<>(configuredProperties);
        for (PropertyReference reference : referencedOnly) {
            String normalizedName = propertyNameNormalizer.normalize(reference.propertyName());
            if (allProperties.stream().anyMatch(property -> propertyNameNormalizer.normalize(property.name()).equals(normalizedName))) {
                continue;
            }

            PropertyDocumentation documentation = documentationFor(normalizedName, metadataCatalog, customPropertyDefinitions, buildInfo);
            allProperties.add(new ApplicationProperty(
                    reference.propertyName(),
                    null,
                    false,
                    reference.sourceFile(),
                    null,
                    null,
                    classifyReferencedProperty(normalizedName, reference, documentation, buildInfo),
                    documentation,
                    matchingReferences(reference.propertyName(), propertyReferences)
            ));
        }

        for (Map.Entry<String, CustomPropertyContext> entry : customPropertyDefinitions.entrySet()) {
            String propertyName = entry.getKey();
            if (allProperties.stream().anyMatch(property -> propertyNameNormalizer.normalize(property.name()).equals(propertyName))) {
                continue;
            }

            PropertyDocumentation documentation = documentationFor(propertyName, metadataCatalog, customPropertyDefinitions, buildInfo);
            allProperties.add(new ApplicationProperty(
                    propertyName,
                    null,
                    false,
                    entry.getValue().sourceClass().sourceFile(),
                    null,
                    null,
                    PropertyKind.CUSTOM_CONFIGURATION_PROPERTIES,
                    documentation,
                    matchingReferences(propertyName, propertyReferences)
            ));
        }

        addConfigurationFindings(configuredProperties, rawConfiguredProperties, referencedOnly, files, customConfigurationClasses, findings);

        ConfigurationSummary summary = buildSummary(configuredProperties, propertyReferences);
        allProperties.sort(Comparator.comparing(ApplicationProperty::name));

        return new Result(
                new ConfigurationAnalysis(
                        List.copyOf(files),
                        List.copyOf(allProperties),
                        List.copyOf(propertyReferences),
                        List.copyOf(customConfigurationClasses),
                        summary
                ),
                List.copyOf(findings)
        );
    }

    private List<ParsedConfigurationProperty> parse(ConfigurationFileScanner.ConfigurationCandidate candidate) {
        return switch (candidate.sourceType()) {
            case PROPERTIES -> propertiesFileParser.parse(candidate.absolutePath(), candidate.relativePath(), candidate.profile());
            case YAML -> yamlConfigurationParser.parse(candidate.absolutePath(), candidate.relativePath(), candidate.profile());
            default -> List.of();
        };
    }

    private ApplicationProperty toApplicationProperty(
            ParsedConfigurationProperty parsedProperty,
            SpringConfigurationMetadataCatalog.MetadataCatalog metadataCatalog,
            Map<String, CustomPropertyContext> customPropertyDefinitions,
            List<PropertyReference> propertyReferences,
            BuildInfo buildInfo
    ) {
        String normalizedName = propertyNameNormalizer.normalize(parsedProperty.name());
        PropertyDocumentation documentation = documentationFor(normalizedName, metadataCatalog, customPropertyDefinitions, buildInfo);
        List<PropertyReference> references = matchingReferences(normalizedName, propertyReferences);
        PropertyKind propertyKind = determinePropertyKind(normalizedName, documentation, customPropertyDefinitions, references, buildInfo);
        boolean sensitive = redactor.isSensitive(normalizedName);

        return new ApplicationProperty(
                normalizedName,
                sensitive ? redactor.redact(parsedProperty.value()) : formatDisplayValue(parsedProperty.value()),
                sensitive,
                parsedProperty.sourceFile(),
                parsedProperty.line(),
                parsedProperty.profile(),
                propertyKind,
                documentation,
                references
        );
    }

    private PropertyDocumentation documentationFor(
            String propertyName,
            SpringConfigurationMetadataCatalog.MetadataCatalog metadataCatalog,
            Map<String, CustomPropertyContext> customPropertyDefinitions,
            BuildInfo buildInfo
    ) {
        SpringConfigurationMetadataCatalog.MetadataProperty metadataProperty = metadataCatalog.find(propertyName);
        if (metadataProperty != null) {
            return metadataProperty.documentation();
        }

        MapPrefixMetadata mapMetadata = mapMetadataFor(propertyName);
        if (mapMetadata != null) {
            return new PropertyDocumentation(
                    true,
                    mapMetadata.type(),
                    mapMetadata.description(),
                    null,
                    mapMetadata.sourceType(),
                    false,
                    null,
                    List.of()
            );
        }

        ThirdPartyPrefixMetadata thirdPartyMetadata = thirdPartyMetadataFor(propertyName, buildInfo);
        if (thirdPartyMetadata != null) {
            return new PropertyDocumentation(
                    true,
                    null,
                    "Configuration property provided by " + thirdPartyMetadata.provider() + ".",
                    null,
                    thirdPartyMetadata.provider(),
                    false,
                    null,
                    List.of()
            );
        }

        CustomPropertyContext customPropertyContext = customPropertyDefinitions.get(propertyName);
        if (customPropertyContext != null) {
            String description = customPropertyContext.property().description();
            if (description == null || description.isBlank()) {
                description = "Custom property defined by " + customPropertyContext.sourceClass().className() + ".";
            }
            return new PropertyDocumentation(
                    true,
                    customPropertyContext.property().type(),
                    description,
                    null,
                    customPropertyContext.sourceClass().className(),
                    false,
                    null,
                    List.of()
            );
        }

        return PropertyDocumentation.unknown();
    }

    private PropertyKind determinePropertyKind(
            String propertyName,
            PropertyDocumentation documentation,
            Map<String, CustomPropertyContext> customPropertyDefinitions,
            List<PropertyReference> references,
            BuildInfo buildInfo
    ) {
        if (mapMetadataFor(propertyName) != null) {
            return PropertyKind.SPRING_BOOT_MAP_PROPERTY;
        }
        if (customPropertyDefinitions.containsKey(propertyName)) {
            return PropertyKind.CUSTOM_CONFIGURATION_PROPERTIES;
        }
        if (documentation.known() && isThirdPartySource(documentation.sourceType(), buildInfo)) {
            return PropertyKind.THIRD_PARTY;
        }
        if (documentation.known()) {
            return PropertyKind.SPRING_BOOT;
        }
        if (references.stream().anyMatch(reference -> "@ConditionalOnProperty".equals(reference.referenceType()))) {
            return PropertyKind.CONDITIONAL_PROPERTY;
        }
        if (!references.isEmpty()) {
            return PropertyKind.CODE_REFERENCED;
        }
        if (thirdPartyMetadataFor(propertyName, buildInfo) != null) {
            return PropertyKind.THIRD_PARTY;
        }
        return PropertyKind.UNKNOWN;
    }

    private PropertyKind classifyReferencedProperty(
            String propertyName,
            PropertyReference reference,
            PropertyDocumentation documentation,
            BuildInfo buildInfo
    ) {
        if ("@ConditionalOnProperty".equals(reference.referenceType())) {
            return PropertyKind.CONDITIONAL_PROPERTY;
        }
        if (documentation.known() && isThirdPartySource(documentation.sourceType(), buildInfo)) {
            return PropertyKind.THIRD_PARTY;
        }
        if (documentation.known() && mapMetadataFor(propertyName) != null) {
            return PropertyKind.SPRING_BOOT_MAP_PROPERTY;
        }
        if (documentation.known()) {
            return PropertyKind.SPRING_BOOT;
        }
        return PropertyKind.CODE_REFERENCED;
    }

    private List<PropertyReference> matchingReferences(String propertyName, List<PropertyReference> propertyReferences) {
        String normalizedName = propertyNameNormalizer.normalize(propertyName);
        return propertyReferences.stream()
                .filter(reference -> propertyNameNormalizer.normalize(reference.propertyName()).equals(normalizedName))
                .toList();
    }

    private Map<String, CustomPropertyContext> indexCustomPropertyDefinitions(
            List<ConfigurationPropertiesClass> configurationPropertiesClasses
    ) {
        Map<String, CustomPropertyContext> definitions = new LinkedHashMap<>();
        for (ConfigurationPropertiesClass configurationClass : configurationPropertiesClasses) {
            String prefix = propertyNameNormalizer.normalize(configurationClass.prefix());
            for (CustomPropertyDefinition property : configurationClass.properties()) {
                String propertyName = prefix.isBlank()
                        ? propertyNameNormalizer.normalize(property.propertyName())
                        : prefix + "." + propertyNameNormalizer.normalize(property.propertyName());
                definitions.put(propertyName, new CustomPropertyContext(configurationClass, property));
            }
        }
        return definitions;
    }

    private void addConfigurationFindings(
            List<ApplicationProperty> configuredProperties,
            Map<String, ParsedConfigurationProperty> rawConfiguredProperties,
            List<PropertyReference> referencedOnly,
            List<ConfigurationFile> files,
            List<ConfigurationPropertiesClass> configurationPropertiesClasses,
            List<Finding> findings
    ) {
        if (files.stream().anyMatch(file -> !"default".equals(file.profile()) && !"bootstrap".equals(file.profile()))) {
            findings.add(new Finding(
                    FindingSeverity.INFO,
                    "Profile-specific configuration files were found. Static analysis cannot determine which runtime profiles are active.",
                    null
            ));
        }

        long unknownCount = configuredProperties.stream().filter(property -> property.kind() == PropertyKind.UNKNOWN).count();
        if (unknownCount > 0) {
            findings.add(new Finding(
                    FindingSeverity.INFO,
                    unknownCount + " configured properties could not be matched to Spring Boot, discovered custom metadata, or known third-party metadata. See Configuration > Unknown.",
                    null
            ));
        }

        Set<String> deprecatedPropertyNames = new LinkedHashSet<>();
        Set<String> missingReferencedProperties = new LinkedHashSet<>();
        Set<String> orphanPrefixes = new LinkedHashSet<>();
        Set<String> customLookingOrphans = new LinkedHashSet<>();

        for (ApplicationProperty property : configuredProperties) {
            if (property.documentation().deprecated() && deprecatedPropertyNames.add(property.name())) {
                findings.add(new Finding(
                        FindingSeverity.WARNING,
                        "Deprecated configuration property is used: " + property.name(),
                        property.sourceFile()
                ));
            }

            addRiskFinding(property, rawConfiguredProperties, findings);

            if (property.kind() == PropertyKind.UNKNOWN
                    && !property.references().isEmpty()
                    && customLookingOrphans.add(property.name())) {
                findings.add(new Finding(
                        FindingSeverity.INFO,
                        "Configured property looks application-specific but has no matching @ConfigurationProperties metadata: " + property.name(),
                        property.sourceFile()
                ));
            }
        }

        for (PropertyReference reference : referencedOnly) {
            if (missingReferencedProperties.add(reference.propertyName())) {
                findings.add(new Finding(
                        FindingSeverity.WARNING,
                        "Property is referenced in code but not configured: " + reference.propertyName(),
                        reference.sourceFile()
                ));
            }
        }

        for (ConfigurationPropertiesClass configurationPropertiesClass : configurationPropertiesClasses) {
            String prefix = propertyNameNormalizer.normalize(configurationPropertiesClass.prefix());
            boolean matched = configuredProperties.stream().anyMatch(property ->
                    property.name().equals(prefix) || property.name().startsWith(prefix + "."));
            if (!matched && orphanPrefixes.add(prefix)) {
                findings.add(new Finding(
                        FindingSeverity.INFO,
                        "@ConfigurationProperties prefix was found but no matching configured properties were detected: " + prefix,
                        configurationPropertiesClass.sourceFile()
                ));
            }
        }
    }

    private void addRiskFinding(
            ApplicationProperty property,
            Map<String, ParsedConfigurationProperty> rawConfiguredProperties,
            List<Finding> findings
    ) {
        String name = property.name();
        String rawValue = findRawValue(property, rawConfiguredProperties);
        String profile = property.profile() == null ? "default" : property.profile();

        if ("prod".equalsIgnoreCase(profile)
                && "spring.jpa.hibernate.ddl-auto".equals(name)
                && rawValue != null
                && List.of("update", "create", "create-drop").contains(rawValue.toLowerCase(Locale.ROOT))) {
            findings.add(new Finding(
                    FindingSeverity.WARNING,
                    "spring.jpa.hibernate.ddl-auto=" + rawValue + " is risky in production.",
                    property.sourceFile()
            ));
        }

        if ("management.endpoints.web.exposure.include".equals(name) && "*".equals(rawValue)) {
            findings.add(new Finding(
                    FindingSeverity.WARNING,
                    "management.endpoints.web.exposure.include=* exposes every actuator endpoint.",
                    property.sourceFile()
            ));
        }

        if ("management.endpoint.health.show-details".equals(name) && "always".equalsIgnoreCase(rawValue)) {
            findings.add(new Finding(
                    FindingSeverity.INFO,
                    "management.endpoint.health.show-details=always reveals detailed health information.",
                    property.sourceFile()
            ));
        }

        if (property.valueRedacted() && rawValue != null && !isPlaceholder(rawValue)) {
            findings.add(new Finding(
                    FindingSeverity.WARNING,
                    "Sensitive configuration property appears to use a literal value in a config file: " + name,
                    property.sourceFile()
            ));
        }
    }

    private String findRawValue(ApplicationProperty property, Map<String, ParsedConfigurationProperty> rawConfiguredProperties) {
        String key = property.name() + "@" + property.sourceFile() + "@" + property.profile();
        ParsedConfigurationProperty parsedProperty = rawConfiguredProperties.get(key);
        return parsedProperty == null ? null : parsedProperty.value();
    }

    private boolean isPlaceholder(String value) {
        return value != null && value.trim().startsWith("${") && value.trim().endsWith("}");
    }

    private ConfigurationSummary buildSummary(List<ApplicationProperty> configuredProperties, List<PropertyReference> references) {
        int knownSpringBootCount = 0;
        int customCount = 0;
        int unknownCount = 0;
        int sensitiveCount = 0;
        Set<String> profiles = new LinkedHashSet<>();

        for (ApplicationProperty property : configuredProperties) {
            switch (property.kind()) {
                case SPRING_BOOT, SPRING_BOOT_MAP_PROPERTY -> knownSpringBootCount++;
                case CUSTOM_CONFIGURATION_PROPERTIES -> customCount++;
                case UNKNOWN -> unknownCount++;
                default -> {
                }
            }
            if (property.valueRedacted()) {
                sensitiveCount++;
            }
            if (property.profile() != null && !property.profile().isBlank()) {
                profiles.add(property.profile());
            }
        }

        return new ConfigurationSummary(
                configuredProperties.size(),
                knownSpringBootCount,
                customCount,
                unknownCount,
                references.size(),
                sensitiveCount,
                List.copyOf(profiles)
        );
    }

    private String formatDisplayValue(String rawValue) {
        if (rawValue == null) {
            return null;
        }
        try {
            Duration duration = Duration.parse(rawValue);
            long seconds = duration.getSeconds();
            if (seconds % 3600 == 0) {
                return (seconds / 3600) + " hours";
            }
            if (seconds % 60 == 0) {
                return (seconds / 60) + " minutes";
            }
            return seconds + " seconds";
        } catch (Exception ignored) {
            return rawValue;
        }
    }

    private MapPrefixMetadata mapMetadataFor(String propertyName) {
        return SPRING_BOOT_MAP_PREFIXES.stream()
                .filter(metadata -> propertyName.startsWith(metadata.prefix()))
                .findFirst()
                .orElse(null);
    }

    private ThirdPartyPrefixMetadata thirdPartyMetadataFor(String propertyName, BuildInfo buildInfo) {
        return THIRD_PARTY_PREFIXES.stream()
                .filter(metadata -> propertyName.startsWith(metadata.prefix()))
                .filter(metadata -> dependencyPresent(buildInfo, metadata.providerDependencyMarker()))
                .findFirst()
                .orElse(null);
    }

    private boolean isThirdPartySource(String sourceType, BuildInfo buildInfo) {
        if (sourceType == null) {
            return false;
        }
        return THIRD_PARTY_PREFIXES.stream()
                .anyMatch(metadata -> sourceType.equalsIgnoreCase(metadata.provider())
                        && dependencyPresent(buildInfo, metadata.providerDependencyMarker()));
    }

    private boolean dependencyPresent(BuildInfo buildInfo, String marker) {
        return buildInfo.dependencies().stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.contains(marker.toLowerCase(Locale.ROOT)));
    }

    private record CustomPropertyContext(
            ConfigurationPropertiesClass sourceClass,
            CustomPropertyDefinition property
    ) {
    }

    private record MapPrefixMetadata(
            String prefix,
            String sourceType,
            String description,
            String type
    ) {
    }

    private record ThirdPartyPrefixMetadata(
            String prefix,
            String providerDependencyMarker
    ) {
        String provider() {
            return providerDependencyMarker;
        }
    }

    public record Result(
            ConfigurationAnalysis configurationAnalysis,
            List<Finding> findings
    ) {
    }
}
