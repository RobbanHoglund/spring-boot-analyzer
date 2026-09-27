package com.robbanhoglund.springbootanalyzer.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildInfo;
import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildTool;
import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingSeverity;
import com.robbanhoglund.springbootanalyzer.analyzer.source.JavaSources;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContainerPracticeFindingAnalyzerTest {

    private static final String ENABLE_ON_NON_BEAN = "SPRING_ENABLE_ANNOTATION_ON_NON_BEAN_CLASS";
    private static final String BEAN_METHOD_INVALID = "SPRING_BEAN_METHOD_INVALID";
    private static final String INVALID_PREFIX = "SPRING_CONFIGURATION_PROPERTIES_INVALID_PREFIX";
    private static final String CONSTRUCTOR_BINDING =
            "SPRING_CONFIGURATION_PROPERTIES_BEAN_CONSTRUCTOR_BINDING";
    private static final String SCOPED_WITHOUT_PROXY = "SPRING_SCOPED_BEAN_WITHOUT_PROXY";
    private static final String BFPP_NOT_STATIC = "SPRING_BFPP_BEAN_METHOD_NOT_STATIC";

    @TempDir Path repoRoot;

    private final ContainerPracticeFindingAnalyzer analyzer =
            new ContainerPracticeFindingAnalyzer();

    private void write(String fileName, String content) throws IOException {
        Path file = repoRoot.resolve("src/main/java/com/example").resolve(fileName);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private List<Finding> findings(String bootVersion) {
        return analyzer.analyze(
                JavaSources.from(repoRoot),
                new BuildInfo(
                        BuildTool.GRADLE,
                        true,
                        "21",
                        List.of(),
                        bootVersion,
                        "build.gradle plugin",
                        "HIGH"));
    }

    private List<Finding> findings() {
        return findings("3.5.13");
    }

    private static List<Finding> byRule(List<Finding> findings, String ruleId) {
        return findings.stream().filter(finding -> ruleId.equals(finding.ruleId())).toList();
    }

    // ── SPRING_ENABLE_ANNOTATION_ON_NON_BEAN_CLASS ────────────────────────────

    @Test
    void flagsEnableWebSecurityOnClassWithoutConfiguration() throws IOException {
        write(
                "SecurityConfig.java",
                """
                package com.example;

                import org.springframework.context.annotation.Bean;
                import org.springframework.security.config.annotation.web.builders.HttpSecurity;
                import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
                import org.springframework.security.web.SecurityFilterChain;

                @EnableWebSecurity
                public class SecurityConfig {
                    @Bean
                    SecurityFilterChain chain(HttpSecurity http) throws Exception {
                        return http.authorizeHttpRequests(a -> a.anyRequest().authenticated()).build();
                    }
                }
                """);

        List<Finding> findings = byRule(findings(), ENABLE_ON_NON_BEAN);

        assertThat(findings)
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.severity()).isEqualTo(FindingSeverity.ERROR);
                            assertThat(finding.target()).isEqualTo("SecurityConfig");
                            assertThat(finding.message())
                                    .contains("@EnableWebSecurity", "SecurityConfig");
                            assertThat(finding.primaryLocation().startLine()).isEqualTo(8);
                        });
    }

    @Test
    void reportsNonSecurityEnableAnnotationAsWarning() throws IOException {
        write(
                "Jobs.java",
                """
                package com.example;

                import org.springframework.scheduling.annotation.EnableScheduling;

                @EnableScheduling
                class Jobs {
                }
                """);

        assertThat(byRule(findings(), ENABLE_ON_NON_BEAN))
                .singleElement()
                .extracting(Finding::severity)
                .isEqualTo(FindingSeverity.WARNING);
    }

    @Test
    void doesNotFlagEnableAnnotationOnRegisteredClasses() throws IOException {
        write(
                "WebSecurity.java",
                """
                package com.example;

                import org.springframework.context.annotation.Configuration;
                import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

                @Configuration
                @EnableWebSecurity
                class WebSecurity {
                }
                """);
        write(
                "MethodSecurity.java",
                """
                package com.example;

                import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

                @EnableMethodSecurity
                class MethodSecurity {
                }
                """);
        write(
                "Programmatic.java",
                """
                package com.example;

                import org.springframework.scheduling.annotation.EnableAsync;

                @EnableAsync
                class Programmatic {
                }
                """);
        write(
                "Application.java",
                """
                package com.example;

                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;
                import org.springframework.context.annotation.AnnotationConfigApplicationContext;
                import org.springframework.context.annotation.Import;

                @SpringBootApplication
                @Import(MethodSecurity.class)
                public class Application {
                    public static void main(String[] args) {
                        SpringApplication.run(Application.class, args);
                        new AnnotationConfigApplicationContext().register(Programmatic.class);
                    }
                }
                """);

        assertThat(byRule(findings(), ENABLE_ON_NON_BEAN)).isEmpty();
    }

    @Test
    void honoursCustomStereotypesAndIgnoresNonSpringEnableAnnotations() throws IOException {
        write(
                "SecurityConfiguration.java",
                """
                package com.example;

                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import org.springframework.context.annotation.Configuration;

                @Retention(RetentionPolicy.RUNTIME)
                @Configuration
                @interface SecurityConfiguration {
                }
                """);
        write(
                "Security.java",
                """
                package com.example;

                import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

                @SecurityConfiguration
                @EnableWebSecurity
                class Security {
                }
                """);
        write(
                "Locks.java",
                """
                package com.example;

                import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

                @EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
                class Locks {
                }
                """);

        assertThat(byRule(findings(), ENABLE_ON_NON_BEAN)).isEmpty();
    }

    // ── SPRING_BEAN_METHOD_INVALID ────────────────────────────────────────────

    @Test
    void flagsPrivateAndFinalBeanMethodsInProxiedConfiguration() throws IOException {
        write(
                "ClientConfig.java",
                """
                package com.example;

                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                class ClientConfig {
                    @Bean
                    private Client client() {
                        return new Client();
                    }

                    @Bean
                    final Client backupClient() {
                        return new Client();
                    }

                    @Bean
                    private static Client staticClient() {
                        return new Client();
                    }
                }

                class Client {
                }
                """);

        assertThat(byRule(findings(), BEAN_METHOD_INVALID))
                .extracting(Finding::target)
                .containsExactlyInAnyOrder("ClientConfig#client", "ClientConfig#backupClient");
    }

    @Test
    void allowsPrivateBeanMethodsWithoutBeanMethodProxying() throws IOException {
        write(
                "LiteConfig.java",
                """
                package com.example;

                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration(proxyBeanMethods = false)
                final class LiteConfig {
                    @Bean
                    private Client client() {
                        return new Client();
                    }
                }

                class Client {
                }
                """);

        assertThat(byRule(findings(), BEAN_METHOD_INVALID)).isEmpty();
    }

    @Test
    void flagsFinalConfigurationClassWithInstanceBeanMethods() throws IOException {
        write(
                "FinalConfig.java",
                """
                package com.example;

                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                final class FinalConfig {
                    @Bean
                    Client client() {
                        return new Client();
                    }
                }

                class Client {
                }
                """);

        assertThat(byRule(findings(), BEAN_METHOD_INVALID))
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.target()).isEqualTo("FinalConfig");
                            assertThat(finding.message()).contains("is final");
                        });
    }

    @Test
    void flagsVoidAndAutowiredBeanMethodsOnlyFromSpringBoot34() throws IOException {
        write(
                "InitConfig.java",
                """
                package com.example;

                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.context.annotation.Bean;
                import org.springframework.stereotype.Component;

                @Component
                class InitConfig {
                    @Bean
                    void warmUp() {
                    }

                    @Bean
                    @Autowired
                    Client client() {
                        return new Client();
                    }
                }

                class Client {
                }
                """);

        assertThat(byRule(findings("3.5.13"), BEAN_METHOD_INVALID))
                .extracting(Finding::message)
                .anyMatch(message -> message.contains("returns void"))
                .anyMatch(message -> message.contains("also annotated @Autowired"));
        assertThat(byRule(findings("3.3.5"), BEAN_METHOD_INVALID)).isEmpty();
    }

    @Test
    void flagsOverloadedBeanMethodsUnlessUniquenessIsRelaxed() throws IOException {
        write(
                "Overloads.java",
                """
                package com.example;

                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                class Overloads {
                    @Bean
                    Client client() {
                        return new Client();
                    }

                    @Bean
                    Client client(Settings settings) {
                        return new Client();
                    }
                }

                @Configuration(enforceUniqueMethods = false)
                class RelaxedOverloads {
                    @Bean
                    Client relaxed() {
                        return new Client();
                    }

                    @Bean
                    Client relaxed(Settings settings) {
                        return new Client();
                    }
                }

                class Client {
                }

                class Settings {
                }
                """);

        assertThat(byRule(findings(), BEAN_METHOD_INVALID))
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.target()).isEqualTo("Overloads#client");
                            assertThat(finding.primaryLocation().startLine()).isEqualTo(13);
                        });
    }

    // ── SPRING_CONFIGURATION_PROPERTIES_INVALID_PREFIX ─────────────────────────

    @Test
    void flagsNonCanonicalConfigurationPropertiesPrefixes() throws IOException {
        write(
                "Props.java",
                """
                package com.example;

                import org.springframework.boot.context.properties.ConfigurationProperties;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @ConfigurationProperties("myApp")
                class CamelCaseProps {
                }

                @ConfigurationProperties(prefix = "app.mail_server")
                class UnderscoreProps {
                }

                @ConfigurationProperties("app.mail-server")
                class CanonicalProps {
                }

                @Configuration
                class DataSourceConfig {
                    @Bean
                    @ConfigurationProperties("app.DataSource")
                    Object dataSource() {
                        return new Object();
                    }
                }
                """);

        List<Finding> findings = byRule(findings(), INVALID_PREFIX);

        assertThat(findings)
                .extracting(Finding::target)
                .containsExactlyInAnyOrder(
                        "CamelCaseProps", "UnderscoreProps", "DataSourceConfig#dataSource");
        assertThat(findings)
                .filteredOn(finding -> "CamelCaseProps".equals(finding.target()))
                .singleElement()
                .extracting(Finding::recommendation)
                .asString()
                .contains("\"my-app\"");
    }

    // ── SPRING_CONFIGURATION_PROPERTIES_BEAN_CONSTRUCTOR_BINDING ───────────────

    @Test
    void flagsConstructorBoundPropertiesThatAreAlsoComponents() throws IOException {
        write(
                "AppProperties.java",
                """
                package com.example;

                import org.springframework.boot.context.properties.ConfigurationProperties;
                import org.springframework.stereotype.Component;

                @Component
                @ConfigurationProperties("app")
                record AppProperties(String name, int port) {
                }
                """);

        assertThat(byRule(findings(), CONSTRUCTOR_BINDING))
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.target()).isEqualTo("AppProperties");
                            assertThat(finding.message()).contains("@Component", "String name");
                        });
    }

    @Test
    void doesNotFlagScannedRecordsOrComponentsWithBeanConstructors() throws IOException {
        write(
                "Props.java",
                """
                package com.example;

                import org.springframework.boot.context.properties.ConfigurationProperties;
                import org.springframework.stereotype.Component;

                @ConfigurationProperties("app")
                record ScannedProperties(String name) {
                }

                @Component
                @ConfigurationProperties("mail")
                class MailProperties {
                    private String host;

                    MailProperties(Clock clock) {
                    }
                }

                @Component
                @ConfigurationProperties("cache")
                class CacheProperties {
                    private int size;

                    public void setSize(int size) {
                        this.size = size;
                    }
                }

                class Clock {
                }
                """);

        assertThat(byRule(findings(), CONSTRUCTOR_BINDING)).isEmpty();
    }

    // ── SPRING_SCOPED_BEAN_WITHOUT_PROXY ──────────────────────────────────────

    @Test
    void flagsUnproxiedRequestScopedBeanInjectedIntoSingleton() throws IOException {
        write(
                "RequestContext.java",
                """
                package com.example;

                import org.springframework.context.annotation.Scope;
                import org.springframework.stereotype.Component;

                @Component
                @Scope("request")
                class RequestContext {
                }
                """);
        write(
                "OrderService.java",
                """
                package com.example;

                import org.springframework.stereotype.Service;

                @Service
                class OrderService {
                    private final RequestContext requestContext;

                    OrderService(RequestContext requestContext) {
                        this.requestContext = requestContext;
                    }
                }
                """);

        assertThat(byRule(findings(), SCOPED_WITHOUT_PROXY))
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.target()).isEqualTo("OrderService.requestContext");
                            assertThat(finding.message()).contains("request-scoped");
                        });
    }

    @Test
    void flagsSessionScopeConstantAndFieldInjection() throws IOException {
        write(
                "Cart.java",
                """
                package com.example;

                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.context.annotation.Scope;
                import org.springframework.stereotype.Component;
                import org.springframework.web.context.WebApplicationContext;

                @Component
                @Scope(WebApplicationContext.SCOPE_SESSION)
                class Cart {
                }

                @Component
                class Checkout {
                    @Autowired
                    private Cart cart;
                }
                """);

        assertThat(byRule(findings(), SCOPED_WITHOUT_PROXY))
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.target()).isEqualTo("Checkout.cart");
                            assertThat(finding.recommendation()).contains("@SessionScope");
                        });
    }

    @Test
    void doesNotFlagProxiedOrLazilyResolvedScopedBeans() throws IOException {
        write(
                "Scoped.java",
                """
                package com.example;

                import org.springframework.beans.factory.ObjectProvider;
                import org.springframework.context.annotation.Lazy;
                import org.springframework.context.annotation.Scope;
                import org.springframework.context.annotation.ScopedProxyMode;
                import org.springframework.stereotype.Component;
                import org.springframework.web.context.annotation.RequestScope;

                @Component
                @RequestScope
                class ProxiedByDefault {
                }

                @Component
                @Scope(value = "request", proxyMode = ScopedProxyMode.TARGET_CLASS)
                class ExplicitProxy {
                }

                @Component
                @Scope("request")
                class Unproxied {
                }

                @Component
                @Scope("prototype")
                class Prototype {
                }

                @Component
                class Consumer {
                    Consumer(
                            ProxiedByDefault proxied,
                            ExplicitProxy explicit,
                            ObjectProvider<Unproxied> provider,
                            @Lazy Unproxied lazy,
                            Prototype prototype) {
                    }
                }
                """);

        assertThat(byRule(findings(), SCOPED_WITHOUT_PROXY)).isEmpty();
    }

    // ── SPRING_BFPP_BEAN_METHOD_NOT_STATIC ────────────────────────────────────

    @Test
    void flagsNonStaticPostProcessorBeanInClassThatUsesInjection() throws IOException {
        write(
                "PlaceholderConfig.java",
                """
                package com.example;

                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

                @Configuration
                class PlaceholderConfig {
                    @Value("${app.name}")
                    private String name;

                    @Bean
                    PropertySourcesPlaceholderConfigurer placeholderConfigurer() {
                        return new PropertySourcesPlaceholderConfigurer();
                    }
                }
                """);

        assertThat(byRule(findings(), BFPP_NOT_STATIC))
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.target())
                                    .isEqualTo("PlaceholderConfig#placeholderConfigurer");
                            assertThat(finding.severity()).isEqualTo(FindingSeverity.WARNING);
                        });
    }

    @Test
    void flagsProjectPostProcessorTypesAndSkipsStaticOrInjectionFreeClasses() throws IOException {
        write(
                "Registrar.java",
                """
                package com.example;

                import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
                import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

                class Registrar implements BeanFactoryPostProcessor {
                    @Override
                    public void postProcessBeanFactory(ConfigurableListableBeanFactory factory) {
                    }
                }
                """);
        write(
                "Configs.java",
                """
                package com.example;

                import jakarta.annotation.PostConstruct;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

                @Configuration
                class WithLifecycle {
                    @PostConstruct
                    void init() {
                    }

                    @Bean
                    Registrar registrar() {
                        return new Registrar();
                    }

                    @Bean
                    static PropertySourcesPlaceholderConfigurer placeholders() {
                        return new PropertySourcesPlaceholderConfigurer();
                    }
                }

                @Configuration
                class WithoutInjection {
                    @Bean
                    Registrar otherRegistrar() {
                        return new Registrar();
                    }
                }
                """);

        assertThat(byRule(findings(), BFPP_NOT_STATIC))
                .extracting(Finding::target)
                .containsExactly("WithLifecycle#registrar");
    }
}
