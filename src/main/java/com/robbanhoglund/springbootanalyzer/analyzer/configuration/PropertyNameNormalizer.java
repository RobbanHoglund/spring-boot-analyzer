package com.robbanhoglund.springbootanalyzer.analyzer.configuration;

import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class PropertyNameNormalizer {

    public String normalize(String propertyName) {
        if (propertyName == null) {
            return "";
        }
        return propertyName.trim().toLowerCase(Locale.ROOT);
    }

    public String toKebabCase(String javaName) {
        if (javaName == null || javaName.isBlank()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        char[] chars = javaName.trim().toCharArray();
        for (int index = 0; index < chars.length; index++) {
            char current = chars[index];
            if (Character.isUpperCase(current)) {
                if (index > 0 && builder.charAt(builder.length() - 1) != '-') {
                    builder.append('-');
                }
                builder.append(Character.toLowerCase(current));
            } else if (current == '_' || current == ' ') {
                if (!builder.isEmpty() && builder.charAt(builder.length() - 1) != '-') {
                    builder.append('-');
                }
            } else {
                builder.append(Character.toLowerCase(current));
            }
        }
        return builder.toString();
    }
}
