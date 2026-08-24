package com.juancasimiro.spring_mcp_gateway.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RagQueryRequest(
        String question,
        @JsonProperty("n_results")
        int nResults){}
