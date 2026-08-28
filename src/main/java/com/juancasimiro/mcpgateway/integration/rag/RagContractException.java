package com.juancasimiro.mcpgateway.integration.rag;

public final class RagContractException extends RagException {

    private static final String MESSAGE =
            "The research service returned an invalid response. This is an internal error; do not retry with the same input.";

    public RagContractException(Throwable cause) {
        super(MESSAGE, cause);
    }

    public RagContractException() {
        super(MESSAGE);
    }
}
