package com.juancasimiro.mcpgateway.config;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

// rag.health.enabled is deliberately unset: guards the default, where the
// gateway's health does not depend on the RAG service at all.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnableWireMock(@ConfigureWireMock(name = "rag-service", baseUrlProperties = "rag.base-url"))
class RagHealthIndicatorDisabledTest {

    @LocalServerPort
    private int port;

    @InjectWireMock("rag-service")
    private WireMockServer ragWireMock;

    @Autowired
    private ApplicationContext context;

    @BeforeEach
    void setUp() {
        ragWireMock.resetAll();
    }

    @Test
    void isNotRegisteredAndNeverCallsRagWhenNotEnabled() {
        int status = RestClient.builder().baseUrl("http://localhost:" + port).build()
                .get().uri("/actuator/health")
                .exchange((request, response) -> response.getStatusCode().value());

        assertThat(status).isEqualTo(200);
        assertThat(context.getBeanNamesForType(RagHealthIndicator.class)).isEmpty();
        ragWireMock.verify(0, anyRequestedFor(anyUrl()));
    }
}
