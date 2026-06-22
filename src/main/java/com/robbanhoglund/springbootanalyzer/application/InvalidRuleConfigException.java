package com.robbanhoglund.springbootanalyzer.application;

public class InvalidRuleConfigException extends RuntimeException {

    public InvalidRuleConfigException(String message) {
        super(message);
    }
}
