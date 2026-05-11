package com.robbanhoglund.springbootanalyzer;

import com.robbanhoglund.springbootanalyzer.config.AnalyzerProperties;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point for the Spring Boot Analyzer service.
 *
 * <p>{@code @EnableConfigurationProperties} binds {@link AnalyzerProperties} from the
 * {@code analyzer.*} namespace.
 *
 * <p>When {@code --repo} is present in the command-line arguments the application starts
 * in CLI mode: the embedded web server is disabled, the {@code cli} Spring profile is
 * activated, and {@link com.robbanhoglund.springbootanalyzer.cli.CliRunner} drives
 * execution. In all other cases the application behaves as a normal Spring Boot web
 * service.
 */
@SpringBootApplication
@EnableConfigurationProperties(AnalyzerProperties.class)
public class SpringBootAnalyzerApplication {

    public static void main(String[] args) {
        boolean cliMode =
                Arrays.stream(args).anyMatch(a -> a.startsWith("--repo=") || a.equals("--repo"));
        if (cliMode) {
            SpringApplication app = new SpringApplication(SpringBootAnalyzerApplication.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            app.setAdditionalProfiles("cli");
            app.run(args);
        } else {
            SpringApplication.run(SpringBootAnalyzerApplication.class, args);
        }
    }
}
