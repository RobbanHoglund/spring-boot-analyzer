package com.robbanhoglund.springbootanalyzer.analyzer.model.messaging;

import java.util.List;

public record MessageListenerEndpoint(
        String listenerType,
        List<String> destinations,
        String groupId,
        String className,
        String methodName,
        String sourceFile,
        Integer line) {}
