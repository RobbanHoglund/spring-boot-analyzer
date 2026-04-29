package com.robbanhoglund.springbootanalyzer.analyzer.gradle;

import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class GradleScriptValueRenderer {

    public String groovyStringLiteral(String value) {
        if (value == null) {
            return "\"\"";
        }
        String escaped = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("$", "\\$")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
        return "\"" + escaped + "\"";
    }

    public String groovyUriLiteral(Path path) {
        return groovyStringLiteral(path.toUri().toASCIIString());
    }

    public String groovyFileUriLiteral(Path path) {
        return groovyUriLiteral(path);
    }
}
