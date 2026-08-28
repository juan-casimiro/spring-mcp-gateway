package com.juancasimiro.mcpgateway.integration.rag;

public abstract class RagException extends RuntimeException {

    protected RagException(String message) {
        super(message);
    }

    protected RagException(String message, Throwable cause) {
        super(message, cause);
    }
}
