package com.juancasimiro.mcpgateway.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Opt-in readiness check: reports UP only if the RAG service's own /health
 * answers 2xx at the configured {@code rag.base-url}. Absent unless
 * {@code rag.health.enabled=true}, so the default /actuator/health stays a
 * gateway-only check. Deliberately bypasses the query path (retry, circuit
 * breaker, rate limiter), so polling neither consumes them nor moves their
 * metrics; its timeouts are its own and stay below the container
 * HEALTHCHECK's four seconds. RAG's /health does no retrieval and no LLM call.
 */
@Component
@ConditionalOnProperty(name = "rag.health.enabled", havingValue = "true")
class RagHealthIndicator implements HealthIndicator {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(2);

    private final RestClient ragHealthClient;

    RagHealthIndicator(RestClient.Builder builder, RagProperties ragProperties) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);

        this.ragHealthClient = builder
                .requestFactory(requestFactory)
                .baseUrl(ragProperties.baseUrl())
                .build();
    }

    @Override
    public Health health() {
        try {
            ragHealthClient.get().uri("/health").retrieve().toBodilessEntity();
            return Health.up().build();
        } catch (RuntimeException exception) {
            return Health.down().build();
        }
    }
}
