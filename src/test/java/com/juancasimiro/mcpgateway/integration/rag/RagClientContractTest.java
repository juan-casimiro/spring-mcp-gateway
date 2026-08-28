package com.juancasimiro.mcpgateway.integration.rag;

import com.juancasimiro.mcpgateway.application.research.ResearchAnswer;
import com.juancasimiro.mcpgateway.application.research.ResearchQuestion;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void preservesInsufficientContextAsSuccessfulResponse() {
        wireMock.stubFor(post(urlEqualTo("/query"))
                .willReturn(okJson("""
                        {
                          "answer": "The corpus does not contain enough evidence.",
                          "sources": [],
                          "context_sufficient": false,
                          "insufficiency_reason": "No relevant evidence was retrieved."
                        }
                        """)));

        ResearchAnswer response = ragClient.query(new ResearchQuestion("test question", 8));

        assertThat(response.contextSufficient()).isFalse();
        assertThat(response.insufficiencyReason()).isEqualTo("No relevant evidence was retrieved.");
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503})
    void mapsUnavailableStatuses(int status) {
        wireMock.stubFor(post(urlEqualTo("/query")).willReturn(aResponse().withStatus(status)));

        assertThatThrownBy(() -> ragClient.query(new ResearchQuestion("test question", 8)))
                .isInstanceOf(RagUnavailableException.class);
    }

    @Test
    void mapsGatewayTimeoutStatus() {
        wireMock.stubFor(post(urlEqualTo("/query")).willReturn(aResponse().withStatus(504)));

        assertThatThrownBy(() -> ragClient.query(new ResearchQuestion("test question", 8)))
                .isInstanceOf(RagTimeoutException.class);
    }

    @Test
    void mapsUnlistedServerErrorStatus() {
        wireMock.stubFor(post(urlEqualTo("/query")).willReturn(aResponse().withStatus(507)));

        assertThatThrownBy(() -> ragClient.query(new ResearchQuestion("test question", 8)))
                .isInstanceOf(RagUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 404, 422})
    void mapsClientErrorStatuses(int status) {
        wireMock.stubFor(post(urlEqualTo("/query")).willReturn(aResponse().withStatus(status)));

        assertThatThrownBy(() -> ragClient.query(new ResearchQuestion("test question", 8)))
                .isInstanceOf(RagContractException.class);
    }

    @Test
    void mapsEmptySuccessfulBody() {
        wireMock.stubFor(post(urlEqualTo("/query")).willReturn(ok()));

        assertThatThrownBy(() -> ragClient.query(new ResearchQuestion("test question", 8)))
                .isInstanceOf(RagContractException.class);
    }

    @Test
    void mapsUnsupportedSuccessfulBodyContentType() {
        wireMock.stubFor(post(urlEqualTo("/query"))
                .willReturn(ok("not JSON").withHeader("Content-Type", "text/html")));

        assertThatThrownBy(() -> ragClient.query(new ResearchQuestion("test question", 8)))
                .isInstanceOf(RagContractException.class);
    }

    @Test
    void mapsNonTimeoutTransportFailure() {
        wireMock.stubFor(post(urlEqualTo("/query"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> ragClient.query(new ResearchQuestion("test question", 8)))
                .isInstanceOf(RagUnavailableException.class);
    }
}
