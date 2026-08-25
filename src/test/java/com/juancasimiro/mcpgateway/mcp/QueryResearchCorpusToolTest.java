package com.juancasimiro.mcpgateway.mcp;

import com.juancasimiro.mcpgateway.application.research.ResearchAnswer;
import com.juancasimiro.mcpgateway.application.research.ResearchGateway;
import com.juancasimiro.mcpgateway.application.research.ResearchQuestion;
import com.juancasimiro.mcpgateway.mcp.model.QueryResearchCorpusResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
}
