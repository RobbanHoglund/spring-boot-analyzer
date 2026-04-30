package com.example.springbootanalyzer;

import com.example.springbootanalyzer.config.AnalyzerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(AnalyzerProperties.class)
@EnableScheduling
public class SpringBootAnalyzerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringBootAnalyzerApplication.class, args);
    }
}
