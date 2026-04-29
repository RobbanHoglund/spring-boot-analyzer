package com.example.springbootanalyzer.git;

public class InvalidRepositoryReferenceException extends RuntimeException {

    public InvalidRepositoryReferenceException(String message) {
        super(message);
    }
}
