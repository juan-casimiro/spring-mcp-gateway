package com.juancasimiro.mcpgateway.integration.rag.exception;

public final class RagUnavailableException extends RagException {

    private static final String MESSAGE =
            "The research corpus is currently unavailable. Do not answer from general knowledge; tell the user that retrieval failed.";

    public RagUnavailableException(Throwable cause) {
        super(MESSAGE, cause);
    }

    public RagUnavailableException() {
        super(MESSAGE);
    }
}
