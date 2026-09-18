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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class QueryResearchCorpusTool {

    private static final Logger LOGGER = LoggerFactory.getLogger(QueryResearchCorpusTool.class);
    private static final int DEFAULT_RESULT_COUNT = 8;
    private static final String TOOL_NAME = "query_research_corpus";
    private static final String TIMER_NAME = "mcp.tool.duration";

    private final ResearchGateway researchGateway;
    private final MeterRegistry meterRegistry;

    public QueryResearchCorpusTool(ResearchGateway researchGateway, MeterRegistry meterRegistry) {
        this.researchGateway = researchGateway;
        this.meterRegistry = meterRegistry;
    }

    @McpTool(
            name = TOOL_NAME,
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

        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            int effectiveResultCount = resultCount != null ? resultCount : DEFAULT_RESULT_COUNT;

            ResearchAnswer answer = researchGateway.query(
                    new ResearchQuestion(question, effectiveResultCount)
            );

            outcome = "success";
            return toResponse(answer);
        } catch (RagContractException exception) {
            LOGGER.error("Research corpus contract failure", exception);
            throw exception;
        } catch (RagUnavailableException | RagTimeoutException | RagCircuitOpenException |
                 InvalidResearchQuestionException exception) {
            LOGGER.warn("Research corpus query failed: {}", exception.getMessage());
            throw exception;
        } finally {
            sample.stop(Timer.builder(TIMER_NAME)
                    .tag("tool", TOOL_NAME)
                    .tag("outcome", outcome)
                    .register(meterRegistry));
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
