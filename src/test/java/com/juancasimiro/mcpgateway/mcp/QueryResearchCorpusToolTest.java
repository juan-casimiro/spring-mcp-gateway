package com.juancasimiro.mcpgateway.mcp;

import com.juancasimiro.mcpgateway.application.research.ResearchAnswer;
import com.juancasimiro.mcpgateway.application.research.ResearchGateway;
import com.juancasimiro.mcpgateway.application.research.ResearchQuestion;
import com.juancasimiro.mcpgateway.integration.rag.RagContractException;
import com.juancasimiro.mcpgateway.integration.rag.RagTimeoutException;
import com.juancasimiro.mcpgateway.integration.rag.RagUnavailableException;
import com.juancasimiro.mcpgateway.mcp.model.QueryResearchCorpusResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class QueryResearchCorpusToolTest {

    @Test
    void mapsResearchAnswerToMcpResponseAndPreservesSourceOrder() {
        ResearchGateway researchGateway = mock(ResearchGateway.class);
        ResearchQuestion question = new ResearchQuestion("Example question", 8);
        ResearchAnswer answer = new ResearchAnswer(
                "Example answer",
                List.of("document-a.pdf", "document-b.pdf"),
                false,
                "The retrieved context does not fully answer the question."
        );
        when(researchGateway.query(question)).thenReturn(answer);
        QueryResearchCorpusTool tool = new QueryResearchCorpusTool(researchGateway);

        QueryResearchCorpusResponse response = tool.query("Example question", null);

        assertThat(response).isEqualTo(new QueryResearchCorpusResponse(
                "Example answer",
                List.of("document-a.pdf", "document-b.pdf"),
                false,
                "The retrieved context does not fully answer the question."
        ));
        verify(researchGateway).query(question);
    }

    @Test
    void returnsFailureResponseAndLogsErrorForContractFailure(CapturedOutput output) {
        RagContractException failure = new RagContractException();
        QueryResearchCorpusTool tool = toolThrowing(failure);

        QueryResearchCorpusResponse response = tool.query("test question", 8);

        assertFailureResponse(response, failure.getMessage());
        assertThat(output).contains("ERROR").contains("Research corpus contract failure");
    }

    @Test
    void returnsFailureResponseAndLogsWarningForUnavailableService(CapturedOutput output) {
        RagUnavailableException failure = new RagUnavailableException();
        QueryResearchCorpusTool tool = toolThrowing(failure);

        QueryResearchCorpusResponse response = tool.query("test question", 8);

        assertFailureResponse(response, failure.getMessage());
        assertThat(output).contains("WARN").contains(failure.getMessage());
    }

    @Test
    void returnsFailureResponseAndLogsWarningForTimeout(CapturedOutput output) {
        RagTimeoutException failure = new RagTimeoutException();
        QueryResearchCorpusTool tool = toolThrowing(failure);

        QueryResearchCorpusResponse response = tool.query("test question", 8);

        assertFailureResponse(response, failure.getMessage());
        assertThat(output).contains("WARN").contains(failure.getMessage());
    }

    @Test
    void returnsFailureResponseAndLogsWarningForInvalidQuestion(CapturedOutput output) {
        ResearchGateway researchGateway = mock(ResearchGateway.class);
        QueryResearchCorpusTool tool = new QueryResearchCorpusTool(researchGateway);

        QueryResearchCorpusResponse response = tool.query("   ", 8);

        assertFailureResponse(
                response,
                "The research question must contain between 1 and 1,000 characters."
        );
        assertThat(output).contains("WARN").contains("The research question must contain between 1 and 1,000 characters.");
    }

    private QueryResearchCorpusTool toolThrowing(RuntimeException failure) {
        ResearchGateway researchGateway = mock(ResearchGateway.class);
        when(researchGateway.query(new ResearchQuestion("test question", 8))).thenThrow(failure);
        return new QueryResearchCorpusTool(researchGateway);
    }

    private void assertFailureResponse(QueryResearchCorpusResponse response, String message) {
        assertThat(response).isEqualTo(new QueryResearchCorpusResponse(
                message,
                List.of(),
                false,
                message
        ));
    }
}
