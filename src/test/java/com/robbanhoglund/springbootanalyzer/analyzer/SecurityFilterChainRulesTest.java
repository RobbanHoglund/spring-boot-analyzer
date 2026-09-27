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

/**
 * Filter-chain and authorization rules of {@link SecurityPracticeFindingAnalyzer}, plus the
 * false-positive guards of the SSRF, redirect, cookie and logging rules.
 */
class SecurityFilterChainRulesTest {

    private static final String PERMIT_ALL = "SPRING_PERMIT_ALL_ANY_REQUEST";
    private static final String NO_AUTHORIZATION = "SPRING_SECURITY_FILTER_CHAIN_NO_AUTHORIZATION";
    private static final String RULE_INVALID = "SPRING_SECURITY_AUTHORIZATION_RULE_INVALID";
    private static final String UNREACHABLE = "SPRING_SECURITY_FILTER_CHAIN_UNREACHABLE";

    private static final String IMPORTS =
            """
            package com.example;

            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;
            import org.springframework.core.annotation.Order;
            import org.springframework.security.config.Customizer;
            import org.springframework.security.config.annotation.web.builders.HttpSecurity;
            import org.springframework.security.web.SecurityFilterChain;

            """;

    @TempDir Path repoRoot;

    private final SecurityPracticeFindingAnalyzer analyzer = new SecurityPracticeFindingAnalyzer();

