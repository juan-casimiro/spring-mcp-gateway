package com.juancasimiro.spring_mcp_gateway.services;

import com.juancasimiro.spring_mcp_gateway.model.RagQueryRequest;
import com.juancasimiro.spring_mcp_gateway.model.RagQueryResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RagClient {

    private final RestClient ragRestClient;

    public RagClient(@Qualifier("ragRestClient") RestClient ragRestClient) {
        this.ragRestClient = ragRestClient;
    }

    public RagQueryResponse query(RagQueryRequest ragQueryRequest) {
        return ragRestClient.post()
                .uri("/query")
                .body(ragQueryRequest)
                .retrieve()
                .body(RagQueryResponse.class);
    }
}
