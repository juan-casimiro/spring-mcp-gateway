package com.juancasimiro.mcpgateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

// Port 1 is reserved and refuses connections: stands in for a missing or
// wrong RAG_BASE_URL, or a RAG service that does not resolve or listen.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"rag.base-url=http://localhost:1", "rag.health.enabled=true"})
class RagHealthIndicatorUnreachableTest {

    @LocalServerPort
    private int port;

    @Test
    void reportsDownWhenRagCannotBeReached() {
        int status = RestClient.builder().baseUrl("http://localhost:" + port).build()
                .get().uri("/actuator/health")
                .exchange((request, response) -> response.getStatusCode().value());

        assertThat(status).isEqualTo(503);
    }
}
