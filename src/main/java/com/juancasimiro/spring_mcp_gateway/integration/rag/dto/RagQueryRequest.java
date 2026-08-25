package com.juancasimiro.spring_mcp_gateway.integration.rag.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RagQueryRequest(
        String question,
        @JsonProperty("n_results")
        int nResults) {
}
