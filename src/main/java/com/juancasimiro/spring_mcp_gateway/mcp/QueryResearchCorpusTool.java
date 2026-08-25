package com.juancasimiro.spring_mcp_gateway.mcp;

import com.juancasimiro.spring_mcp_gateway.application.research.ResearchAnswer;
import com.juancasimiro.spring_mcp_gateway.application.research.ResearchGateway;
import com.juancasimiro.spring_mcp_gateway.application.research.ResearchQuestion;
import com.juancasimiro.spring_mcp_gateway.mcp.model.QueryResearchCorpusResponse;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class QueryResearchCorpusTool {

    private final ResearchGateway researchGateway;

    public QueryResearchCorpusTool(ResearchGateway researchGateway) {
        this.researchGateway = researchGateway;
    }

    @McpTool(
            name = "query_research_corpus",
            description = "Searches the biomedical research corpus and answers questions using retrieved evidence. Returns the answer and supporting sources."
    )
    public QueryResearchCorpusResponse query(
            @McpToolParam(
                    description = "Question to answer using the biomedical research corpus",
                    required = true
            )
            String question,

            @McpToolParam(
                    description = "Maximum number of retrieved chunks to use",
                    required = false
            )
            Integer resultCount) {

        int effectiveResultCount = resultCount != null ? resultCount : 8;

        ResearchAnswer answer = researchGateway.query(
                new ResearchQuestion(question, effectiveResultCount)
        );

        return toResponse(answer);
    }

    private QueryResearchCorpusResponse toResponse(ResearchAnswer answer) {
        return new QueryResearchCorpusResponse(
                answer.answer(),
                answer.sources(),
                answer.contextSufficient(),
                answer.insufficiencyReason()
        );
    }
}
