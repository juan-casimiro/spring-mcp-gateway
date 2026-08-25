package com.juancasimiro.spring_mcp_gateway.integration.rag;

import com.juancasimiro.spring_mcp_gateway.application.research.ResearchAnswer;
import com.juancasimiro.spring_mcp_gateway.application.research.ResearchGateway;
import com.juancasimiro.spring_mcp_gateway.application.research.ResearchQuestion;
import com.juancasimiro.spring_mcp_gateway.integration.rag.dto.RagQueryRequest;
import com.juancasimiro.spring_mcp_gateway.integration.rag.dto.RagQueryResponse;
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
