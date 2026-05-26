package com.robbanhoglund.springbootanalyzer.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecurityPracticeFindingAnalyzerTest {

    @TempDir Path repoRoot;

    private SecurityPracticeFindingAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new SecurityPracticeFindingAnalyzer();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void writeSourceFile(String relativePath, String content) throws IOException {
        Path file = repoRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private List<Finding> findings() {
        return analyzer.analyze(repoRoot);
    }

    private static Finding byRule(List<Finding> findings, String ruleId) {
        return findings.stream().filter(f -> ruleId.equals(f.ruleId())).findFirst().orElse(null);
    }

    // ── No sources ────────────────────────────────────────────────────────────

    @Test
    void returnsEmptyListWhenNoMainDirectory() {
        assertThat(findings()).isEmpty();
    }

    // ── SPRING_CSRF_DISABLED_CODE — csrf().disable() ──────────────────────────

    @Test
    void flagsCsrfDisableChainSyntax() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/SecurityConfig.java",
                """
                package com.example;
                import org.springframework.security.config.annotation.web.builders.HttpSecurity;
                public class SecurityConfig {
                    public void configure(HttpSecurity http) throws Exception {
                        http.csrf().disable().authorizeRequests().anyRequest().authenticated();
                    }
                }
                """);

        Finding f = byRule(findings(), "SPRING_CSRF_DISABLED_CODE");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("csrf");
    }

    @Test
    void flagsCsrfWithMethodReferenceArgument() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/SecurityConfig.java",
                """
                package com.example;
                import org.springframework.security.config.annotation.web.builders.HttpSecurity;
                import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
                public class SecurityConfig {
                    public void configure(HttpSecurity http) throws Exception {
                        http.csrf(AbstractHttpConfigurer::disable);
                    }
                }
                """);

        Finding f = byRule(findings(), "SPRING_CSRF_DISABLED_CODE");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("csrf");
    }

    @Test
    void doesNotFlagFileWithNoCsrfDisable() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/SecurityConfig.java",
                """
                package com.example;
                import org.springframework.security.config.annotation.web.builders.HttpSecurity;
                public class SecurityConfig {
                    public void configure(HttpSecurity http) throws Exception {
                        http.authorizeRequests().anyRequest().authenticated();
                    }
                }
                """);

        assertThat(byRule(findings(), "SPRING_CSRF_DISABLED_CODE")).isNull();
    }

    @Test
    void doesNotFlagFileWithNoCsrfAtAll() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/OrderService.java",
                """
                package com.example;
                public class OrderService {
                    public void save() {}
                }
                """);

        assertThat(byRule(findings(), "SPRING_CSRF_DISABLED_CODE")).isNull();
    }

    // ── SPRING_PREAUTHORIZE_ON_PRIVATE_METHOD ─────────────────────────────────

    @Test
    void flagsPreAuthorizeOnPrivateMethod() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/AdminService.java",
                """
                package com.example;
                import org.springframework.security.access.prepost.PreAuthorize;
                public class AdminService {
                    @PreAuthorize("hasRole('ADMIN')")
                    private void deleteAll() {}
                }
                """);

        Finding f = byRule(findings(), "SPRING_PREAUTHORIZE_ON_PRIVATE_METHOD");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("AdminService#deleteAll");
        assertThat(f.message()).contains("PreAuthorize").contains("private");
    }

    @Test
    void flagsPostAuthorizeOnPrivateMethod() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/AdminService.java",
                """
                package com.example;
                import org.springframework.security.access.prepost.PostAuthorize;
                public class AdminService {
                    @PostAuthorize("returnObject.owner == authentication.name")
                    private Object loadSecret() { return null; }
                }
                """);

        Finding f = byRule(findings(), "SPRING_PREAUTHORIZE_ON_PRIVATE_METHOD");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("PostAuthorize");
    }

    @Test
    void flagsSecuredOnPrivateMethod() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/AdminService.java",
                """
                package com.example;
                import org.springframework.security.access.annotation.Secured;
                public class AdminService {
                    @Secured("ROLE_ADMIN")
                    private void sensitiveOp() {}
                }
                """);

        Finding f = byRule(findings(), "SPRING_PREAUTHORIZE_ON_PRIVATE_METHOD");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("Secured");
    }

    @Test
    void flagsRolesAllowedOnPrivateMethod() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/AdminService.java",
                """
                package com.example;
                import jakarta.annotation.security.RolesAllowed;
                public class AdminService {
                    @RolesAllowed("ADMIN")
                    private void sensitiveOp() {}
                }
                """);

        Finding f = byRule(findings(), "SPRING_PREAUTHORIZE_ON_PRIVATE_METHOD");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("RolesAllowed");
    }

    @Test
    void doesNotFlagPreAuthorizeOnPublicMethod() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/AdminService.java",
                """
                package com.example;
                import org.springframework.security.access.prepost.PreAuthorize;
                public class AdminService {
                    @PreAuthorize("hasRole('ADMIN')")
                    public void deleteAll() {}
                }
                """);

        assertThat(byRule(findings(), "SPRING_PREAUTHORIZE_ON_PRIVATE_METHOD")).isNull();
    }

    @Test
    void doesNotFlagPrivateMethodWithoutSecurityAnnotation() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/AdminService.java",
                """
                package com.example;
                public class AdminService {
                    private void helper() {}
                }
                """);

        assertThat(byRule(findings(), "SPRING_PREAUTHORIZE_ON_PRIVATE_METHOD")).isNull();
    }

    // ── SPRING_INSECURE_TRUST_MANAGER ─────────────────────────────────────────

    @Test
    void flagsX509TrustManagerWithEmptyCheckServerTrusted() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/PermissiveTrustManager.java",
                """
                package com.example;
                import java.security.cert.X509Certificate;
                import javax.net.ssl.X509TrustManager;
                public class PermissiveTrustManager implements X509TrustManager {
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }
                """);

        Finding f = byRule(findings(), "SPRING_INSECURE_TRUST_MANAGER");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("checkServerTrusted");
    }

    @Test
    void doesNotFlagTrustManagerThatThrows() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/StrictTrustManager.java",
                """
                package com.example;
                import java.security.cert.CertificateException;
                import java.security.cert.X509Certificate;
                import javax.net.ssl.X509TrustManager;
                public class StrictTrustManager implements X509TrustManager {
                    public void checkServerTrusted(X509Certificate[] chain, String authType)
                            throws CertificateException {
                        throw new CertificateException("untrusted");
                    }
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        throw new RuntimeException("not implemented");
                    }
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }
                """);

        assertThat(byRule(findings(), "SPRING_INSECURE_TRUST_MANAGER")).isNull();
    }

    @Test
    void flagsHostnameVerifierReturningTrueUnconditionally() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/AcceptAllHosts.java",
                """
                package com.example;
                import javax.net.ssl.HostnameVerifier;
                import javax.net.ssl.SSLSession;
                public class AcceptAllHosts implements HostnameVerifier {
                    public boolean verify(String hostname, SSLSession session) {
                        return true;
                    }
                }
                """);

        Finding f = byRule(findings(), "SPRING_INSECURE_TRUST_MANAGER");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("HostnameVerifier");
    }

    @Test
    void flagsSetHostnameVerifierLambdaReturningTrue() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/HttpsClient.java",
                """
                package com.example;
                import javax.net.ssl.HttpsURLConnection;
                public class HttpsClient {
                    public void setup(HttpsURLConnection conn) {
                        conn.setHostnameVerifier((hostname, session) -> true);
                    }
                }
                """);

        Finding f = byRule(findings(), "SPRING_INSECURE_TRUST_MANAGER");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("setHostnameVerifier");
    }

    @Test
    void doesNotFlagHostnameVerifierWithRealCheck() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/SafeHosts.java",
                """
                package com.example;
                import javax.net.ssl.HostnameVerifier;
                import javax.net.ssl.SSLSession;
                public class SafeHosts implements HostnameVerifier {
                    public boolean verify(String hostname, SSLSession session) {
                        return "internal.example.com".equals(hostname);
                    }
                }
                """);

        assertThat(byRule(findings(), "SPRING_INSECURE_TRUST_MANAGER")).isNull();
    }

    // ── SPRING_XXE_VULNERABLE_PARSER ──────────────────────────────────────────

    @Test
    void flagsDocumentBuilderFactoryWithoutSetFeature() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/XmlReader.java",
                """
                package com.example;
                import javax.xml.parsers.DocumentBuilderFactory;
                public class XmlReader {
                    public void read() {
                        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                        // ... use factory without disabling external entities ...
                    }
                }
                """);

        Finding f = byRule(findings(), "SPRING_XXE_VULNERABLE_PARSER");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("DocumentBuilderFactory");
    }

    @Test
    void doesNotFlagDocumentBuilderFactoryWithSetFeature() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/SafeXmlReader.java",
                """
                package com.example;
                import javax.xml.XMLConstants;
                import javax.xml.parsers.DocumentBuilderFactory;
                public class SafeXmlReader {
                    public void read() throws Exception {
                        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                    }
                }
                """);

        assertThat(byRule(findings(), "SPRING_XXE_VULNERABLE_PARSER")).isNull();
    }

    @Test
    void flagsXmlInputFactoryWithoutSetProperty() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/StaxReader.java",
                """
                package com.example;
                import javax.xml.stream.XMLInputFactory;
                public class StaxReader {
                    public void read() {
                        XMLInputFactory factory = XMLInputFactory.newFactory();
                    }
                }
                """);

        Finding f = byRule(findings(), "SPRING_XXE_VULNERABLE_PARSER");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("XMLInputFactory");
    }

    // ── SPRING_INSECURE_DESERIALIZATION ───────────────────────────────────────

    @Test
    void flagsJacksonEnableDefaultTyping() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/JsonConfig.java",
                """
                package com.example;
                import com.fasterxml.jackson.databind.ObjectMapper;
                public class JsonConfig {
                    public ObjectMapper mapper() {
                        ObjectMapper m = new ObjectMapper();
                        m.enableDefaultTyping();
                        return m;
                    }
                }
                """);

        Finding f = byRule(findings(), "SPRING_INSECURE_DESERIALIZATION");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("enableDefaultTyping");
    }

    @Test
    void flagsJacksonActivateDefaultTyping() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/JsonConfig.java",
                """
                package com.example;
                import com.fasterxml.jackson.databind.ObjectMapper;
                import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
                public class JsonConfig {
                    public ObjectMapper mapper() {
                        ObjectMapper m = new ObjectMapper();
                        m.activateDefaultTyping(LaissezFaireSubTypeValidator.instance);
                        return m;
                    }
                }
                """);

        Finding f = byRule(findings(), "SPRING_INSECURE_DESERIALIZATION");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("activateDefaultTyping");
    }

    @Test
    void flagsObjectInputStreamConstruction() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/Deserializer.java",
                """
                package com.example;
                import java.io.InputStream;
                import java.io.ObjectInputStream;
                public class Deserializer {
                    public Object read(InputStream in) throws Exception {
                        ObjectInputStream ois = new ObjectInputStream(in);
                        return ois.readObject();
                    }
                }
                """);

        Finding f = byRule(findings(), "SPRING_INSECURE_DESERIALIZATION");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("ObjectInputStream");
    }

    @Test
    void flagsSnakeYamlNoArgConstructor() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/YamlReader.java",
                """
                package com.example;
                import org.yaml.snakeyaml.Yaml;
                public class YamlReader {
                    public Object read(String input) {
                        Yaml yaml = new Yaml();
                        return yaml.load(input);
                    }
                }
                """);

        Finding f = byRule(findings(), "SPRING_INSECURE_DESERIALIZATION");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("Yaml");
    }

    @Test
    void doesNotFlagSnakeYamlWithSafeConstructor() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/SafeYamlReader.java",
                """
                package com.example;
                import org.yaml.snakeyaml.Yaml;
                import org.yaml.snakeyaml.constructor.SafeConstructor;
                import org.yaml.snakeyaml.LoaderOptions;
                public class SafeYamlReader {
                    public Object read(String input) {
                        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
                        return yaml.load(input);
                    }
                }
                """);

        assertThat(byRule(findings(), "SPRING_INSECURE_DESERIALIZATION")).isNull();
    }

    // ── SPRING_SECURITY_HEADERS_DISABLED ──────────────────────────────────────

    @Test
    void flagsFrameOptionsDisable() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/SecurityConfig.java",
                """
                package com.example;
                import org.springframework.security.config.annotation.web.builders.HttpSecurity;
                public class SecurityConfig {
                    public void configure(HttpSecurity http) throws Exception {
                        http.headers().frameOptions().disable();
                    }
                }
                """);

        Finding f = byRule(findings(), "SPRING_SECURITY_HEADERS_DISABLED");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("frameOptions");
    }

    @Test
    void flagsHeadersDisableInsideHttpSecurity() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/SecurityConfig.java",
                """
                package com.example;
                import org.springframework.security.config.annotation.web.builders.HttpSecurity;
                public class SecurityConfig {
                    public void configure(HttpSecurity http) throws Exception {
                        http.headers().disable();
                    }
                }
                """);

        Finding f = byRule(findings(), "SPRING_SECURITY_HEADERS_DISABLED");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("headers");
    }

    @Test
    void doesNotFlagHeadersDisableInUnrelatedDsl() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/EmailBuilder.java",
                """
                package com.example;
                public class EmailBuilder {
                    private final Builder builder = new Builder();
                    public void configure() {
                        builder.headers().disable();
                    }
                    static class Builder {
                        Headers headers() { return new Headers(); }
                    }
                    static class Headers {
                        void disable() {}
                    }
                }
                """);

        assertThat(byRule(findings(), "SPRING_SECURITY_HEADERS_DISABLED")).isNull();
    }

    // ── SPRING_PERMIT_ALL_ANY_REQUEST ─────────────────────────────────────────

    @Test
    void flagsAnyRequestPermitAll() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/SecurityConfig.java",
                """
                package com.example;
                import org.springframework.security.config.annotation.web.builders.HttpSecurity;
                public class SecurityConfig {
                    public void configure(HttpSecurity http) throws Exception {
                        http.authorizeHttpRequests().anyRequest().permitAll();
                    }
                }
                """);

        Finding f = byRule(findings(), "SPRING_PERMIT_ALL_ANY_REQUEST");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("anyRequest");
    }

    @Test
    void flagsRequestMatchersWildcardPermitAll() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/SecurityConfig.java",
                """
                package com.example;
                import org.springframework.security.config.annotation.web.builders.HttpSecurity;
                public class SecurityConfig {
                    public void configure(HttpSecurity http) throws Exception {
                        http.authorizeHttpRequests(a -> a.requestMatchers("/**").permitAll());
                    }
                }
                """);

        Finding f = byRule(findings(), "SPRING_PERMIT_ALL_ANY_REQUEST");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("/**");
    }

    @Test
    void doesNotFlagPermitAllOnSpecificPath() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/SecurityConfig.java",
                """
                package com.example;
                import org.springframework.security.config.annotation.web.builders.HttpSecurity;
                public class SecurityConfig {
                    public void configure(HttpSecurity http) throws Exception {
                        http.authorizeHttpRequests(a -> a
                            .requestMatchers("/public/**").permitAll()
                            .anyRequest().authenticated());
                    }
                }
                """);

        assertThat(byRule(findings(), "SPRING_PERMIT_ALL_ANY_REQUEST")).isNull();
    }

    // ── SPRING_H2_CONSOLE_PERMITALL ───────────────────────────────────────────

    @Test
    void flagsH2ConsolePermitAll() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/SecurityConfig.java",
                """
                package com.example;
                import org.springframework.security.config.annotation.web.builders.HttpSecurity;
                public class SecurityConfig {
                    public void configure(HttpSecurity http) throws Exception {
                        http.authorizeHttpRequests(a -> a
                            .requestMatchers("/h2-console/**").permitAll()
                            .anyRequest().authenticated());
                    }
                }
                """);

        Finding f = byRule(findings(), "SPRING_H2_CONSOLE_PERMITALL");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("H2 console");
    }

    @Test
    void doesNotFlagOtherPathsPermitAll() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/SecurityConfig.java",
                """
                package com.example;
                import org.springframework.security.config.annotation.web.builders.HttpSecurity;
                public class SecurityConfig {
                    public void configure(HttpSecurity http) throws Exception {
                        http.authorizeHttpRequests(a -> a
                            .requestMatchers("/api/public/**").permitAll());
                    }
                }
                """);

        assertThat(byRule(findings(), "SPRING_H2_CONSOLE_PERMITALL")).isNull();
    }
}
