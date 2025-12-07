package com.sgr.ai.common;

public class AgentExecutionException extends RuntimeException {
    private final int statusCode;
    private final boolean isRetryable;

    public AgentExecutionException(String message, int statusCode, boolean isRetryable, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.isRetryable = isRetryable;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public boolean isRetryable() {
        return isRetryable;
    }
}