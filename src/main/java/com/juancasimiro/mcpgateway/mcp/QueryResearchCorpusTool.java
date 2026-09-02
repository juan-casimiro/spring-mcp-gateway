package com.juancasimiro.mcpgateway.mcp;

import com.juancasimiro.mcpgateway.application.research.ResearchAnswer;
import com.juancasimiro.mcpgateway.application.research.ResearchGateway;
import com.juancasimiro.mcpgateway.application.research.ResearchQuestion;
import com.juancasimiro.mcpgateway.application.research.exception.InvalidResearchQuestionException;
import com.juancasimiro.mcpgateway.integration.rag.exception.RagCircuitOpenException;
import com.juancasimiro.mcpgateway.integration.rag.exception.RagContractException;
import com.juancasimiro.mcpgateway.integration.rag.exception.RagTimeoutException;
import com.juancasimiro.mcpgateway.integration.rag.exception.RagUnavailableException;
import com.juancasimiro.mcpgateway.mcp.model.QueryResearchCorpusResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class QueryResearchCorpusTool {

    private static final Logger LOGGER = LoggerFactory.getLogger(QueryResearchCorpusTool.class);
    private static final int DEFAULT_RESULT_COUNT = 8;

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
                    description = "Question to answer using the biomedical research corpus; must contain between 1 and 1,000 characters after trimming",
                    required = true
            )
            String question,

            @McpToolParam(
                    description = "Maximum number of retrieved chunks to use; must be between 1 and 20",
                    required = false
            )
            Integer resultCount) {

        try {
            int effectiveResultCount = resultCount != null ? resultCount : DEFAULT_RESULT_COUNT;

            ResearchAnswer answer = researchGateway.query(
                    new ResearchQuestion(question, effectiveResultCount)
            );

            return toResponse(answer);
        } catch (RagContractException exception) {
            LOGGER.error("Research corpus contract failure", exception);
            throw exception;
        } catch (RagUnavailableException | RagTimeoutException | RagCircuitOpenException |
                 InvalidResearchQuestionException exception) {
            LOGGER.warn("Research corpus query failed: {}", exception.getMessage());
            throw exception;
        }
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
