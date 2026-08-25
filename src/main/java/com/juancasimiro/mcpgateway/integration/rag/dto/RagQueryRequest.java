package com.juancasimiro.mcpgateway.integration.rag.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RagQueryRequest(
        String question,
        @JsonProperty("n_results")
        int nResults,
        @JsonProperty("use_bm25")
        boolean useBm25,
        @JsonProperty("use_query_rewriting")
        boolean useQueryRewriting) {
}
