package com.juancasimiro.mcpgateway.integration.rag.exception;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

public final class RagCircuitOpenException extends RagException {

    private static final String MESSAGE =
            "The research service is temporarily unavailable because the circuit breaker is open.";

    public RagCircuitOpenException(CallNotPermittedException cause) {
        super(MESSAGE, cause);
    }
}
