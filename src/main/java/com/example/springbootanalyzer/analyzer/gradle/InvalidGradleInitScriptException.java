package com.example.springbootanalyzer.analyzer.gradle;

import java.io.IOException;

public class InvalidGradleInitScriptException extends IOException {

    public InvalidGradleInitScriptException(String message) {
        super(message);
    }
}
