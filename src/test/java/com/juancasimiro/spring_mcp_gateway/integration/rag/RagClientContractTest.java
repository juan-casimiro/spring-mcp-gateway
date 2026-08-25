package com.juancasimiro.spring_mcp_gateway.integration.rag;

import com.juancasimiro.spring_mcp_gateway.application.research.ResearchAnswer;
import com.juancasimiro.spring_mcp_gateway.application.research.ResearchQuestion;
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
                          "n_results": 8,
                          "use_bm25": false,
                          "use_query_rewriting": false
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

        ResearchAnswer response = ragClient.query(
                new ResearchQuestion(
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
