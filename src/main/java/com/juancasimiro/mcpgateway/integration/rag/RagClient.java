package com.juancasimiro.mcpgateway.integration.rag;

import com.juancasimiro.mcpgateway.application.research.ResearchAnswer;
import com.juancasimiro.mcpgateway.application.research.ResearchGateway;
import com.juancasimiro.mcpgateway.application.research.ResearchQuestion;
import com.juancasimiro.mcpgateway.integration.rag.exception.RagCircuitOpenException;
import com.juancasimiro.mcpgateway.integration.rag.exception.RagRateLimitException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

@Component
public class RagClient implements ResearchGateway {

    static final String CIRCUIT_BREAKER_NAME = "rag";
    static final String OBSERVATION_NAME = "rag.query";

    private final RagClientRequestExecutor requestExecutor;
    private final ObservationRegistry observationRegistry;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public RagClient(
            RagClientRequestExecutor requestExecutor,
            ObservationRegistry observationRegistry,
            CircuitBreakerRegistry circuitBreakerRegistry) {
        this.requestExecutor = requestExecutor;
        this.observationRegistry = observationRegistry;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @Override
    public ResearchAnswer query(ResearchQuestion question) {
        Observation observation = Observation.createNotStarted(OBSERVATION_NAME, observationRegistry)
                .highCardinalityKeyValue("rag.n_results.requested", String.valueOf(question.resultCount()))
                .lowCardinalityKeyValue("rag.circuit_breaker.state", circuitBreakerState());

        return observation.observe(() -> executeQuery(question, observation));
    }

    private ResearchAnswer executeQuery(ResearchQuestion question, Observation observation) {
        try {
            ResearchAnswer answer = requestExecutor.query(question);
            observation.highCardinalityKeyValue("rag.n_results.returned", String.valueOf(answer.sources().size()));
            observation.lowCardinalityKeyValue("rag.context_sufficient", String.valueOf(answer.contextSufficient()));
            return answer;
        } catch (CallNotPermittedException exception) {
            throw new RagCircuitOpenException(exception);
        } catch (RequestNotPermitted exception) {
            throw new RagRateLimitException();
        }
    }

    private String circuitBreakerState() {
        return circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME).getState().name();
    }
}
