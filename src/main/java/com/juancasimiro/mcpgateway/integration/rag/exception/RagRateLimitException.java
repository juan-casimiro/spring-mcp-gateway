package com.juancasimiro.mcpgateway.integration.rag.exception;

public final class RagRateLimitException extends RagException {

    private static final String MESSAGE =
            "The research service request limit has been reached. Please try again later.";

    public RagRateLimitException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
