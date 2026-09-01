package com.juancasimiro.mcpgateway.config;

import com.juancasimiro.mcpgateway.integration.rag.exception.RagContractException;
import com.juancasimiro.mcpgateway.integration.rag.exception.RagTimeoutException;
import com.juancasimiro.mcpgateway.integration.rag.exception.RagUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.functions.Either;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ResilienceConfigurationTest {

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private RetryRegistry retryRegistry;

    @Test
    void loadsRagCircuitBreakerConfiguration() {
        CircuitBreakerConfig config = circuitBreakerRegistry
                .circuitBreaker("rag")
                .getCircuitBreakerConfig();

        assertThat(config.getSlidingWindowType())
                .isEqualTo(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED);
        assertThat(config.getSlidingWindowSize()).isEqualTo(10);
        assertThat(config.getMinimumNumberOfCalls()).isEqualTo(5);
        assertThat(config.getFailureRateThreshold()).isEqualTo(50.0f);
        assertThat(config.getSlowCallDurationThreshold()).isEqualTo(Duration.ofSeconds(20));
        assertThat(config.getSlowCallRateThreshold()).isEqualTo(50.0f);
        assertThat(config.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(30_000L);
        assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(1);
        assertThat(config.isAutomaticTransitionFromOpenToHalfOpenEnabled()).isFalse();
        assertThat(config.getRecordExceptionPredicate().test(new RagUnavailableException())).isTrue();
        assertThat(config.getRecordExceptionPredicate().test(new RagTimeoutException())).isTrue();
        assertThat(config.getIgnoreExceptionPredicate().test(new RagContractException())).isTrue();
    }

    @Test
    void loadsOnlyTheNamedRagCircuitBreaker() {
        assertThat(circuitBreakerRegistry.getAllCircuitBreakers())
                .extracting(CircuitBreaker::getName)
                .containsExactly("rag");
    }

    @Test
    void loadsRagRetryConfiguration() {
        var config = retryRegistry.retry("rag").getRetryConfig();

        assertThat(config.getMaxAttempts()).isEqualTo(3);
        assertThat(config.getIntervalBiFunction().apply(
                1,
                Either.left(new RagUnavailableException())
        )).isEqualTo(1_000L);
        assertThat(config.getExceptionPredicate().test(new RagUnavailableException())).isTrue();
        assertThat(config.getExceptionPredicate().test(new RagTimeoutException())).isFalse();
        assertThat(config.getExceptionPredicate().test(new RagContractException())).isFalse();
    }

    @Test
    void loadsOnlyTheNamedRagRetry() {
        assertThat(retryRegistry.getAllRetries())
                .extracting(Retry::getName)
                .containsExactly("rag");
    }
}
