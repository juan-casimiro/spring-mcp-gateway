package com.juancasimiro.spring_mcp_gateway.integration.rag.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record RagQueryResponse(
        String answer,
        List<String> sources,
        @JsonProperty("context_sufficient")
        boolean contextSufficient,
        @JsonProperty("insufficiency_reason")
        String insufficiencyReason) {
}
