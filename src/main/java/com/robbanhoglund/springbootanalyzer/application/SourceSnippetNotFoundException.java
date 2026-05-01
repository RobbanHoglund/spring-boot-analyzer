package com.robbanhoglund.springbootanalyzer.application;

public class SourceSnippetNotFoundException extends RuntimeException {

    public SourceSnippetNotFoundException(String message) {
        super(message);
    }
}
