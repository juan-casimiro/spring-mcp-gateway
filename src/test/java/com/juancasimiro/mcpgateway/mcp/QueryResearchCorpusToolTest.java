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
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class QueryResearchCorpusToolTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(QueryResearchCorpusTool.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureBoundaryLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseBoundaryLogs() {
        logger.detachAppender(logs);
        logs.stop();
    }

    @Test
    void forwardsExplicitResultCountAndTrimmedQuestion() {
        ResearchGateway gateway = mock(ResearchGateway.class);
        ResearchQuestion expected = new ResearchQuestion("test question", 3);
        ResearchAnswer answer = new ResearchAnswer("test answer", List.of("test source"), true, null);
        when(gateway.query(any(ResearchQuestion.class))).thenReturn(answer);

        var response = new QueryResearchCorpusTool(gateway).query("  test question  ", 3);

        assertThat(response).isEqualTo(new QueryResearchCorpusResponse(
                "test answer", List.of("test source"), true, null));
        verify(gateway).query(expected);
    }


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
        assertBoundaryLog(Level.ERROR, failure, true);
    }

    @Test
    void rethrowsUnavailableServiceFailure() {
        RagUnavailableException failure = new RagUnavailableException();
        QueryResearchCorpusTool tool = toolThrowing(failure);

        assertThatThrownBy(() -> tool.query("test question", 8))
                .isSameAs(failure);
        assertBoundaryLog(Level.WARN, failure, false);
    }

    @Test
    void rethrowsTimeoutFailure() {
        RagTimeoutException failure = new RagTimeoutException();
        QueryResearchCorpusTool tool = toolThrowing(failure);

        assertThatThrownBy(() -> tool.query("test question", 8))
                .isSameAs(failure);
        assertBoundaryLog(Level.WARN, failure, false);
    }

    @Test
    void rethrowsInvalidQuestionFailure() {
        ResearchGateway researchGateway = mock(ResearchGateway.class);
        QueryResearchCorpusTool tool = new QueryResearchCorpusTool(researchGateway);

        assertThatThrownBy(() -> tool.query("   ", 8))
                .isInstanceOf(InvalidResearchQuestionException.class)
                .hasMessage("The research question must contain between 1 and 1,000 characters.");
        verifyNoInteractions(researchGateway);
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    void rethrowsBreakerOpenFailure() {
        RagCircuitOpenException failure = new RagCircuitOpenException(new RuntimeException("circuit open"));
        QueryResearchCorpusTool tool = toolThrowing(failure);

        assertThatThrownBy(() -> tool.query("test question", 8))
                .isSameAs(failure);
        assertBoundaryLog(Level.WARN, failure, false);
    }

    private void assertBoundaryLog(Level level, RuntimeException failure, boolean includesCause) {
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(level);
            if (includesCause) {
                assertThat(event.getThrowableProxy().getClassName()).isEqualTo(failure.getClass().getName());
            } else {
                assertThat(event.getFormattedMessage()).contains(failure.getMessage());
                assertThat(event.getThrowableProxy()).isNull();
            }
        });
    }

    private QueryResearchCorpusTool toolThrowing(RuntimeException failure) {
        ResearchGateway researchGateway = mock(ResearchGateway.class);
        when(researchGateway.query(new ResearchQuestion("test question", 8))).thenThrow(failure);
        return new QueryResearchCorpusTool(researchGateway);
    }

}