    private void write(String fileName, String content) throws IOException {
        Path file = repoRoot.resolve("src/main/java/com/example").resolve(fileName);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void writeSecurityConfig(String body) throws IOException {
        write(
                "SecurityConfig.java",
                IMPORTS + "@Configuration\nclass SecurityConfig {\n" + body + "\n}\n");
    }

    private List<Finding> findings(String ruleId, String bootVersion) {
        BuildInfo buildInfo =
                bootVersion == null
                        ? null
                        : new BuildInfo(
                                BuildTool.GRADLE,
                                true,
                                "21",
                                List.of(),
                                bootVersion,
                                "build.gradle plugin",
                                "HIGH");
        return analyzer.analyze(JavaSources.from(repoRoot), buildInfo).stream()
                .filter(finding -> ruleId.equals(finding.ruleId()))
                .toList();
    }

    private List<Finding> findings(String ruleId) {
        return findings(ruleId, "3.5.13");
    }

    // ── SPRING_PERMIT_ALL_ANY_REQUEST ─────────────────────────────────────────

    @Test
    void reportsCatchAllPermitAllAsOnlyRuleAsError() throws IOException {
        writeSecurityConfig(
                """
                    @Bean
                    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
                        return http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
                    }
                """);

        assertThat(findings(PERMIT_ALL))
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.severity()).isEqualTo(FindingSeverity.ERROR);
                            assertThat(finding.message())
                                    .contains(
                                            "anyRequest().permitAll() is the only authorization"
                                                    + " rule in SecurityConfig#filterChain");
                            assertThat(finding.target()).isEqualTo("SecurityConfig#filterChain");
                        });
    }

    @Test
    void reportsCatchAllPermitAllAfterRestrictingRulesAsFallbackWarning() throws IOException {
        writeSecurityConfig(
                """
                    @Bean
                    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
                        return http.authorizeHttpRequests(auth -> auth
                                        .requestMatchers("/admin/**").hasRole("ADMIN")
                                        .anyRequest().permitAll())
                                .build();
                    }
                """);

        assertThat(findings(PERMIT_ALL))
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.severity()).isEqualTo(FindingSeverity.WARNING);
                            assertThat(finding.message()).contains("ends the rules");
                        });
    }

    @Test
    void reportsPermitAllOnConditionalBranchAsWarning() throws IOException {
        writeSecurityConfig(
                """
                    private boolean securityEnabled;

                    @Bean
                    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
                        if (!securityEnabled) {
                            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
                        } else {
                            http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
                        }
                        return http.build();
                    }
                """);

        assertThat(findings(PERMIT_ALL))
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.severity()).isEqualTo(FindingSeverity.WARNING);
                            assertThat(finding.message()).contains("on a conditional branch");
                        });
    }

    @Test
    void doesNotReportPermitAllInChainScopedWithSecurityMatcher() throws IOException {
        writeSecurityConfig(
                """
                    @Bean
                    @Order(1)
                    SecurityFilterChain actuator(HttpSecurity http) throws Exception {
                        return http.securityMatcher("/actuator/**")
                                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                                .build();
                    }
                """);

        assertThat(findings(PERMIT_ALL)).isEmpty();
    }

    // ── SPRING_SECURITY_FILTER_CHAIN_NO_AUTHORIZATION ─────────────────────────

    @Test
    void flagsFilterChainThatNeverAuthorizesRequests() throws IOException {
        writeSecurityConfig(
                """
                    @Bean
                    SecurityFilterChain api(HttpSecurity http) throws Exception {
                        return http.csrf(csrf -> csrf.disable())
                                .httpBasic(Customizer.withDefaults())
                                .build();
                    }
                """);

        assertThat(findings(NO_AUTHORIZATION))
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.severity()).isEqualTo(FindingSeverity.ERROR);
                            assertThat(finding.target()).isEqualTo("SecurityConfig#api");
                            assertThat(finding.primaryLocation().startLine()).isEqualTo(13);
                        });
    }

    @Test
    void doesNotFlagChainsThatAuthorizeScopeOrDelegate() throws IOException {
        writeSecurityConfig(
                """
                    @Bean
                    @Order(1)
                    SecurityFilterChain assets(HttpSecurity http) throws Exception {
                        return http.securityMatcher("/assets/**").build();
                    }

                    @Bean
                    @Order(2)
                    SecurityFilterChain shared(HttpSecurity http) throws Exception {
                        applyCommonRules(http);
                        return http.securityMatcher("/shared/**").build();
                    }

                    @Bean
                    SecurityFilterChain delegated(HttpSecurity http) throws Exception {
                        applyCommonRules(http);
                        return http.build();
                    }

                    @Bean
                    SecurityFilterChain web(HttpSecurity http) throws Exception {
                        return http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                                .build();
                    }

                    private void applyCommonRules(HttpSecurity http) throws Exception {
                        http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
                    }
                """);

        assertThat(findings(NO_AUTHORIZATION)).isEmpty();
    }

    @Test
    void skipsChainCheckWhenHttpSecurityCustomizerBeansExist() throws IOException {
        writeSecurityConfig(
                """
                    @Bean
                    SecurityFilterChain api(HttpSecurity http) throws Exception {
                        return http.httpBasic(Customizer.withDefaults()).build();
                    }

                    @Bean
                    Customizer<HttpSecurity> authorization() {
                        return http -> {
                        };
                    }
                """);

        assertThat(findings(NO_AUTHORIZATION)).isEmpty();
    }

    // ── SPRING_SECURITY_AUTHORIZATION_RULE_INVALID ────────────────────────────

    @Test
    void flagsRolePrefixInServletHasRole() throws IOException {
        writeSecurityConfig(
                """
                    @Bean
                    SecurityFilterChain web(HttpSecurity http) throws Exception {
                        return http.authorizeHttpRequests(auth -> auth
                                        .requestMatchers("/admin/**").hasRole("ROLE_ADMIN")
                                        .requestMatchers("/ops/**").hasAuthority("ROLE_OPS")
                                        .anyRequest().authenticated())
                                .build();
                    }
                """);

        assertThat(findings(RULE_INVALID))
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.severity()).isEqualTo(FindingSeverity.ERROR);
                            assertThat(finding.message())
                                    .contains("hasRole(\"ROLE_ADMIN\")", "fails at startup");
                            assertThat(finding.recommendation()).contains("hasRole(\"ADMIN\")");
                        });
    }

    @Test
    void flagsRolePrefixInReactiveHasRoleAsNeverMatching() throws IOException {
        write(
                "ReactiveSecurity.java",
                """
                package com.example;

                import org.springframework.context.annotation.Bean;
                import org.springframework.security.config.web.server.ServerHttpSecurity;
                import org.springframework.security.web.server.SecurityWebFilterChain;

                class ReactiveSecurity {
                    @Bean
                    SecurityWebFilterChain chain(ServerHttpSecurity http) {
                        return http.authorizeExchange(exchanges -> exchanges
                                        .pathMatchers("/admin/**").hasRole("ROLE_ADMIN")
                                        .anyExchange().authenticated())
                                .build();
                    }
                }
                """);

        assertThat(findings(RULE_INVALID))
                .singleElement()
                .extracting(Finding::message)
                .asString()
                .contains("never matches", "ROLE_ROLE_ADMIN");
    }

    @Test
    void doesNotFlagRolePrefixWithCustomAuthorityDefaultsOrInSpel() throws IOException {
        writeSecurityConfig(
                """
                    @Bean
                    static org.springframework.security.config.core.GrantedAuthorityDefaults defaults() {
                        return new org.springframework.security.config.core.GrantedAuthorityDefaults("");
                    }

                    @Bean
                    SecurityFilterChain web(HttpSecurity http) throws Exception {
                        return http.authorizeHttpRequests(auth -> auth
                                        .requestMatchers("/admin/**").hasRole("ROLE_ADMIN")
                                        .anyRequest().authenticated())
                                .build();
                    }
                """);
        write(
                "AdminService.java",
                """
                package com.example;

                import org.springframework.security.access.prepost.PreAuthorize;

                class AdminService {
                    @PreAuthorize("hasRole('ROLE_ADMIN')")
                    void purge() {
                    }
                }
                """);

        assertThat(findings(RULE_INVALID)).isEmpty();
    }

    @Test
    void flagsMatcherRegisteredAfterAnyRequest() throws IOException {
        writeSecurityConfig(
                """
                    @Bean
                    SecurityFilterChain chained(HttpSecurity http) throws Exception {
                        return http.authorizeHttpRequests(auth -> auth
                                        .anyRequest().authenticated()
                                        .requestMatchers("/public/**").permitAll())
                                .build();
                    }

                    @Bean
                    SecurityFilterChain block(HttpSecurity http) throws Exception {
                        http.securityMatcher("/api/**");
                        http.authorizeHttpRequests(auth -> {
                            auth.anyRequest().authenticated();
                            auth.requestMatchers("/api/health").permitAll();
                        });
                        return http.build();
                    }

                    @Bean
                    SecurityFilterChain ordered(HttpSecurity http) throws Exception {
                        http.securityMatcher("/web/**");
                        return http.authorizeHttpRequests(auth -> auth
                                        .requestMatchers("/web/public/**").permitAll()
                                        .anyRequest().authenticated())
                                .build();
                    }
                """);

        List<Finding> findings = findings(RULE_INVALID);

        assertThat(findings)
                .extracting(Finding::target)
                .containsExactlyInAnyOrder("SecurityConfig#chained", "SecurityConfig#block");
        assertThat(findings)
                .allSatisfy(
                        finding ->
                                assertThat(finding.message())
                                        .contains("requestMatchers(...) after anyRequest()"));
    }

    // ── SPRING_SECURITY_FILTER_CHAIN_UNREACHABLE ──────────────────────────────

    private void writeTwoCatchAllChains() throws IOException {
        writeSecurityConfig(
                """
                    @Bean
                    SecurityFilterChain api(HttpSecurity http) throws Exception {
                        return http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                                .build();
                    }

                    @Bean
                    SecurityFilterChain web(HttpSecurity http) throws Exception {
                        return http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                                .formLogin(Customizer.withDefaults())
                                .build();
                    }
                """);
    }

    @Test
    void reportsTwoCatchAllChainsAsStartupFailureFromBoot34() throws IOException {
        writeTwoCatchAllChains();

        assertThat(findings(UNREACHABLE, "3.5.13"))
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.severity()).isEqualTo(FindingSeverity.ERROR);
                            assertThat(finding.message())
                                    .isEqualTo(
                                            "SecurityConfig#api and SecurityConfig#web both match"
                                                    + " every request — Spring Security refuses to"
                                                    + " start.");
                            assertThat(finding.target()).isEqualTo("SecurityConfig#web");
                        });
    }

    @Test
    void reportsTwoCatchAllChainsAsSilentWarningBeforeBoot34() throws IOException {
        writeTwoCatchAllChains();

        assertThat(findings(UNREACHABLE, "3.3.5"))
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.severity()).isEqualTo(FindingSeverity.WARNING);
                            assertThat(finding.message())
                                    .endsWith("the one ordered later never runs.");
                        });
        assertThat(findings(UNREACHABLE, null))
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.severity()).isEqualTo(FindingSeverity.ERROR);
                            assertThat(finding.message())
                                    .contains("never runs, and Spring Security 6.4+");
                        });
    }

    @Test
    void reportsCatchAllChainOrderedBeforeScopedChain() throws IOException {
        writeSecurityConfig(
                """
                    @Bean
                    @Order(1)
                    SecurityFilterChain web(HttpSecurity http) throws Exception {
                        return http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                                .build();
                    }

                    @Bean
                    @Order(2)
                    SecurityFilterChain api(HttpSecurity http) throws Exception {
                        return http.securityMatcher("/api/**")
                                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("API"))
                                .build();
                    }
                """);

        assertThat(findings(UNREACHABLE))
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.target()).isEqualTo("SecurityConfig#web");
                            assertThat(finding.message())
                                    .contains("is ordered before SecurityConfig#api");
                        });
    }

    @Test
    void doesNotReportCorrectlyOrderedOrConditionalChains() throws IOException {
        writeSecurityConfig(
                """
                    @Bean
                    @Order(1)
                    SecurityFilterChain api(HttpSecurity http) throws Exception {
                        return http.securityMatcher("/api/**")
                                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("API"))
                                .build();
                    }

                    @Bean
                    SecurityFilterChain web(HttpSecurity http) throws Exception {
                        return http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                                .build();
                    }

                    @Bean
                    @org.springframework.context.annotation.Profile("local")
                    SecurityFilterChain local(HttpSecurity http) throws Exception {
                        return http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                                .build();
                    }
                """);

        assertThat(findings(UNREACHABLE)).isEmpty();
    }

    // ── False-positive guards of the concatenation and logging rules ──────────

    @Test
    void doesNotReportFixedHostsRelativePathsOrResponseLocationsAsSsrf() throws IOException {
        write(
                "OrderClient.java",
                """
                package com.example;

                import java.net.URI;
                import org.springframework.http.ResponseEntity;
                import org.springframework.web.client.RestTemplate;

                class OrderClient {
                    private final RestTemplate rest = new RestTemplate();

                    String fetch(String id) {
                        return rest.getForObject("https://orders.example.com/api/" + id, String.class);
                    }

                    String relative(String id) {
                        return rest.getForObject("/orders/" + id, String.class);
                    }

                    ResponseEntity<Void> created(String id) {
                        return ResponseEntity.created(URI.create("/orders/" + id)).build();
                    }

                    String open(String host) {
                        return rest.getForObject("https://" + host + "/status", String.class);
                    }

                    String configured(OrderProperties properties, String id) {
                        return rest.getForObject(properties.getBaseUrl() + "/orders/" + id, String.class);
                    }

                    String constant(String id) {
                        return rest.getForObject(ORDERS_URL + "/" + id, String.class);
                    }

                    String endpoint(String serviceEndpoint, String query) {
                        return rest.getForObject(trim(serviceEndpoint) + "?q=" + query, String.class);
                    }

                    String relay(OrderProperties properties, String target) {
                        String base = properties.getRelayBaseUrl().trim();
                        return rest.getForObject(base + "?url=" + target, String.class);
                    }

                    String origin(OrderProperties properties, String path) {
                        String origin = URI.create(properties.getBaseUrl()).resolve("/").toString();
                        return rest.getForObject(origin + path, String.class);
                    }

                    java.net.URI parsed(String value, java.net.URI link) {
                        URI.create("https://website.invalid" + value);
                        return URI.create("https://www.example.com" + link.getRawPath());
                    }

                    private static final String ORDERS_URL = "https://orders.example.com";

                    private static String trim(String value) {
                        return value.strip();
                    }
                }

                class OrderProperties {
                    String getBaseUrl() {
                        return "https://orders.example.com";
                    }

                    String getRelayBaseUrl() {
                        return "https://relay.example.com";
                    }
                }
                """);

        assertThat(findings("SPRING_SSRF_USER_URL"))
                .singleElement()
                .extracting(finding -> finding.primaryLocation().startLine())
                .isEqualTo(23);
    }

    @Test
    void acceptsContextPathRedirectsAndFlagsOpenOnes() throws IOException {
        write(
                "LoginController.java",
                """
                package com.example;

                import jakarta.servlet.http.HttpServletRequest;
                import jakarta.servlet.http.HttpServletResponse;

                class LoginController {
                    void toLogin(HttpServletRequest request, HttpServletResponse response) throws Exception {
                        response.sendRedirect(request.getContextPath() + "/login");
                    }

                    void back(HttpServletResponse response, String target) throws Exception {
                        response.sendRedirect("" + target);
                    }
                }
                """);

        assertThat(findings("SPRING_OPEN_REDIRECT"))
                .singleElement()
                .extracting(finding -> finding.primaryLocation().startLine())
                .isEqualTo(12);
    }

    @Test
    void doesNotRequireHttpOnlyOnCsrfTokenCookie() throws IOException {
        write(
                "CsrfCookieWriter.java",
                """
                package com.example;

                import jakarta.servlet.http.Cookie;
                import jakarta.servlet.http.HttpServletResponse;

                class CsrfCookieWriter {
                    void write(HttpServletResponse response, String token) {
                        Cookie cookie = new Cookie("XSRF-TOKEN", token);
                        cookie.setSecure(true);
                        response.addCookie(cookie);
                    }
                }
                """);

        assertThat(findings("SPRING_COOKIE_MISSING_HTTPONLY")).isEmpty();
    }

    @Test
    void judgesLoggedArgumentsRatherThanMessageText() throws IOException {
        write(
                "TokenAudit.java",
                """
                package com.example;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                class TokenAudit {
                    private static final Logger log = LoggerFactory.getLogger(TokenAudit.class);

                    void quiet(String accessToken, String nextPageToken) {
                        log.info("Access token row missing for re-authorization");
                        log.debug("token present: {}", accessToken != null);
                        log.debug("token masked: {}", mask(accessToken));
                        log.info("fetching page {}", nextPageToken);
                    }

                    void leaky(String accessToken) {
                        log.info("token {}", accessToken);
                    }

                    private static String mask(String value) {
                        return "***";
                    }
                }
                """);

        assertThat(findings("SPRING_LOGGING_AUTH_HEADER"))
                .singleElement()
                .extracting(finding -> finding.primaryLocation().startLine())
                .isEqualTo(17);
    }
}
