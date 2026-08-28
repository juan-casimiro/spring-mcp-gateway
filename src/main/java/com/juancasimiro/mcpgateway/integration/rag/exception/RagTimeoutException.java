package com.juancasimiro.mcpgateway.integration.rag.exception;

public final class RagTimeoutException extends RagException {

    private static final String MESSAGE =
            "The research request timed out. Do not answer from general knowledge; tell the user that retrieval did not complete.";

    public RagTimeoutException(Throwable cause) {
        super(MESSAGE, cause);
    }

    public RagTimeoutException() {
        super(MESSAGE);
    }
}
