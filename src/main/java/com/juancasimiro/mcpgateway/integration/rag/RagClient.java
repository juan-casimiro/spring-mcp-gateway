package com.juancasimiro.mcpgateway.integration.rag;

import com.juancasimiro.mcpgateway.application.research.ResearchAnswer;
import com.juancasimiro.mcpgateway.application.research.ResearchGateway;
import com.juancasimiro.mcpgateway.application.research.ResearchQuestion;
import com.juancasimiro.mcpgateway.integration.rag.dto.RagQueryRequest;
import com.juancasimiro.mcpgateway.integration.rag.dto.RagQueryResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RagClient implements ResearchGateway {

    private final RestClient ragRestClient;

    public RagClient(@Qualifier("ragRestClient") RestClient ragRestClient) {
        this.ragRestClient = ragRestClient;
    }

    @Override
    public ResearchAnswer query(ResearchQuestion question) {
        RagQueryRequest request = toRequest(question);

        RagQueryResponse response = ragRestClient.post()
                .uri("/query")
                .body(request)
                .retrieve()
                .body(RagQueryResponse.class);

        // Full upstream failure handling is Epic C (JUA-57).
        if (response == null) {
            throw new IllegalStateException("RAG service returned an empty response body");
        }

        return toResearchAnswer(response);
    }

    private RagQueryRequest toRequest(ResearchQuestion question) {
        return new RagQueryRequest(
                question.question(),
                question.resultCount(),
                false,
                false
        );
    }

    private ResearchAnswer toResearchAnswer(RagQueryResponse response) {
        return new ResearchAnswer(
                response.answer(),
                response.sources(),
                response.contextSufficient(),
                response.insufficiencyReason()
        );
    }
}
