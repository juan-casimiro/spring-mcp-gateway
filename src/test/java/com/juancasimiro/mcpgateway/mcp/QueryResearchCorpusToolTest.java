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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    void rethrowsContractFailure() {
        RagContractException failure = new RagContractException();
        QueryResearchCorpusTool tool = toolThrowing(failure);

        assertThatThrownBy(() -> tool.query("test question", 8))
                .isSameAs(failure);
    }

    @Test
    void rethrowsUnavailableServiceFailure() {
        RagUnavailableException failure = new RagUnavailableException();
        QueryResearchCorpusTool tool = toolThrowing(failure);

        assertThatThrownBy(() -> tool.query("test question", 8))
                .isSameAs(failure);
    }

    @Test
    void rethrowsTimeoutFailure() {
        RagTimeoutException failure = new RagTimeoutException();
        QueryResearchCorpusTool tool = toolThrowing(failure);

        assertThatThrownBy(() -> tool.query("test question", 8))
                .isSameAs(failure);
    }

    @Test
    void rethrowsInvalidQuestionFailure() {
        ResearchGateway researchGateway = mock(ResearchGateway.class);
        QueryResearchCorpusTool tool = new QueryResearchCorpusTool(researchGateway);

        assertThatThrownBy(() -> tool.query("   ", 8))
                .isInstanceOf(InvalidResearchQuestionException.class)
                .hasMessage("The research question must contain between 1 and 1,000 characters.");
    }

    @Test
    void rethrowsBreakerOpenFailure() {
        RagCircuitOpenException failure = new RagCircuitOpenException(new RuntimeException("circuit open"));
        QueryResearchCorpusTool tool = toolThrowing(failure);

        assertThatThrownBy(() -> tool.query("test question", 8))
                .isSameAs(failure);
    }

    private QueryResearchCorpusTool toolThrowing(RuntimeException failure) {
        ResearchGateway researchGateway = mock(ResearchGateway.class);
        when(researchGateway.query(new ResearchQuestion("test question", 8))).thenThrow(failure);
        return new QueryResearchCorpusTool(researchGateway);
    }

}
