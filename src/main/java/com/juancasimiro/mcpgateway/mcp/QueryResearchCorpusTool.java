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
            description = "Answers questions using a corpus of peer-reviewed biomedical journal "
                    + "articles. The corpus is biomedical only — no legal, physics, humanities, or "
                    + "other non-biomedical material. Retrieves the most relevant passages for the "
                    + "question, then returns an answer grounded only in that retrieved text, along "
                    + "with the source documents it drew from (`sources`).\n\n"
                    + "Call this tool whenever a question needs a specific fact, figure, dosing "
                    + "regimen, outcome, or finding attributed to biomedical research — including a "
                    + "question about a particular trial or study — even if it is unknown in advance "
                    + "whether the corpus actually covers that source; `contextSufficient` reports "
                    + "that gap in the response rather than requiring it to be known beforehand. Do "
                    + "not call it for general clinical knowledge answerable without a literature "
                    + "citation, such as typical vital-sign ranges, standard medical abbreviations, "
                    + "or basic physiology.\n\n"
                    + "The response also reports `contextSufficient`: true only when the retrieved "
                    + "passages explicitly provide the specific fact, figure, or recommendation "
                    + "asked for — whether from a single passage or by combining several. "
                    + "Topically related or generally relevant passages are not sufficient. When "
                    + "false, do not override this flag based on how relevant the sources or answer "
                    + "text appear to be. `insufficiencyReason` then gives a short, human-readable "
                    + "note describing what the retrieved context covered instead. Treat "
                    + "`insufficiencyReason` as explanatory text only — base any decision to retry, "
                    + "rephrase, or stop on `contextSufficient`, never on parsing the reason text."
    )
    public QueryResearchCorpusResponse query(
            @McpToolParam(
                    description = "Question to answer using the biomedical research corpus; must contain between 1 and 1,000 characters after trimming",
                    required = true
            )
            String question,

            @McpToolParam(
                    description = "Number of retrieved text passages (not source documents) to use "
                            + "as context before answering. Higher values give more context — useful "
                            + "for broad or multi-part questions — at some risk of diluting relevance; "
                            + "lower values keep context tighter, better for narrow factual lookups. "
                            + "Must be between 1 and 20; defaults to 8 if omitted. Does not control "
                            + "how many source documents appear in the response, since several "
                            + "passages can come from the same document.",
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
