package com.robbanhoglund.springbootanalyzer.analyzer.model.messaging;

import java.util.List;

public record MessagingAnalysis(List<MessageListenerEndpoint> listeners) {
    public static MessagingAnalysis empty() {
        return new MessagingAnalysis(List.of());
    }
}
