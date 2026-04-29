package com.robbanhoglund.springbootanalyzer.git;

public class GitCloneException extends RuntimeException {

    public GitCloneException(String message, Throwable cause) {
        super(message, cause);
    }
}
