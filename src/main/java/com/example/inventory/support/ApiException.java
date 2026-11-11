package com.example.inventory.support;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String messageKey;
    private final Map<String, Object> parameters;
    private final String code;

    public ApiException(HttpStatus status, String messageKey) {
        this(status, messageKey, Map.of(), null);
    }

    public ApiException(HttpStatus status, String messageKey, Map<String, Object> parameters) {
        this(status, messageKey, parameters, null);
    }

    public ApiException(
            HttpStatus status,
            String messageKey,
            Map<String, Object> parameters,
            String code) {
        super(messageKey);
        this.status = status;
        this.messageKey = messageKey;
        this.parameters = parameters;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public String getCode() {
        return code;
    }
}
