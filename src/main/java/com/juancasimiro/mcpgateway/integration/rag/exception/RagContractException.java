package com.juancasimiro.mcpgateway.integration.rag.exception;

public final class RagContractException extends RagException {

    private static final String MESSAGE =
            "The research service could not process this request. This is an internal error; do not retry with the same input.";

    public RagContractException(Throwable cause) {
        super(MESSAGE, cause);
    }

    public RagContractException() {
        super(MESSAGE);
    }
}
