package com.example.springbootanalyzer.analyzer.configuration;

import com.example.springbootanalyzer.analyzer.model.BuildInfo;
import com.example.springbootanalyzer.analyzer.model.Finding;
import com.example.springbootanalyzer.analyzer.model.FindingCategory;
import com.example.springbootanalyzer.analyzer.model.FindingConfidence;
import com.example.springbootanalyzer.analyzer.model.FindingFactory;
import com.example.springbootanalyzer.analyzer.model.FindingRules;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ConfigurationAnalyzer {
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("^\\$\\{([^}:]+)(?::([^}]*))?}$");

    private static final List<MapPrefixMetadata> SPRING_BOOT_MAP_PREFIXES = List.of(
            new MapPrefixMetadata(
                    "logging.level.",
                    "org.springframework.boot.logging.LogLevel",
                    "Logger level for the named logger configured through logging.level.*",
                    "java.lang.String"
            ),
            new MapPrefixMetadata(
                    "spring.mail.properties.",
                    "org.springframework.boot.autoconfigure.mail.MailProperties",
                    "JavaMail session property passed through via spring.mail.properties.*",
                    "java.lang.String"
            ),
            new MapPrefixMetadata(
                    "management.endpoint.",
                    "org.springframework.boot.actuate.autoconfigure.endpoint",
                    "Actuator endpoint-specific configuration provided through management.endpoint.*.*",
                    "java.lang.String"
            ),
            new MapPrefixMetadata(
                    "management.endpoints.",
                    "org.springframework.boot.actuate.autoconfigure.endpoint",
                    "Actuator endpoint group or exposure configuration provided through management.endpoints.*.*",
                    "java.lang.String"
            ),
            new MapPrefixMetadata(
                    "spring.datasource.hikari.",
                    "com.zaxxer.hikari.HikariConfig",
                    "HikariCP pool property passed through via spring.datasource.hikari.*",
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
        List<PropertyReference> propertyReferences = propertyReferenceAnalyzer.analyze(repositoryRoot).stream()
                .filter(reference -> !isIgnoredSystemPropertyReference(reference))
                .toList();

        Map<String, CustomPropertyContext> customPropertyDefinitions = indexCustomPropertyDefinitions(customConfigurationClasses);
        Map<String, ConfigurationPropertiesClass> customPropertyPrefixes = indexCustomPropertyPrefixes(customConfigurationClasses);
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
                        customPropertyPrefixes,
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

            PropertyDocumentation documentation = documentationFor(
                    normalizedName,
                    metadataCatalog,
                    customPropertyDefinitions,
                    customPropertyPrefixes,
                    buildInfo
            );
            allProperties.add(new ApplicationProperty(
                    reference.propertyName(),
                    null,
                    false,
                    false,
                    reference.sourceFile(),
                    null,
                    null,
                    classifyReferencedProperty(
                            normalizedName,
                            reference,
                            documentation,
                            buildInfo,
                            metadataCatalog,
                            customPropertyDefinitions,
                            customPropertyPrefixes
                    ),
                    documentation,
                    matchingReferences(reference.propertyName(), propertyReferences)
            ));
        }

        for (Map.Entry<String, CustomPropertyContext> entry : customPropertyDefinitions.entrySet()) {
            String propertyName = entry.getKey();
            if (allProperties.stream().anyMatch(property -> propertyNameNormalizer.normalize(property.name()).equals(propertyName))) {
                continue;
            }

            PropertyDocumentation documentation = documentationFor(
                    propertyName,
                    metadataCatalog,
                    customPropertyDefinitions,
                    customPropertyPrefixes,
                    buildInfo
            );
            allProperties.add(new ApplicationProperty(
                    propertyName,
                    null,
                    false,
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
            Map<String, ConfigurationPropertiesClass> customPropertyPrefixes,
            List<PropertyReference> propertyReferences,
            BuildInfo buildInfo
    ) {
        String normalizedName = propertyNameNormalizer.normalize(parsedProperty.name());
        PropertyDocumentation documentation = documentationFor(
                normalizedName,
                metadataCatalog,
                customPropertyDefinitions,
                customPropertyPrefixes,
                buildInfo
        );
        List<PropertyReference> references = matchingReferences(normalizedName, propertyReferences);
        PropertyKind propertyKind = determinePropertyKind(
                normalizedName,
                documentation,
                customPropertyDefinitions,
                customPropertyPrefixes,
                references,
                buildInfo,
                metadataCatalog
        );
        boolean sensitive = redactor.isSensitive(normalizedName);

        return new ApplicationProperty(
                normalizedName,
                sensitive ? redactor.redact(parsedProperty.value()) : formatDisplayValue(parsedProperty.value()),
                sensitive,
                isPlaceholder(parsedProperty.value()),
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
            Map<String, ConfigurationPropertiesClass> customPropertyPrefixes,
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

        CustomPropertyContext customPropertyContext = findCustomPropertyContext(propertyName, customPropertyDefinitions);
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

        ConfigurationPropertiesClass owningPrefix = findCustomPropertyPrefixOwner(propertyName, customPropertyPrefixes);
        if (owningPrefix != null) {
            return new PropertyDocumentation(
                    true,
                    null,
                    "Custom property under @" + "ConfigurationProperties prefix " + owningPrefix.prefix() + ".",
                    null,
                    owningPrefix.className(),
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
            Map<String, ConfigurationPropertiesClass> customPropertyPrefixes,
            List<PropertyReference> references,
            BuildInfo buildInfo,
            SpringConfigurationMetadataCatalog.MetadataCatalog metadataCatalog
    ) {
        if (findCustomPropertyContext(propertyName, customPropertyDefinitions) != null) {
            return PropertyKind.CUSTOM_CONFIGURATION_PROPERTIES;
        }
        if (findCustomPropertyPrefixOwner(propertyName, customPropertyPrefixes) != null) {
            return PropertyKind.CUSTOM_CONFIGURATION_PROPERTIES;
        }
        if (metadataCatalog.find(propertyName) != null) {
            if (documentation.known() && isThirdPartySource(documentation.sourceType(), buildInfo)) {
                return PropertyKind.THIRD_PARTY;
            }
            return PropertyKind.SPRING_BOOT;
        }
        if (mapMetadataFor(propertyName) != null) {
            return PropertyKind.SPRING_BOOT_MAP_PROPERTY;
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
            BuildInfo buildInfo,
            SpringConfigurationMetadataCatalog.MetadataCatalog metadataCatalog,
            Map<String, CustomPropertyContext> customPropertyDefinitions,
            Map<String, ConfigurationPropertiesClass> customPropertyPrefixes
    ) {
        if ("@ConditionalOnProperty".equals(reference.referenceType())) {
            return PropertyKind.CONDITIONAL_PROPERTY;
        }
        if (findCustomPropertyContext(propertyName, customPropertyDefinitions) != null
                || findCustomPropertyPrefixOwner(propertyName, customPropertyPrefixes) != null) {
            return PropertyKind.CUSTOM_CONFIGURATION_PROPERTIES;
        }
        if (metadataCatalog.find(propertyName) != null) {
            if (documentation.known() && isThirdPartySource(documentation.sourceType(), buildInfo)) {
                return PropertyKind.THIRD_PARTY;
            }
            return PropertyKind.SPRING_BOOT;
        }
        if (mapMetadataFor(propertyName) != null) {
            return PropertyKind.SPRING_BOOT_MAP_PROPERTY;
        }
        if (documentation.known() && isThirdPartySource(documentation.sourceType(), buildInfo)) {
            return PropertyKind.THIRD_PARTY;
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

    private Map<String, ConfigurationPropertiesClass> indexCustomPropertyPrefixes(
            List<ConfigurationPropertiesClass> configurationPropertiesClasses
    ) {
        Map<String, ConfigurationPropertiesClass> prefixes = new LinkedHashMap<>();
        for (ConfigurationPropertiesClass configurationClass : configurationPropertiesClasses) {
            String normalizedPrefix = propertyNameNormalizer.normalize(configurationClass.prefix());
            if (!normalizedPrefix.isBlank()) {
                prefixes.put(normalizedPrefix, configurationClass);
            }
        }
        return prefixes;
    }

    private CustomPropertyContext findCustomPropertyContext(
            String propertyName,
            Map<String, CustomPropertyContext> customPropertyDefinitions
    ) {
        CustomPropertyContext exact = customPropertyDefinitions.get(propertyName);
        if (exact != null) {
            return exact;
        }

        CustomPropertyContext bestMatch = null;
        int bestMatchLength = -1;
        for (Map.Entry<String, CustomPropertyContext> entry : customPropertyDefinitions.entrySet()) {
            String definitionName = entry.getKey();
            if (!matchesCustomPropertyPath(propertyName, definitionName, entry.getValue().property().type())) {
                continue;
            }
            if (definitionName.length() > bestMatchLength) {
                bestMatch = entry.getValue();
                bestMatchLength = definitionName.length();
            }
        }
        return bestMatch;
    }

    private boolean matchesCustomPropertyPath(String propertyName, String definitionName, String declaredType) {
        if (propertyName == null || definitionName == null || propertyName.equals(definitionName)) {
            return false;
        }
        if (propertyName.startsWith(definitionName + ".")) {
            return true;
        }
        return isMapLikeType(declaredType)
                && (propertyName.startsWith(definitionName + "[") || propertyName.startsWith(definitionName + "."));
    }

    private boolean isMapLikeType(String declaredType) {
        if (declaredType == null || declaredType.isBlank()) {
            return false;
        }
        String normalized = declaredType.toLowerCase(Locale.ROOT);
        return normalized.equals("map")
                || normalized.endsWith(".map")
                || normalized.contains("map<");
    }

    private ConfigurationPropertiesClass findCustomPropertyPrefixOwner(
            String propertyName,
            Map<String, ConfigurationPropertiesClass> customPropertyPrefixes
    ) {
        ConfigurationPropertiesClass bestMatch = null;
        int bestLength = -1;
        for (Map.Entry<String, ConfigurationPropertiesClass> entry : customPropertyPrefixes.entrySet()) {
            String prefix = entry.getKey();
            if (!propertyName.startsWith(prefix + ".")) {
                continue;
            }
            if (prefix.length() > bestLength) {
                bestMatch = entry.getValue();
                bestLength = prefix.length();
            }
        }
        return bestMatch;
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
            findings.add(FindingFactory.builder(
                            "SPRING_PROFILE_SPECIFIC_CONFIG",
                            "Profile-specific configuration files were found",
                            FindingSeverity.INFO,
                            FindingCategory.PROFILE_DRIFT,
                            com.example.springbootanalyzer.analyzer.model.FindingRuntimeDetection.ACTIVE_PROFILE_RUNTIME_MAY_DETECT,
                            FindingConfidence.MEDIUM
                    )
                    .shortMessage("Profile-specific configuration files were found. Static analysis cannot determine which runtime profiles are active.")
                    .whyBadPractice("Spring resolves only the active profile at runtime. Static configuration drift can stay invisible until a different environment activates a different file set.")
                    .possibleImpact("Different profiles can silently change dependencies, security settings, scheduler behavior, or external service targets between environments.")
                    .recommendation("Review default and profile-specific files together, keep critical operational settings explicit per environment, and add tests for important profile combinations.")
                    .evidence("Configuration files such as application-prod.* or application-dev.* were detected.")
                    .limitations("Static analysis cannot prove which profiles are active in production or how deployment systems inject additional properties.")
                    .target("profiles")
                    .location("Configuration")
                    .build());
        }

        long unknownCount = configuredProperties.stream().filter(property -> property.kind() == PropertyKind.UNKNOWN).count();
        if (unknownCount > 0) {
            List<String> unknownExamples = configuredProperties.stream()
                    .filter(property -> property.kind() == PropertyKind.UNKNOWN)
                    .map(property -> property.name() + " (" + property.sourceFile() + ")")
                    .distinct()
                    .limit(10)
                    .toList();
            findings.add(FindingFactory.builder(
                            "CONFIG_UNKNOWN_PROPERTY",
                            "Unknown configuration properties detected",
                            FindingSeverity.INFO,
                            FindingCategory.CONFIGURATION,
                            com.example.springbootanalyzer.analyzer.model.FindingRuntimeDetection.NOT_NORMALLY_DETECTED,
                            FindingConfidence.MEDIUM
                    )
                    .shortMessage(unknownCount + " configured properties could not be matched to Spring Boot, discovered custom metadata, or known third-party metadata. See Configuration > Unknown.")
                    .whyBadPractice("Unknown properties are easy to miss because Spring often ignores typos and unsupported keys without turning them into startup failures.")
                    .possibleImpact("A misspelled or outdated property can leave important behavior running with defaults in one environment while operators assume the setting is active.")
                    .recommendation("Review unknown properties, confirm whether they belong to Spring Boot, a known third-party library, or a custom @ConfigurationProperties prefix, and remove or rename stale keys.")
                    .evidence("Unknown properties included: " + String.join(", ", unknownExamples) + ".")
                    .limitations("Static analysis cannot always prove whether a property is consumed indirectly by reflection, generated metadata, or runtime-only libraries that were not visible in the scanned sources.")
                    .target("unknown configuration properties")
                    .location("Configuration")
                    .build());
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
                findings.add(FindingFactory.builder(
                                "CONFIG_CODE_REFERENCE_MISSING",
                                "Referenced property is not configured",
                                FindingSeverity.WARNING,
                                FindingCategory.CONFIGURATION,
                                com.example.springbootanalyzer.analyzer.model.FindingRuntimeDetection.NOT_NORMALLY_DETECTED,
                                FindingConfidence.MEDIUM
                        )
                        .shortMessage("Property is referenced in code but no matching configured property was found in scanned files: " + reference.propertyName())
                        .whyBadPractice("A property that is only referenced in code can silently fall back to defaults, null-like behavior, or environment-only wiring that is hard to see in code review.")
                        .possibleImpact("Behavior may differ between local development, CI, and production depending on environment variables, deployment secrets, or missing profile files.")
                        .recommendation("Either configure the property explicitly, document that it must come from the environment, or remove the unused reference if it is stale.")
                        .evidence(reference.referenceType() + " references " + reference.propertyName() + " in " + reference.sourceFile() + ".")
                        .limitations("Static analysis cannot see higher-precedence environment variables, deployment platform secrets, or late-bound property sources that may satisfy the reference at runtime.")
                        .source(reference.sourceFile(), null)
                        .target(reference.propertyName())
                        .build());
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
                && List.of("update", "create", "create-drop", "drop").contains(rawValue.toLowerCase(Locale.ROOT))) {
            findings.add(FindingFactory.builder(FindingRules.SPRING_RISKY_PROD_CONFIG, FindingConfidence.HIGH)
                    .shortMessage("spring.jpa.hibernate.ddl-auto=" + rawValue + " is risky in production.")
                    .whyBadPractice("Schema-changing Hibernate DDL settings trade safety for convenience. They make database structure changes happen implicitly during application startup instead of through reviewed migrations.")
                    .possibleImpact("Production schema can drift unexpectedly, destructive changes can happen during rollout, and failures become harder to reproduce across environments.")
                    .recommendation("Use Flyway or Liquibase for reviewed migrations and keep production ddl-auto at validate or none.")
                    .evidence(name + " was set to " + rawValue + " in " + property.sourceFile() + ".")
                    .limitations("Static analysis cannot prove whether this file is always active, but the profile and filename strongly suggest a production-oriented configuration.")
                    .source(property.sourceFile(), property.line())
                    .target(name)
                    .build());
        }

        if ("management.endpoints.web.exposure.include".equals(name) && "*".equals(rawValue)) {
            findings.add(FindingFactory.builder(FindingRules.SPRING_RISKY_PROD_CONFIG, FindingConfidence.HIGH)
                    .shortMessage("management.endpoints.web.exposure.include=* exposes every actuator endpoint.")
                    .whyBadPractice("Wildcard actuator exposure makes internal diagnostics easier to reach than intended and mixes operational endpoints into the normal HTTP surface.")
                    .possibleImpact("Operational details, environment information, heap dumps, and debugging endpoints may become reachable in environments where they should stay restricted.")
                    .recommendation("Expose only the actuator endpoints you intentionally operate, and keep broader exposure limited to tightly controlled environments.")
                    .evidence(name + "=* was found in " + property.sourceFile() + ".")
                    .limitations("Static analysis cannot prove the final network exposure or security policy applied at runtime.")
                    .source(property.sourceFile(), property.line())
                    .target(name)
                    .build());
        }

        if ("management.endpoint.health.show-details".equals(name) && "always".equalsIgnoreCase(rawValue)) {
            findings.add(FindingFactory.builder(FindingRules.SPRING_RISKY_PROD_CONFIG, FindingConfidence.HIGH)
                    .shortMessage("management.endpoint.health.show-details=always reveals detailed health information.")
                    .whyBadPractice("Detailed health output is useful operationally, but it increases the amount of internal state exposed through health responses.")
                    .possibleImpact("Health responses may disclose component details, failing dependencies, or other internal signals that are better kept behind authenticated operations tooling.")
                    .recommendation("Prefer when-authorized or a narrower exposure model outside development and local troubleshooting.")
                    .evidence(name + "=always was found in " + property.sourceFile() + ".")
                    .limitations("Static analysis cannot see who can reach the endpoint or whether response details are filtered elsewhere.")
                    .source(property.sourceFile(), property.line())
                    .target(name)
                    .build());
        }

        SecretFallback secretFallback = secretFallback(rawValue);
        if (property.valueRedacted() && secretFallback.weakDefault()) {
            findings.add(FindingFactory.builder(FindingRules.SPRING_SECRET_WEAK_PLACEHOLDER_DEFAULT, FindingConfidence.HIGH)
                    .shortMessage("Sensitive configuration property has a weak placeholder default: " + name)
                    .whyBadPractice("A secret placeholder with a weak default can silently fall back to an insecure value when the environment variable is missing.")
                    .possibleImpact("A deployment with missing secret injection may start successfully with a known password or weak credential.")
                    .recommendation("Remove the default value for secrets or fail fast when the environment variable is absent.")
                    .evidence(name + " uses placeholder " + rawValue + " in " + property.sourceFile() + ".")
                    .limitations("Static analysis cannot prove whether deployment tooling always injects the expected secret before startup.")
                    .source(property.sourceFile(), property.line())
                    .target(name)
                    .build());
            return;
        }

        if (property.valueRedacted() && (secretFallback.literalDefault() || hasDirectSensitiveLiteral(rawValue))) {
            findings.add(FindingFactory.builder(FindingRules.SPRING_SECRET_LITERAL, FindingConfidence.HIGH)
                    .shortMessage("Sensitive configuration property appears to use a literal value in a config file: " + name)
                    .whyBadPractice("Secrets stored directly in static configuration are hard to rotate, easy to copy, and may leak through repository access, logs, screenshots, CI artifacts, or backups.")
                    .possibleImpact("A committed password, token, or client secret may grant unintended access to production systems or third-party services, even after the value is removed later.")
                    .recommendation("Use environment variables, a secret manager, or deployment platform secret references such as ${DB_PASSWORD}. Rotate any real value that may already have been committed.")
                    .evidence(name + " was found in " + property.sourceFile() + " with a non-placeholder value.")
                    .limitations("Static analysis cannot prove whether the value is real, already rotated, or only used in a private environment.")
                    .source(property.sourceFile(), property.line())
                    .target(name)
                    .build());
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

    private boolean hasDirectSensitiveLiteral(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return false;
        }
        String trimmed = rawValue.trim();
        return !isPlaceholder(trimmed);
    }

    private boolean isIgnoredSystemPropertyReference(PropertyReference reference) {
        if (reference == null || reference.propertyName() == null || reference.propertyName().isBlank()) {
            return false;
        }
        if ("@Value".equals(reference.referenceType())) {
            return false;
        }
        String propertyName = reference.propertyName();
        return propertyName.startsWith("java.")
                || propertyName.startsWith("os.")
                || propertyName.startsWith("user.")
                || propertyName.startsWith("sun.")
                || propertyName.startsWith("file.")
                || propertyName.startsWith("awt.")
                || propertyName.startsWith("jdk.")
                || propertyName.startsWith("org.gradle.")
                || propertyName.startsWith("systemprop.")
                || propertyName.equals("distributionurl")
                || propertyName.equals("distribution-url")
                || propertyName.equals("path.separator")
                || propertyName.equals("line.separator")
                || propertyName.equals("native.encoding");
    }

    private SecretFallback secretFallback(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return SecretFallback.none();
        }
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(rawValue.trim());
        if (!matcher.matches()) {
            return SecretFallback.none();
        }
        String fallback = matcher.group(2);
        if (fallback == null) {
            return SecretFallback.none();
        }
        String trimmedFallback = fallback.trim();
        if (trimmedFallback.isBlank()) {
            return SecretFallback.none();
        }
        return new SecretFallback(trimmedFallback, isWeakSecretDefault(trimmedFallback), true);
    }

    private boolean isWeakSecretDefault(String fallback) {
        String normalized = fallback.toLowerCase(Locale.ROOT);
        return Set.of("admin", "password", "passwd", "changeme", "changeit", "secret", "default", "root", "token", "key")
                .contains(normalized);
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

    private record SecretFallback(
            String value,
            boolean weakDefault,
            boolean literalDefault
    ) {
        static SecretFallback none() {
            return new SecretFallback(null, false, false);
        }
    }

    public record Result(
            ConfigurationAnalysis configurationAnalysis,
            List<Finding> findings
    ) {
    }
}
