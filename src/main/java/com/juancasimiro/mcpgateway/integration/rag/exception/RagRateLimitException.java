package com.juancasimiro.mcpgateway.integration.rag.exception;

public final class RagRateLimitException extends RagException {

    private static final String MESSAGE =
            "The research service request limit has been reached. Please try again later.";

    // Deliberately no cause: the MCP layer appends the cause message to the tool error,
    // which would leak the Resilience4j limiter diagnostic to callers.
    public RagRateLimitException() {
        super(MESSAGE);
    }
}
