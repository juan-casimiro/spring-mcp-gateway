package com.juancasimiro.mcpgateway.integration.rag.exception;

public final class RagCircuitOpenException extends RagException {

    private static final String MESSAGE =
            "The research service is temporarily unavailable because the circuit breaker is open.";

    public RagCircuitOpenException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
