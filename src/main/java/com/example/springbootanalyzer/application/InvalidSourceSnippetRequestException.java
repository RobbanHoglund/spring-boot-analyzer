package com.example.springbootanalyzer.application;

public class InvalidSourceSnippetRequestException extends RuntimeException {

    public InvalidSourceSnippetRequestException(String message) {
        super(message);
    }
}
