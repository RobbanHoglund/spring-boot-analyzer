package com.robbanhoglund.springbootanalyzer.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.JavaParser;
import com.robbanhoglund.springbootanalyzer.analyzer.configuration.ConfigurationPropertiesClassAnalyzer;
import com.robbanhoglund.springbootanalyzer.analyzer.configuration.PropertyReferenceAnalyzer;
import com.robbanhoglund.springbootanalyzer.analyzer.http.HttpSurfaceAnalyzer;
import com.robbanhoglund.springbootanalyzer.analyzer.runtime.RuntimeStackAnalyzer;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaParserConcurrencyRegressionTest {

    private static final List<Class<?>> SINGLETON_ANALYZERS =
            List.of(
                    StaticPracticeFindingAnalyzer.class,
                    HttpSurfaceAnalyzer.class,
                    JavaSourceAnalyzer.class,
                    RuntimeStackAnalyzer.class,
                    TestingPracticeFindingAnalyzer.class,
                    ConfigurationPropertiesClassAnalyzer.class,
                    PropertyReferenceAnalyzer.class);

    @TempDir Path tempDir;

    @Test
    void singletonAnalyzersDoNotRetainMutableJavaParserInstances() {
        assertThat(SINGLETON_ANALYZERS)
                .allSatisfy(
                        analyzerClass ->
                                assertThat(analyzerClass.getDeclaredFields())
                                        .extracting(Field::getType)
                                        .doesNotContain(JavaParser.class));
    }

    @Test
    void parallelAnalysesOnSameInstanceMatchSerialResults() throws Exception {
        Path left = writeFixture("left", "AlphaService", "Service");
        Path right = writeFixture("right", "BetaController", "RestController");
        JavaSourceAnalyzer analyzer = new JavaSourceAnalyzer();
        JavaSourceAnalyzer.SourceAnalysis expectedLeft = analyzer.analyze(left);
        JavaSourceAnalyzer.SourceAnalysis expectedRight = analyzer.analyze(right);
        CyclicBarrier startTogether = new CyclicBarrier(2);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var leftFuture =
                    executor.submit(
                            () -> {
                                startTogether.await();
                                return analyzer.analyze(left);
                            });
            var rightFuture =
                    executor.submit(
                            () -> {
                                startTogether.await();
                                return analyzer.analyze(right);
                            });

            assertThat(leftFuture.get(30, TimeUnit.SECONDS)).isEqualTo(expectedLeft);
            assertThat(rightFuture.get(30, TimeUnit.SECONDS)).isEqualTo(expectedRight);
        }
    }

    private Path writeFixture(String directory, String className, String annotation)
            throws Exception {
        Path root = tempDir.resolve(directory);
        Path sourceRoot = Files.createDirectories(root.resolve("src/main/java/com/example"));
        Files.writeString(
                sourceRoot.resolve(className + ".java"),
                """
                package com.example;
                import org.springframework.stereotype.%s;
                @%s
                class %s {}
                """
                        .formatted(annotation, annotation, className));
        return root;
    }
}
