package com.juancasimiro.mcpgateway.integration.rag;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.juancasimiro.mcpgateway.application.research.ResearchQuestion;
import com.juancasimiro.mcpgateway.integration.rag.exception.RagTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "rag.read-timeout=200ms")
@EnableWireMock(
        @ConfigureWireMock(
                name = "rag-service",
                baseUrlProperties = "rag.base-url"
        )
)
class RagClientTimeoutContractTest {

    @InjectWireMock("rag-service")
    private WireMockServer wireMock;

    @Autowired
    private RagClient ragClient;

    @Test
    void mapsReadTimeoutToRagTimeoutException() {
        wireMock.stubFor(post(urlEqualTo("/query"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "answer": "test answer",
                                  "sources": [],
                                  "context_sufficient": true,
                                  "insufficiency_reason": null
                                }
                                """)
                        .withFixedDelay(500)));

        // Exercises the configured HTTP request factory. If this fails after changing the factory,
        // re-check timeout classification and retry behaviour before adapting the exception mapping.
        assertThatThrownBy(() -> ragClient.query(new ResearchQuestion("test question", 8)))
                .isInstanceOf(RagTimeoutException.class);
    }
}
