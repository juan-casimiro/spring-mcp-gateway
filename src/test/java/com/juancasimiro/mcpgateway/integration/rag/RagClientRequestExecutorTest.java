package com.juancasimiro.mcpgateway.integration.rag;

import com.juancasimiro.mcpgateway.application.research.ResearchQuestion;
import com.juancasimiro.mcpgateway.integration.rag.exception.RagTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagClientRequestExecutorTest {

    @Test
    void mapsSocketTimeoutDuringRequestExecutionToRagTimeoutException() {
        RestClient restClient = RestClient.builder()
                .requestFactory((uri, httpMethod) -> new MockClientHttpRequest(httpMethod, uri) {
                    @Override
                    protected ClientHttpResponse executeInternal() throws SocketTimeoutException {
                        throw new SocketTimeoutException("connect timed out");
                    }
                })
                .baseUrl("http://test-rag-service")
                .build();
        RagClientRequestExecutor requestExecutor = new RagClientRequestExecutor(restClient);

        assertThatThrownBy(() -> requestExecutor.query(new ResearchQuestion("test question", 8)))
                .isInstanceOf(RagTimeoutException.class)
                .hasRootCauseInstanceOf(SocketTimeoutException.class);
    }
}
