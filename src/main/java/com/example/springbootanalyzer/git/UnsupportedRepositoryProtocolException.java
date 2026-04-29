package com.example.springbootanalyzer.git;

public class UnsupportedRepositoryProtocolException extends RuntimeException {

    public UnsupportedRepositoryProtocolException(String message) {
        super(message);
    }
}
