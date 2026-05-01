package com.robbanhoglund.springbootanalyzer.analyzer.gradle.plugin;

import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingSeverity;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradlePluginBridgeFailure;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradlePluginDeclaration;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.GradlePluginResolutionBridgeResult;
import com.robbanhoglund.springbootanalyzer.analyzer.model.gradle.ResolvedGradlePlugin;
import com.robbanhoglund.springbootanalyzer.config.AnalyzerProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

@Component
public class GradlePluginResolutionBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(GradlePluginResolutionBridge.class);
    private static final Pattern PROPERTY_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    private final GradleCorePluginDetector corePluginDetector;
    private final ArtifactTransport artifactTransport;

    @Autowired
    public GradlePluginResolutionBridge(GradleCorePluginDetector corePluginDetector) {
        this(corePluginDetector, new HttpArtifactTransport());
    }

    public GradlePluginResolutionBridge(
            GradleCorePluginDetector corePluginDetector,
            ArtifactTransport artifactTransport
    ) {
        this.corePluginDetector = corePluginDetector;
        this.artifactTransport = artifactTransport;
    }

    public GradlePluginResolutionBridgeResult prefetch(
            Path repositoryRoot,
            List<GradlePluginDeclaration> declarations,
            AnalyzerProperties.GradleProperties properties
    ) {
        if (properties == null
                || properties.pluginResolutionBridge() == null
                || !properties.pluginResolutionBridge().enabled()
                || declarations.isEmpty()) {
            return GradlePluginResolutionBridgeResult.empty();
        }

        AnalyzerProperties.PluginResolutionBridgeProperties bridgeProperties = properties.pluginResolutionBridge();
        Path localRepository = repositoryRoot.getParent().resolve("gradle-plugin-cache").resolve("m2");
        try {
            Files.createDirectories(localRepository);
        } catch (IOException exception) {
            return new GradlePluginResolutionBridgeResult(
                    false,
                    localRepository.toString(),
                    List.of(),
                    List.of(new GradlePluginBridgeFailure(
                            null,
                            null,
                            null,
                            null,
                            null,
                            "LOCAL_REPOSITORY_ERROR",
                            exception.getMessage(),
                            false,
                            false,
                            null
                    )),
                    List.of(new Finding(FindingSeverity.WARNING, "Gradle plugin bridge could not create its local repository.", null))
            );
        }

        List<String> repositories = pluginRepositories(properties);
        BridgeNetworkSettings networkSettings = BridgeNetworkSettings.from(properties);
        ResolutionContext context = new ResolutionContext(localRepository, repositories, bridgeProperties, networkSettings);
        List<ResolvedGradlePlugin> resolvedPlugins = new ArrayList<>();
        List<GradlePluginBridgeFailure> failures = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();

        for (GradlePluginDeclaration declaration : declarations.stream().limit(bridgeProperties.maxPlugins()).toList()) {
            if (declaration.pluginId() == null
                    || declaration.pluginId().isBlank()
                    || declaration.version() == null
                    || declaration.version().isBlank()
                    || corePluginDetector.isCorePlugin(declaration.pluginId())) {
                continue;
            }
            String markerCoordinates = markerCoordinates(declaration.pluginId(), declaration.version());
            try {
                EffectivePom markerPom = resolvePom(parseCoordinates(markerCoordinates), context);
                Dependency implementationDependency = markerPom.dependencies().stream()
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("Marker POM did not declare an implementation dependency."));
                ArtifactCoordinates implementationCoordinates = implementationDependency.toCoordinates();
                int beforeArtifacts = context.resolvedArtifacts.size();
                resolveArtifactTree(implementationCoordinates, context, new HashSet<>());
                int transitiveCount = Math.max(0, context.resolvedArtifacts.size() - beforeArtifacts);
                resolvedPlugins.add(new ResolvedGradlePlugin(
                        declaration.pluginId(),
                        declaration.version(),
                        markerCoordinates,
                        implementationCoordinates.toCoordinateString(),
                        declaration.sourceFile(),
                        declaration.line(),
                        Files.exists(context.localPathFor(parseCoordinates(markerCoordinates), "pom")),
                        artifactExistsLocally(implementationCoordinates, context),
                        transitiveCount
                ));
                LOGGER.info(
                        "Gradle plugin bridge resolved: {}:{} marker={} implementation={} localRepository={}",
                        declaration.pluginId(),
                        declaration.version(),
                        markerCoordinates,
                        implementationCoordinates.toCoordinateString(),
                        localRepository
                );
            } catch (Exception exception) {
                String message = redact(exception.getMessage());
                GradlePluginBridgeFailure failure = new GradlePluginBridgeFailure(
                        declaration.pluginId(),
                        declaration.version(),
                        markerCoordinates,
                        declaration.sourceFile(),
                        declaration.line(),
                        "PLUGIN_BRIDGE_RESOLUTION_FAILED",
                        message,
                        Files.exists(context.localPathFor(parseCoordinates(markerCoordinates), "pom")),
                        false,
                        null
                );
                failures.add(failure);
                findings.add(new Finding(
                        FindingSeverity.WARNING,
                        "Gradle plugin resolution bridge could not resolve plugin %s:%s. Gradle model analysis is partial."
                                .formatted(declaration.pluginId(), declaration.version()),
                        sourceLocation(declaration.sourceFile(), declaration.line())
                ));
            }
        }

        return new GradlePluginResolutionBridgeResult(
                failures.isEmpty(),
                localRepository.toString(),
                List.copyOf(resolvedPlugins),
                List.copyOf(failures),
                List.copyOf(findings)
        );
    }

    public String markerCoordinates(String pluginId, String version) {
        return pluginId + ":" + pluginId + ".gradle.plugin:" + version;
    }

    private boolean artifactExistsLocally(ArtifactCoordinates coordinates, ResolutionContext context) {
        if (Files.exists(context.localPathFor(coordinates, "jar"))) {
            return true;
        }
        return Files.exists(context.localPathFor(coordinates, "pom"));
    }

    private void resolveArtifactTree(
            ArtifactCoordinates coordinates,
            ResolutionContext context,
            Set<String> visiting
    ) throws Exception {
        if (!visiting.add(coordinates.toCoordinateString())) {
            return;
        }
        EffectivePom pom = resolvePom(coordinates, context);
        if (!"pom".equalsIgnoreCase(pom.packaging())) {
            downloadArtifact(coordinates, "jar", context);
            context.resolvedArtifacts.add(coordinates.toCoordinateString() + "@jar");
        }
        for (Dependency dependency : pom.dependencies()) {
            if (dependency.optional() || dependency.version() == null || dependency.version().isBlank()) {
                continue;
            }
            if (dependency.scope() != null && List.of("test", "provided", "system").contains(dependency.scope())) {
                continue;
            }
            resolveArtifactTree(dependency.toCoordinates(), context, visiting);
        }
    }

    private EffectivePom resolvePom(ArtifactCoordinates coordinates, ResolutionContext context) throws Exception {
        EffectivePom cached = context.effectivePoms.get(coordinates.toCoordinateString());
        if (cached != null) {
            return cached;
        }

        downloadArtifact(coordinates, "pom", context);
        Path pomFile = context.localPathFor(coordinates, "pom");
        RawPomModel rawPom = parsePom(pomFile);

        String groupId = rawPom.groupId() != null ? rawPom.groupId() : coordinates.groupId();
        String version = rawPom.version() != null ? rawPom.version() : coordinates.version();
        Map<String, String> properties = new LinkedHashMap<>();
        Map<String, Dependency> dependencyManagement = new LinkedHashMap<>();

        if (rawPom.parent() != null) {
            ArtifactCoordinates parentCoordinates = rawPom.parent().toCoordinates(groupId, version);
            EffectivePom parent = resolvePom(parentCoordinates, context);
            properties.putAll(parent.properties());
            dependencyManagement.putAll(parent.dependencyManagement());
            if (groupId == null) {
                groupId = parent.groupId();
            }
            if (version == null) {
                version = parent.version();
            }
        }

        properties.put("project.groupId", groupId);
        properties.put("project.artifactId", rawPom.artifactId());
        properties.put("project.version", version);
        properties.put("pom.groupId", groupId);
        properties.put("pom.artifactId", rawPom.artifactId());
        properties.put("pom.version", version);
        rawPom.properties().forEach((key, value) -> properties.put(key, substitute(value, properties)));

        for (Dependency dependency : rawPom.dependencyManagement()) {
            Dependency resolved = resolveDependency(dependency, properties, dependencyManagement);
            if ("import".equalsIgnoreCase(resolved.scope()) && "pom".equalsIgnoreCase(resolved.type())) {
                EffectivePom importedBom = resolvePom(resolved.toCoordinates(), context);
                dependencyManagement.putAll(importedBom.dependencyManagement());
            } else {
                dependencyManagement.put(key(resolved.groupId(), resolved.artifactId()), resolved);
            }
        }

        List<Dependency> dependencies = new ArrayList<>();
        for (Dependency dependency : rawPom.dependencies()) {
            dependencies.add(resolveDependency(dependency, properties, dependencyManagement));
        }

        EffectivePom effectivePom = new EffectivePom(
                groupId,
                rawPom.artifactId(),
                version,
                rawPom.packaging() == null ? "jar" : rawPom.packaging(),
                Map.copyOf(properties),
                Map.copyOf(dependencyManagement),
                List.copyOf(dependencies)
        );
        context.effectivePoms.put(coordinates.toCoordinateString(), effectivePom);
        return effectivePom;
    }

    private Dependency resolveDependency(
            Dependency dependency,
            Map<String, String> properties,
            Map<String, Dependency> dependencyManagement
    ) {
        String groupId = substitute(dependency.groupId(), properties);
        String artifactId = substitute(dependency.artifactId(), properties);
        String version = substitute(dependency.version(), properties);
        String type = substitute(dependency.type(), properties);
        String scope = substitute(dependency.scope(), properties);
        if ((version == null || version.isBlank()) && groupId != null && artifactId != null) {
            Dependency managed = dependencyManagement.get(key(groupId, artifactId));
            if (managed != null) {
                version = managed.version();
                if (type == null || type.isBlank()) {
                    type = managed.type();
                }
                if (scope == null || scope.isBlank()) {
                    scope = managed.scope();
                }
            }
        }
        return new Dependency(groupId, artifactId, version, scope, type == null || type.isBlank() ? "jar" : type, dependency.optional());
    }

    private void downloadArtifact(ArtifactCoordinates coordinates, String extension, ResolutionContext context) throws Exception {
        Path target = context.localPathFor(coordinates, extension);
        if (!context.bridgeProperties.redownload() && Files.exists(target)) {
            return;
        }
        if (context.resolvedArtifacts.size() >= context.bridgeProperties.maxArtifacts()) {
            throw new IllegalStateException("Gradle plugin bridge reached its artifact limit.");
        }
        Files.createDirectories(target.getParent());
        String relativePath = coordinates.groupId().replace('.', '/')
                + "/"
                + coordinates.artifactId()
                + "/"
                + coordinates.version()
                + "/"
                + coordinates.artifactId()
                + "-"
                + coordinates.version()
                + "."
                + extension;
        Exception lastFailure = null;
        for (String repository : context.repositories) {
            String url = repository.replaceAll("/+$", "") + "/" + relativePath;
            try {
                ArtifactResponse response = artifactTransport.fetch(url, context.bridgeProperties.timeout(), context.networkSettings);
                if (response.statusCode() == 200) {
                    Files.write(target, response.body());
                    context.resolvedArtifacts.add(coordinates.toCoordinateString() + "@" + extension);
                    return;
                }
            } catch (Exception exception) {
                lastFailure = exception;
            }
        }
        throw lastFailure == null
                ? new IOException("Artifact not found for " + coordinates.toCoordinateString())
                : lastFailure;
    }

    private RawPomModel parsePom(Path pomFile) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setExpandEntityReferences(false);
        Document document;
        try (InputStream inputStream = Files.newInputStream(pomFile)) {
            document = factory.newDocumentBuilder().parse(inputStream);
        }
        Element project = document.getDocumentElement();
        String groupId = childText(project, "groupId");
        String artifactId = childText(project, "artifactId");
        String version = childText(project, "version");
        String packaging = childText(project, "packaging");
        Parent parent = null;
        Element parentElement = child(project, "parent");
        if (parentElement != null) {
            parent = new Parent(
                    childText(parentElement, "groupId"),
                    childText(parentElement, "artifactId"),
                    childText(parentElement, "version")
            );
        }
        Map<String, String> properties = new LinkedHashMap<>();
        Element propertiesElement = child(project, "properties");
        if (propertiesElement != null) {
            NodeList children = propertiesElement.getChildNodes();
            for (int index = 0; index < children.getLength(); index++) {
                Node node = children.item(index);
                if (node instanceof Element element) {
                    properties.put(element.getTagName(), element.getTextContent().trim());
                }
            }
        }
        List<Dependency> dependencyManagement = parseDependencies(child(child(project, "dependencyManagement"), "dependencies"));
        List<Dependency> dependencies = parseDependencies(child(project, "dependencies"));
        return new RawPomModel(groupId, artifactId, version, packaging, parent, properties, dependencyManagement, dependencies);
    }

    private List<Dependency> parseDependencies(Element dependenciesElement) {
        if (dependenciesElement == null) {
            return List.of();
        }
        List<Dependency> dependencies = new ArrayList<>();
        NodeList children = dependenciesElement.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (node instanceof Element element && "dependency".equals(element.getTagName())) {
                dependencies.add(new Dependency(
                        childText(element, "groupId"),
                        childText(element, "artifactId"),
                        childText(element, "version"),
                        childText(element, "scope"),
                        childText(element, "type"),
                        Boolean.parseBoolean(childText(element, "optional"))
                ));
            }
        }
        return List.copyOf(dependencies);
    }

    private Element child(Element element, String name) {
        if (element == null) {
            return null;
        }
        NodeList children = element.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (node instanceof Element child && name.equals(child.getTagName())) {
                return child;
            }
        }
        return null;
    }

    private String childText(Element element, String name) {
        Element child = child(element, name);
        return child == null ? null : child.getTextContent().trim();
    }

    private String substitute(String value, Map<String, String> properties) {
        if (value == null) {
            return null;
        }
        String resolved = value;
        for (int guard = 0; guard < 10; guard++) {
            Matcher matcher = PROPERTY_PATTERN.matcher(resolved);
            StringBuffer buffer = new StringBuffer();
            boolean changed = false;
            while (matcher.find()) {
                String replacement = properties.getOrDefault(matcher.group(1), matcher.group(0));
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
                changed = true;
            }
            matcher.appendTail(buffer);
            resolved = buffer.toString();
            if (!changed || !resolved.contains("${")) {
                return resolved;
            }
        }
        return resolved;
    }

    private List<String> pluginRepositories(AnalyzerProperties.GradleProperties properties) {
        LinkedHashSet<String> repositories = new LinkedHashSet<>();
        if (properties.pluginResolutionBridge() != null) {
            repositories.addAll(properties.pluginResolutionBridge().repositories());
        }
        repositories.addAll(properties.pluginRepositoryUrls());
        if (properties.addMavenCentralForPluginResolution()) {
            repositories.add("https://repo.maven.apache.org/maven2/");
        }
        return List.copyOf(repositories);
    }

    private String sourceLocation(String sourceFile, Integer line) {
        if (sourceFile == null) {
            return null;
        }
        return line == null ? sourceFile : sourceFile + ":" + line;
    }

    private String redact(String value) {
        return value == null ? null : value.replaceAll("(?i)(password|token|secret)=([^\\s]+)", "$1=[redacted]");
    }

    private String key(String groupId, String artifactId) {
        return (groupId == null ? "" : groupId) + ":" + (artifactId == null ? "" : artifactId);
    }

    public interface ArtifactTransport {
        ArtifactResponse fetch(String url, Duration timeout, BridgeNetworkSettings networkSettings) throws Exception;
    }

    static final class HttpArtifactTransport implements ArtifactTransport {
        @Override
        public ArtifactResponse fetch(String url, Duration timeout, BridgeNetworkSettings networkSettings) throws Exception {
            HttpClient.Builder builder = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(timeout);
            if (networkSettings.proxyAddress() != null) {
                builder.proxy(ProxySelector.of(networkSettings.proxyAddress()));
            }
            if (networkSettings.proxyUsername() != null && networkSettings.proxyPassword() != null) {
                builder.authenticator(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(
                                networkSettings.proxyUsername(),
                                networkSettings.proxyPassword().toCharArray()
                        );
                    }
                });
            }
            if (networkSettings.sslContext() != null) {
                builder.sslContext(networkSettings.sslContext());
            }
            HttpClient client = builder.build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return new ArtifactResponse(response.statusCode(), response.body());
        }
    }

    public static final class BridgeNetworkSettings {
        private final InetSocketAddress proxyAddress;
        private final String proxyUsername;
        private final String proxyPassword;
        private final SSLContext sslContext;

        private BridgeNetworkSettings(
                InetSocketAddress proxyAddress,
                String proxyUsername,
                String proxyPassword,
                SSLContext sslContext
        ) {
            this.proxyAddress = proxyAddress;
            this.proxyUsername = proxyUsername;
            this.proxyPassword = proxyPassword;
            this.sslContext = sslContext;
        }

        static BridgeNetworkSettings from(AnalyzerProperties.GradleProperties properties) {
            AnalyzerProperties.ProxyProperties explicitProxy = properties.proxy();
            String host = explicitProxy != null && explicitProxy.enabled() ? explicitProxy.host() : null;
            Integer port = explicitProxy != null && explicitProxy.enabled() ? explicitProxy.port() : null;
            String username = explicitProxy != null && explicitProxy.enabled() ? explicitProxy.username() : null;
            String password = explicitProxy != null && explicitProxy.enabled() ? explicitProxy.password() : null;

            if (host == null || host.isBlank()) {
                Map<String, String> hostProperties = com.robbanhoglund.springbootanalyzer.analyzer.gradle.GradleExecutionSupport.hostGradleProxyProperties();
                host = firstNonBlank(hostProperties.get("systemProp.https.proxyHost"), hostProperties.get("systemProp.http.proxyHost"));
                port = parseInt(firstNonBlank(hostProperties.get("systemProp.https.proxyPort"), hostProperties.get("systemProp.http.proxyPort")));
                username = firstNonBlank(hostProperties.get("systemProp.https.proxyUser"), hostProperties.get("systemProp.http.proxyUser"));
                password = firstNonBlank(hostProperties.get("systemProp.https.proxyPassword"), hostProperties.get("systemProp.http.proxyPassword"));
            }
            if (host == null || host.isBlank()) {
                ProxySpec envProxy = ProxySpec.fromEnvironment();
                if (envProxy != null) {
                    host = envProxy.host();
                    port = envProxy.port();
                    username = envProxy.username();
                    password = envProxy.password();
                }
            }

            SSLContext sslContext = null;
            AnalyzerProperties.SslProperties ssl = properties.ssl();
            if (ssl != null && ssl.trustStore() != null && Files.exists(ssl.trustStore())) {
                sslContext = buildSslContext(ssl);
            }
            return new BridgeNetworkSettings(
                    host == null || port == null ? null : InetSocketAddress.createUnresolved(host, port),
                    username,
                    password,
                    sslContext
            );
        }

        private static SSLContext buildSslContext(AnalyzerProperties.SslProperties ssl) {
            try (InputStream inputStream = Files.newInputStream(ssl.trustStore())) {
                KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
                char[] password = ssl.trustStorePassword() == null ? null : ssl.trustStorePassword().toCharArray();
                keyStore.load(inputStream, password);
                var trustManagerFactory = javax.net.ssl.TrustManagerFactory.getInstance(
                        javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
                );
                trustManagerFactory.init(keyStore);
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, trustManagerFactory.getTrustManagers(), new java.security.SecureRandom());
                return context;
            } catch (Exception exception) {
                return null;
            }
        }

        private static Integer parseInt(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException exception) {
                return null;
            }
        }

        private static String firstNonBlank(String first, String second) {
            if (first != null && !first.isBlank()) {
                return first;
            }
            return second;
        }

        InetSocketAddress proxyAddress() {
            return proxyAddress;
        }

        String proxyUsername() {
            return proxyUsername;
        }

        String proxyPassword() {
            return proxyPassword;
        }

        SSLContext sslContext() {
            return sslContext;
        }
    }

    record ProxySpec(String host, Integer port, String username, String password) {
        static ProxySpec fromEnvironment() {
            String raw = Optional.ofNullable(System.getenv("HTTPS_PROXY"))
                    .or(() -> Optional.ofNullable(System.getenv("https_proxy")))
                    .or(() -> Optional.ofNullable(System.getenv("HTTP_PROXY")))
                    .or(() -> Optional.ofNullable(System.getenv("http_proxy")))
                    .orElse(null);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                URI uri = URI.create(raw.contains("://") ? raw : "http://" + raw);
                String userInfo = uri.getUserInfo();
                String username = null;
                String password = null;
                if (userInfo != null && userInfo.contains(":")) {
                    String[] parts = userInfo.split(":", 2);
                    username = parts[0];
                    password = parts[1];
                }
                return new ProxySpec(uri.getHost(), uri.getPort(), username, password);
            } catch (Exception exception) {
                return null;
            }
        }
    }

    public record ArtifactResponse(int statusCode, byte[] body) {
    }

    record ArtifactCoordinates(String groupId, String artifactId, String version) {
        String toCoordinateString() {
            return groupId + ":" + artifactId + ":" + version;
        }
    }

    record Dependency(
            String groupId,
            String artifactId,
            String version,
            String scope,
            String type,
            boolean optional
    ) {
        ArtifactCoordinates toCoordinates() {
            return new ArtifactCoordinates(groupId, artifactId, version);
        }
    }

    record Parent(String groupId, String artifactId, String version) {
        ArtifactCoordinates toCoordinates(String fallbackGroupId, String fallbackVersion) {
            return new ArtifactCoordinates(
                    groupId == null ? fallbackGroupId : groupId,
                    artifactId,
                    version == null ? fallbackVersion : version
            );
        }
    }

    record RawPomModel(
            String groupId,
            String artifactId,
            String version,
            String packaging,
            Parent parent,
            Map<String, String> properties,
            List<Dependency> dependencyManagement,
            List<Dependency> dependencies
    ) {
    }

    record EffectivePom(
            String groupId,
            String artifactId,
            String version,
            String packaging,
            Map<String, String> properties,
            Map<String, Dependency> dependencyManagement,
            List<Dependency> dependencies
    ) {
    }

    record ResolutionContext(
            Path localRepository,
            List<String> repositories,
            AnalyzerProperties.PluginResolutionBridgeProperties bridgeProperties,
            BridgeNetworkSettings networkSettings,
            Map<String, EffectivePom> effectivePoms,
            Set<String> resolvedArtifacts
    ) {
        ResolutionContext(
                Path localRepository,
                List<String> repositories,
                AnalyzerProperties.PluginResolutionBridgeProperties bridgeProperties,
                BridgeNetworkSettings networkSettings
        ) {
            this(localRepository, repositories, bridgeProperties, networkSettings, new HashMap<>(), new LinkedHashSet<>());
        }

        Path localPathFor(ArtifactCoordinates coordinates, String extension) {
            return localRepository
                    .resolve(coordinates.groupId().replace('.', '/'))
                    .resolve(coordinates.artifactId())
                    .resolve(coordinates.version())
                    .resolve(coordinates.artifactId() + "-" + coordinates.version() + "." + extension);
        }
    }

    ArtifactCoordinates parseCoordinates(String coordinates) {
        String[] parts = coordinates.split(":");
        return new ArtifactCoordinates(parts[0], parts[1], parts[2]);
    }
}
