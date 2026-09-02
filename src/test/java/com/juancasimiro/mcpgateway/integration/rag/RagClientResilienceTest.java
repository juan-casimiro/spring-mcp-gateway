package com.juancasimiro.mcpgateway.integration.rag;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.juancasimiro.mcpgateway.application.research.ResearchQuestion;
import com.juancasimiro.mcpgateway.integration.rag.exception.RagCircuitOpenException;
import com.juancasimiro.mcpgateway.integration.rag.exception.RagContractException;
import com.juancasimiro.mcpgateway.integration.rag.exception.RagTimeoutException;
import com.juancasimiro.mcpgateway.integration.rag.exception.RagUnavailableException;
import com.juancasimiro.mcpgateway.mcp.QueryResearchCorpusTool;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "resilience4j.circuitbreaker.instances.rag.sliding-window-type=COUNT_BASED",
        "resilience4j.circuitbreaker.instances.rag.sliding-window-size=4",
        "resilience4j.circuitbreaker.instances.rag.minimum-number-of-calls=4",
        "resilience4j.circuitbreaker.instances.rag.failure-rate-threshold=50",
        "resilience4j.retry.instances.rag.wait-duration=0"
})
@EnableWireMock(
        @ConfigureWireMock(
                name = "rag-service",
                baseUrlProperties = "rag.base-url"
        )
)
class RagClientResilienceTest {

    private static final int EXPECTED_RETRY_ATTEMPTS = 3;
    private static final ResearchQuestion TEST_QUESTION =
            new ResearchQuestion("test resilience policy", 8);

    @InjectWireMock("rag-service")
    private WireMockServer wireMock;

    @Autowired
    private RagClient ragClient;

    @Autowired
    private QueryResearchCorpusTool queryResearchCorpusTool;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void resetResilienceState() {
        wireMock.resetAll();
        circuitBreaker = circuitBreakerRegistry.circuitBreaker("rag");
        circuitBreaker.reset();
    }

    @Test
    void retriesUnavailableFailuresUpToTheConfiguredMaximumAttempts() {
        wireMock.stubFor(post(urlEqualTo("/query"))
                .willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> ragClient.query(TEST_QUESTION))
                .isInstanceOf(RagUnavailableException.class);

        wireMock.verify(EXPECTED_RETRY_ATTEMPTS, postRequestedFor(urlEqualTo("/query")));
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls())
                .as("each protected RAG attempt is recorded, including retries")
                .isEqualTo(EXPECTED_RETRY_ATTEMPTS);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void propagatesTechnicalFailureThroughToolWithoutRetryOrSuccessfulResponse() {
        wireMock.stubFor(post(urlEqualTo("/query"))
                .willReturn(aResponse().withStatus(504)));

        assertThatThrownBy(() -> queryResearchCorpusTool.query("test resilience policy", 8))
                .isInstanceOf(RagTimeoutException.class);

        wireMock.verify(1, postRequestedFor(urlEqualTo("/query")));
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void doesNotRetryOrRecordContractFailures() {
        wireMock.stubFor(post(urlEqualTo("/query"))
                .willReturn(aResponse().withStatus(422)));

        assertThatThrownBy(() -> ragClient.query(TEST_QUESTION))
                .isInstanceOf(RagContractException.class);

        wireMock.verify(1, postRequestedFor(urlEqualTo("/query")));
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(circuitBreaker.getMetrics().getNumberOfSuccessfulCalls()).isZero();
        assertThat(circuitBreaker.getMetrics().getNumberOfNotPermittedCalls()).isZero();
    }

    @Test
    void opensBreakerAfterTheConfiguredFailureThreshold() {
        wireMock.stubFor(post(urlEqualTo("/query"))
                .willReturn(aResponse().withStatus(504)));

        for (int call = 0; call < 4; call++) {
            assertThatThrownBy(() -> ragClient.query(TEST_QUESTION))
                    .isInstanceOf(RagTimeoutException.class);
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(4);
        wireMock.verify(4, postRequestedFor(urlEqualTo("/query")));
    }

    @Test
    void translatesBreakerOpenFailureWithoutRetryingOrCallingRagService() {
        circuitBreaker.transitionToOpenState();

        assertThatThrownBy(() -> queryResearchCorpusTool.query("test resilience policy", 8))
                .isInstanceOf(RagCircuitOpenException.class)
                .hasMessage("The research service is temporarily unavailable because the circuit breaker is open.")
                .hasCauseInstanceOf(CallNotPermittedException.class);

        wireMock.verify(0, postRequestedFor(urlEqualTo("/query")));
        assertThat(circuitBreaker.getMetrics().getNumberOfNotPermittedCalls()).isEqualTo(1);
    }
}
