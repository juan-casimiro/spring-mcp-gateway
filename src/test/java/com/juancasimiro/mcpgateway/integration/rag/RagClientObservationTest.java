package com.juancasimiro.mcpgateway.integration.rag;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.juancasimiro.mcpgateway.application.research.ResearchQuestion;
import com.juancasimiro.mcpgateway.integration.rag.exception.RagCircuitOpenException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@EnableWireMock(@ConfigureWireMock(name = "rag-service", baseUrlProperties = "rag.base-url"))
class RagClientObservationTest {

    private static final ResearchQuestion TEST_QUESTION = new ResearchQuestion("test observation policy", 5);

    @InjectWireMock("rag-service")
    private WireMockServer ragWireMock;

    @Autowired
    private RagClient ragClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private RecordingObservationHandler recordingObservationHandler;

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void resetState() {
        ragWireMock.resetAll();
        recordingObservationHandler.finishedContexts().clear();
        circuitBreaker = circuitBreakerRegistry.circuitBreaker("rag");
        circuitBreaker.reset();
    }

    @Test
    void tagsSuccessfulQueryWithRequestedAndReturnedResultCounts() {
        ragWireMock.stubFor(post(urlEqualTo("/query")).willReturn(okJson("""
                {"answer":"test answer","sources":["source-a","source-b"],"context_sufficient":true}
                """)));

        ragClient.query(TEST_QUESTION);

        Observation.Context context = onlyFinishedContext();
        assertThat(context.getHighCardinalityKeyValue("rag.n_results.requested").getValue()).isEqualTo("5");
        assertThat(context.getHighCardinalityKeyValue("rag.n_results.returned").getValue()).isEqualTo("2");
        assertThat(context.getLowCardinalityKeyValue("rag.context_sufficient").getValue()).isEqualTo("true");
        assertThat(context.getLowCardinalityKeyValue("rag.circuit_breaker.state").getValue()).isEqualTo("CLOSED");
    }

    @Test
    void tagsInsufficientContextAsAValidOutcomeRatherThanAnError() {
        ragWireMock.stubFor(post(urlEqualTo("/query")).willReturn(okJson("""
                {"answer":"test partial answer","sources":["source-a"],"context_sufficient":false,
                 "insufficiency_reason":"test missing evidence"}
                """)));

        ragClient.query(TEST_QUESTION);

        Observation.Context context = onlyFinishedContext();
        assertThat(context.getLowCardinalityKeyValue("rag.context_sufficient").getValue()).isEqualTo("false");
    }

    @Test
    void tagsCircuitBreakerStateAtCallTimeWhenTheBreakerIsOpen() {
        circuitBreaker.transitionToOpenState();

        assertThatThrownBy(() -> ragClient.query(TEST_QUESTION))
                .isInstanceOf(RagCircuitOpenException.class);

        Observation.Context context = onlyFinishedContext();
        assertThat(context.getLowCardinalityKeyValue("rag.circuit_breaker.state").getValue()).isEqualTo("OPEN");
        assertThat(context.getHighCardinalityKeyValue("rag.n_results.returned")).isNull();
        ragWireMock.verify(0, postRequestedFor(urlEqualTo("/query")));
    }

    private Observation.Context onlyFinishedContext() {
        assertThat(recordingObservationHandler.finishedContexts()).singleElement();
        return recordingObservationHandler.finishedContexts().getFirst();
    }

    @TestConfiguration
    static class ObservationTestConfiguration {
        @Bean
        RecordingObservationHandler recordingObservationHandler() {
            return new RecordingObservationHandler();
        }
    }

    static class RecordingObservationHandler implements ObservationHandler<Observation.Context> {
        private final List<Observation.Context> finishedContexts = new CopyOnWriteArrayList<>();

        List<Observation.Context> finishedContexts() {
            return finishedContexts;
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return RagClient.OBSERVATION_NAME.equals(context.getName());
        }

        @Override
        public void onStop(Observation.Context context) {
            finishedContexts.add(context);
        }
    }
}
