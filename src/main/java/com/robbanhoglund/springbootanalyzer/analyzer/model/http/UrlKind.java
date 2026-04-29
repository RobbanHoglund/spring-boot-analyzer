package com.robbanhoglund.springbootanalyzer.analyzer.model.http;

public enum UrlKind {
    HTTP_URL,
    JDBC_URL,
    MAIL_HOST,
    MESSAGE_BROKER_URL,
    REDIS_HOST,
    OAUTH_OIDC_URL,
    OBSERVABILITY_ENDPOINT,
    PROPERTY_REFERENCE,
    OTHER
}
