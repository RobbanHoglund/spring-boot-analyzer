package com.robbanhoglund.springbootanalyzer.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildInfo;
import com.robbanhoglund.springbootanalyzer.analyzer.model.BuildTool;
import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.source.JavaSources;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CachingPracticeFindingAnalyzerTest {

    @TempDir Path repoRoot;

    private CachingPracticeFindingAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new CachingPracticeFindingAnalyzer();
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

    private List<Finding> findingsWithDependencies(String... dependencies) {
        BuildInfo buildInfo =
                new BuildInfo(
                        BuildTool.GRADLE,
                        true,
                        "21",
                        List.of(dependencies),
                        "3.5.13",
                        "build.gradle plugin",
                        "HIGH");
        // Parse the configuration the way the pipeline does, so YAML is flattened.
        var configuration =
                new com.robbanhoglund.springbootanalyzer.analyzer.configuration
                                .ConfigurationAnalyzer(
                                new com.robbanhoglund.springbootanalyzer.analyzer.configuration
                                        .ConfigurationFileScanner(),
                                new com.robbanhoglund.springbootanalyzer.analyzer.configuration
                                        .PropertiesFileParser(),
                                new com.robbanhoglund.springbootanalyzer.analyzer.configuration
                                        .YamlConfigurationParser(),
                                new com.robbanhoglund.springbootanalyzer.analyzer.configuration
                                        .SpringConfigurationMetadataCatalog(),
                                new com.robbanhoglund.springbootanalyzer.analyzer.configuration
                                        .ConfigurationPropertiesClassAnalyzer(
                                        new com.robbanhoglund.springbootanalyzer.analyzer
                                                .configuration.PropertyNameNormalizer()),
                                new com.robbanhoglund.springbootanalyzer.analyzer.configuration
                                        .PropertyReferenceAnalyzer(
                                        new com.robbanhoglund.springbootanalyzer.analyzer
                                                .configuration.PropertyNameNormalizer()),
                                new com.robbanhoglund.springbootanalyzer.analyzer.configuration
                                        .SensitivePropertyValueRedactor(),
                                new com.robbanhoglund.springbootanalyzer.analyzer.configuration
                                        .PropertyNameNormalizer())
                        .analyze(repoRoot, buildInfo)
                        .configurationAnalysis();
        return analyzer.analyze(JavaSources.from(repoRoot), buildInfo, configuration);
    }

    private void writeCatalogService() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/CatalogService.java",
                """
                package com.example;
                import org.springframework.cache.annotation.Cacheable;
                public class CatalogService {
                    @Cacheable("catalog")
                    public String name(long id) { return "n" + id; }
                }
                """);
    }

    private static Finding byRule(List<Finding> findings, String ruleId) {
        return findings.stream().filter(f -> ruleId.equals(f.ruleId())).findFirst().orElse(null);
    }

    // ── No sources ────────────────────────────────────────────────────────────

    @Test
    void returnsEmptyListWhenNoMainDirectory() {
        assertThat(findings()).isEmpty();
    }

    // ── SPRING_CACHEABLE_VOID_RETURN ──────────────────────────────────────────

    @Test
    void flagsCacheableOnVoidMethod() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ProductService.java",
                """
                package com.example;
                import org.springframework.cache.annotation.Cacheable;
                public class ProductService {
                    @Cacheable("products")
                    public void warmCache() {}
                }
                """);

        Finding f = byRule(findings(), "SPRING_CACHEABLE_VOID_RETURN");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("ProductService#warmCache");
        assertThat(f.message()).contains("void");
    }

    @Test
    void doesNotFlagCacheableOnNonVoidMethod() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ProductService.java",
                """
                package com.example;
                import org.springframework.cache.annotation.Cacheable;
                public class ProductService {
                    @Cacheable("products")
                    public String getProduct(Long id) { return ""; }
                }
                """);

        assertThat(byRule(findings(), "SPRING_CACHEABLE_VOID_RETURN")).isNull();
    }

    // ── SPRING_CACHEABLE_MUTABLE_RETURN_TYPE ──────────────────────────────────

    @Test
    void flagsCacheableReturningList() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ProductService.java",
                """
                package com.example;
                import org.springframework.cache.annotation.Cacheable;
                import java.util.List;
                public class ProductService {
                    @Cacheable("products")
                    public List<String> getAll() { return new java.util.ArrayList<>(); }
                }
                """);

        Finding f = byRule(findings(), "SPRING_CACHEABLE_MUTABLE_RETURN_TYPE");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("List");
        assertThat(f.target()).isEqualTo("ProductService#getAll");
    }

    @Test
    void flagsCacheableReturningMap() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/CatalogService.java",
                """
                package com.example;
                import org.springframework.cache.annotation.Cacheable;
                import java.util.Map;
                public class CatalogService {
                    @Cacheable("catalog")
                    public Map<String, String> getIndex() { return new java.util.HashMap<>(); }
                }
                """);

        Finding f = byRule(findings(), "SPRING_CACHEABLE_MUTABLE_RETURN_TYPE");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("Map");
    }

    @Test
    void doesNotFlagCacheableReturningString() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ProductService.java",
                """
                package com.example;
                import org.springframework.cache.annotation.Cacheable;
                public class ProductService {
                    @Cacheable("products")
                    public String getById(Long id) { return ""; }
                }
                """);

        assertThat(byRule(findings(), "SPRING_CACHEABLE_MUTABLE_RETURN_TYPE")).isNull();
    }

    @Test
    void doesNotFlagMutableReturnTypeWhenEveryReturnIsUnmodifiable() throws IOException {
        // The rule's own advice (List.copyOf, Stream.toList, ...) must clear the finding.
        writeSourceFile(
                "src/main/java/com/example/CatalogService.java",
                """
                package com.example;
                import java.util.List;
                import java.util.Map;
                import java.util.stream.Collectors;
                import org.springframework.cache.annotation.Cacheable;
                public class CatalogService {
                    @Cacheable("names")
                    public List<String> names(boolean all) {
                        return all ? List.copyOf(load()) : List.of();
                    }
                    @Cacheable("codes")
                    public List<String> codes() { return load().stream().toList(); }
                    @Cacheable("index")
                    public Map<String, Integer> index() {
                        return load().stream()
                                .collect(Collectors.toUnmodifiableMap(n -> n, String::length));
                    }
                    private List<String> load() { return List.of("a", "b"); }
                }
                """);

        assertThat(byRule(findings(), "SPRING_CACHEABLE_MUTABLE_RETURN_TYPE")).isNull();
    }

    @Test
    void stillFlagsMutableReturnTypeWhenOneReturnIsMutable() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/CatalogService.java",
                """
                package com.example;
                import java.util.ArrayList;
                import java.util.List;
                import org.springframework.cache.annotation.Cacheable;
                public class CatalogService {
                    @Cacheable("names")
                    public List<String> names(boolean all) {
                        if (all) {
                            return new ArrayList<>(List.of("a"));
                        }
                        return List.of();
                    }
                }
                """);

        assertThat(byRule(findings(), "SPRING_CACHEABLE_MUTABLE_RETURN_TYPE")).isNotNull();
    }

    // ── SPRING_CACHEABLE_NO_TTL_PROVIDER ──────────────────────────────────────

    @Test
    void doesNotFlagNoTtlProviderWhenJCacheIsOnTheClasspath() throws IOException {
        // Spring Boot auto-configures JCache from the classpath; no property is needed.
        writeCatalogService();

        List<Finding> findings =
                findingsWithDependencies(
                        "org.springframework.boot:spring-boot-starter-cache",
                        "javax.cache:cache-api",
                        "com.github.ben-manes.caffeine:caffeine");

        assertThat(byRule(findings, "SPRING_CACHEABLE_NO_TTL_PROVIDER")).isNull();
    }

    @Test
    void flagsNoTtlProviderAtTheFirstCacheableMethod() throws IOException {
        writeCatalogService();

        Finding f =
                byRule(
                        findingsWithDependencies(
                                "org.springframework.boot:spring-boot-starter-cache"),
                        "SPRING_CACHEABLE_NO_TTL_PROVIDER");
        assertThat(f).isNotNull();
        assertThat(f.sourceFile()).isEqualTo("src/main/java/com/example/CatalogService.java");
        assertThat(f.line()).isEqualTo(4);
        assertThat(f.target()).isEqualTo("CatalogService#name");
    }

    @Test
    void flagsNoTtlProviderWhenTheSimpleCacheIsForced() throws IOException {
        // spring.cache.type=simple overrides whatever provider the classpath offers.
        writeCatalogService();
        writeSourceFile("src/main/resources/application.properties", "spring.cache.type=simple\n");

        Finding f =
                byRule(
                        findingsWithDependencies("com.github.ben-manes.caffeine:caffeine"),
                        "SPRING_CACHEABLE_NO_TTL_PROVIDER");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("spring.cache.type=simple");
    }

    @Test
    void doesNotFlagNoTtlProviderWhenCachingIsDisabled() throws IOException {
        writeCatalogService();
        writeSourceFile(
                "src/main/resources/application.yml", "spring:\n  cache:\n    type: none\n");

        assertThat(byRule(findingsWithDependencies(), "SPRING_CACHEABLE_NO_TTL_PROVIDER")).isNull();
    }

    // ── SPRING_CACHE_ON_PRIVATE_METHOD ────────────────────────────────────────

    @Test
    void flagsCacheableOnPrivateMethod() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ProductService.java",
                """
                package com.example;
                import org.springframework.cache.annotation.Cacheable;
                public class ProductService {
                    @Cacheable("products")
                    private String loadProduct(Long id) { return ""; }
                }
                """);

        Finding f = byRule(findings(), "SPRING_CACHE_ON_PRIVATE_METHOD");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("ProductService#loadProduct");
        assertThat(f.message()).contains("private");
    }

    @Test
    void flagsCacheEvictOnPrivateMethod() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ProductService.java",
                """
                package com.example;
                import org.springframework.cache.annotation.CacheEvict;
                public class ProductService {
                    @CacheEvict("products")
                    private void evict(Long id) {}
                }
                """);

        Finding f = byRule(findings(), "SPRING_CACHE_ON_PRIVATE_METHOD");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("CacheEvict");
    }

    @Test
    void doesNotFlagCacheableOnPublicMethod() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ProductService.java",
                """
                package com.example;
                import org.springframework.cache.annotation.Cacheable;
                public class ProductService {
                    @Cacheable("products")
                    public String getById(Long id) { return ""; }
                }
                """);

        assertThat(byRule(findings(), "SPRING_CACHE_ON_PRIVATE_METHOD")).isNull();
    }

    // ── SPRING_CACHE_SELF_INVOCATION ──────────────────────────────────────────

    @Test
    void flagsSelfInvocationOfCachedMethod() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ProductService.java",
                """
                package com.example;
                import org.springframework.cache.annotation.Cacheable;
                public class ProductService {
                    @Cacheable("products")
                    public String getById(Long id) { return ""; }

                    public String getByIdWrapped(Long id) {
                        return getById(id);
                    }
                }
                """);

        Finding f = byRule(findings(), "SPRING_CACHE_SELF_INVOCATION");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("getById");
        assertThat(f.target()).isEqualTo("ProductService#getByIdWrapped");
    }

    @Test
    void flagsExplicitThisSelfInvocationOfCachedMethod() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ProductService.java",
                """
                package com.example;
                import org.springframework.cache.annotation.Cacheable;
                public class ProductService {
                    @Cacheable("products")
                    public String getById(Long id) { return ""; }

                    public String load(Long id) {
                        return this.getById(id);
                    }
                }
                """);

        Finding f = byRule(findings(), "SPRING_CACHE_SELF_INVOCATION");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("getById");
    }

    @Test
    void doesNotFlagClassWithNoCachedMethods() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ProductService.java",
                """
                package com.example;
                public class ProductService {
                    public String getById(Long id) { return ""; }
                    public String load(Long id) { return getById(id); }
                }
                """);

        assertThat(byRule(findings(), "SPRING_CACHE_SELF_INVOCATION")).isNull();
    }

    // ── SPRING_CACHE_EVICT_WITHOUT_ALL_ENTRIES ────────────────────────────────

    @Test
    void flagsCacheEvictOnNoArgMethodWithoutAllEntries() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/CacheManager.java",
                """
                package com.example;
                import org.springframework.cache.annotation.CacheEvict;
                public class CacheManager {
                    @CacheEvict("products")
                    public void clearCache() {}
                }
                """);

        Finding f = byRule(findings(), "SPRING_CACHE_EVICT_WITHOUT_ALL_ENTRIES");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("CacheManager#clearCache");
        assertThat(f.recommendation()).contains("allEntries");
    }

    @Test
    void doesNotFlagCacheEvictWithAllEntriesTrue() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/CacheManager.java",
                """
                package com.example;
                import org.springframework.cache.annotation.CacheEvict;
                public class CacheManager {
                    @CacheEvict(value = "products", allEntries = true)
                    public void clearCache() {}
                }
                """);

        assertThat(byRule(findings(), "SPRING_CACHE_EVICT_WITHOUT_ALL_ENTRIES")).isNull();
    }

    @Test
    void doesNotFlagCacheEvictOnMethodWithParameters() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/CacheManager.java",
                """
                package com.example;
                import org.springframework.cache.annotation.CacheEvict;
                public class CacheManager {
                    @CacheEvict("products")
                    public void evictById(Long id) {}
                }
                """);

        assertThat(byRule(findings(), "SPRING_CACHE_EVICT_WITHOUT_ALL_ENTRIES")).isNull();
    }

    // ── SPRING_CACHEABLE_SYNC_INCOMPATIBLE ────────────────────────────────────

    @Test
    void flagsCacheableSyncTrueWithUnless() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ProductService.java",
                """
                package com.example;
                import org.springframework.cache.annotation.Cacheable;
                public class ProductService {
                    @Cacheable(value = "products", sync = true, unless = "#result == null")
                    public String getById(Long id) { return ""; }
                }
                """);

        Finding f = byRule(findings(), "SPRING_CACHEABLE_SYNC_INCOMPATIBLE");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("ProductService#getById");
        assertThat(f.message()).contains("unless");
    }

    @Test
    void flagsCacheableSyncTrueWithMultipleCacheNames() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ProductService.java",
                """
                package com.example;
                import org.springframework.cache.annotation.Cacheable;
                public class ProductService {
                    @Cacheable(value = {"products", "catalog"}, sync = true)
                    public String getById(Long id) { return ""; }
                }
                """);

        Finding f = byRule(findings(), "SPRING_CACHEABLE_SYNC_INCOMPATIBLE");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("ProductService#getById");
        assertThat(f.message()).contains("multiple cache names");
    }

    @Test
    void flagsCacheableSyncTrueWithCacheNamesAndUnless() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ProductService.java",
                """
                package com.example;
                import org.springframework.cache.annotation.Cacheable;
                public class ProductService {
                    @Cacheable(cacheNames = {"a", "b"}, sync = true, unless = "#result == null")
                    public String getById(Long id) { return ""; }
                }
                """);

        Finding f = byRule(findings(), "SPRING_CACHEABLE_SYNC_INCOMPATIBLE");
        assertThat(f).isNotNull();
        assertThat(f.message()).contains("unless").contains("multiple cache names");
    }

    @Test
    void doesNotFlagCacheableSyncTrueWithSingleCacheAndNoUnless() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ProductService.java",
                """
                package com.example;
                import org.springframework.cache.annotation.Cacheable;
                public class ProductService {
                    @Cacheable(value = "products", sync = true)
                    public String getById(Long id) { return ""; }
                }
                """);

        assertThat(byRule(findings(), "SPRING_CACHEABLE_SYNC_INCOMPATIBLE")).isNull();
    }

    @Test
    void doesNotFlagCacheableSyncFalseWithMultipleCaches() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ProductService.java",
                """
                package com.example;
                import org.springframework.cache.annotation.Cacheable;
                public class ProductService {
                    @Cacheable(value = {"products", "catalog"}, sync = false)
                    public String getById(Long id) { return ""; }
                }
                """);

        assertThat(byRule(findings(), "SPRING_CACHEABLE_SYNC_INCOMPATIBLE")).isNull();
    }

    @Test
    void doesNotFlagCacheableWithoutSyncAttribute() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ProductService.java",
                """
                package com.example;
                import org.springframework.cache.annotation.Cacheable;
                public class ProductService {
                    @Cacheable(value = {"products", "catalog"}, unless = "#result == null")
                    public String getById(Long id) { return ""; }
                }
                """);

        assertThat(byRule(findings(), "SPRING_CACHEABLE_SYNC_INCOMPATIBLE")).isNull();
    }

    // ── SPRING_CACHEPUT_AND_CACHEABLE_SAME_METHOD ─────────────────────────────

    @Test
    void flagsCachePutAndCacheableOnSameMethod() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ProductService.java",
                """
                package com.example;
                import org.springframework.cache.annotation.Cacheable;
                import org.springframework.cache.annotation.CachePut;
                public class ProductService {
                    @Cacheable("products")
                    @CachePut("products")
                    public String getAndUpdate(Long id) { return ""; }
                }
                """);

        Finding f = byRule(findings(), "SPRING_CACHEPUT_AND_CACHEABLE_SAME_METHOD");
        assertThat(f).isNotNull();
        assertThat(f.target()).isEqualTo("ProductService#getAndUpdate");
        assertThat(f.message()).contains("CachePut").contains("Cacheable");
    }

    @Test
    void doesNotFlagMethodWithOnlyCacheable() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ProductService.java",
                """
                package com.example;
                import org.springframework.cache.annotation.Cacheable;
                public class ProductService {
                    @Cacheable("products")
                    public String getById(Long id) { return ""; }
                }
                """);

        assertThat(byRule(findings(), "SPRING_CACHEPUT_AND_CACHEABLE_SAME_METHOD")).isNull();
    }

    @Test
    void doesNotFlagMethodWithOnlyCachePut() throws IOException {
        writeSourceFile(
                "src/main/java/com/example/ProductService.java",
                """
                package com.example;
                import org.springframework.cache.annotation.CachePut;
                public class ProductService {
                    @CachePut("products")
                    public String update(Long id) { return ""; }
                }
                """);

        assertThat(byRule(findings(), "SPRING_CACHEPUT_AND_CACHEABLE_SAME_METHOD")).isNull();
    }
}
