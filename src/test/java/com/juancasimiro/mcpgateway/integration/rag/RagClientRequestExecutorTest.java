package com.juancasimiro.mcpgateway.integration.rag;

import com.juancasimiro.mcpgateway.application.research.ResearchQuestion;
import com.juancasimiro.mcpgateway.integration.rag.exception.RagTimeoutException;
import com.juancasimiro.mcpgateway.integration.rag.exception.RagUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagClientRequestExecutorTest {

    @Test
    void mapsSocketTimeoutDuringRequestExecutionToRagTimeoutException() {
        // HttpURLConnection connects lazily, so a connect timeout surfaces during execute(),
        // not createRequest(), and throws SocketTimeoutException -- the same type as a read
        // timeout. Both therefore classify as RagTimeoutException and are not retried.
        // Deliberate: distinguishing them requires a different request factory. See ADR-003.
        RagClientRequestExecutor requestExecutor = requestExecutorThrowing(
                new SocketTimeoutException("connect timed out")
        );

        assertThatThrownBy(() -> requestExecutor.query(new ResearchQuestion("test question", 8)))
                .isInstanceOf(RagTimeoutException.class)
                .hasRootCauseInstanceOf(SocketTimeoutException.class);
    }

    @Test
    void mapsConnectionRefusedDuringRequestExecutionToRagUnavailableException() {
        RagClientRequestExecutor requestExecutor = requestExecutorThrowing(
                new ConnectException("Connection refused")
        );

        assertThatThrownBy(() -> requestExecutor.query(new ResearchQuestion("test question", 8)))
                .isInstanceOf(RagUnavailableException.class)
                .hasRootCauseInstanceOf(ConnectException.class);
    }

    private RagClientRequestExecutor requestExecutorThrowing(IOException exception) {
        RestClient restClient = RestClient.builder()
                .requestFactory((uri, httpMethod) -> new MockClientHttpRequest(httpMethod, uri) {
                    @Override
                    protected ClientHttpResponse executeInternal() throws IOException {
                        throw exception;
                    }
                })
                .baseUrl("http://test-rag-service")
                .build();

        return new RagClientRequestExecutor(restClient);
    }
}
