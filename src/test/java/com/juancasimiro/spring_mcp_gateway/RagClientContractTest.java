package com.juancasimiro.spring_mcp_gateway;

import com.juancasimiro.spring_mcp_gateway.model.RagQueryRequest;
import com.juancasimiro.spring_mcp_gateway.model.RagQueryResponse;
import com.juancasimiro.spring_mcp_gateway.services.RagClient;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@EnableWireMock(
        @ConfigureWireMock(
                name = "rag-service",
                baseUrlProperties = "rag.base-url"
        )
)
class RagClientContractTest {

    @InjectWireMock("rag-service")
    private WireMockServer wireMock;

    @Autowired
    private RagClient ragClient;

    @Test
    void sendsQueryAndDeserializesResponse() {
        wireMock.stubFor(post(urlEqualTo("/query"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(equalToJson("""
                        {
                          "question": "What does CT-FFR measure?",
                          "n_results": 8
                        }
                        """))
                .willReturn(okJson("""
                        {
                          "answer": "CT-FFR estimates the functional significance of a coronary stenosis.",
                          "sources": ["cardio-ct-ffr.pdf"],
                          "context_sufficient": true,
                          "insufficiency_reason": null
                        }
                        """)));

        RagQueryResponse response = ragClient.query(
                new RagQueryRequest(
                        "What does CT-FFR measure?",
                        8
                )
        );

        assertThat(response).isNotNull();
        assertThat(response.answer())
                .isEqualTo("CT-FFR estimates the functional significance of a coronary stenosis.");
        assertThat(response.sources())
                .containsExactly("cardio-ct-ffr.pdf");
        assertThat(response.contextSufficient()).isTrue();
        assertThat(response.insufficiencyReason()).isNull();
    }
}