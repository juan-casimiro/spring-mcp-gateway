package com.juancasimiro.mcpgateway.integration.rag;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.juancasimiro.mcpgateway.application.research.ResearchQuestion;
import com.juancasimiro.mcpgateway.integration.rag.exception.RagCircuitOpenException;
import com.juancasimiro.mcpgateway.integration.rag.exception.RagRateLimitException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "resilience4j.ratelimiter.instances.rag.limit-for-period=2",
        "resilience4j.ratelimiter.instances.rag.limit-refresh-period=1h",
        "resilience4j.retry.instances.rag.wait-duration=0"
})
@EnableWireMock(@ConfigureWireMock(name = "rag-service", baseUrlProperties = "rag.base-url"))
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RagClientRateLimitTest {

    private static final ResearchQuestion TEST_QUESTION = new ResearchQuestion("test rate limit", 8);

    @InjectWireMock("rag-service")
    private WireMockServer wireMock;

    @Autowired
    private RagClient ragClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private RateLimiterRegistry rateLimiterRegistry;

    @Test
    void permitsCallsWithinLimitThenRejectsWithoutRetryOrBreakerStatistics() {
        wireMock.stubFor(post(urlEqualTo("/query")).willReturn(okJson("""
                {"answer":"test answer","sources":[],"context_sufficient":true,"insufficiency_reason":null}
                """)));
        AtomicInteger rejections = trackRejections();

        assertThat(ragClient.query(TEST_QUESTION).answer()).isEqualTo("test answer");
        assertThat(ragClient.query(TEST_QUESTION).answer()).isEqualTo("test answer");
        assertRateLimitRejection();

        wireMock.verify(2, postRequestedFor(urlEqualTo("/query")));
        assertThat(rejections).hasValue(1);
        assertBreakerCounts(2, 0);
    }

    @Test
    void retriesConsumePermitsAndStopWhenLimitIsExhausted() {
        wireMock.stubFor(post(urlEqualTo("/query")).willReturn(aResponse().withStatus(503)));
        AtomicInteger rejections = trackRejections();

        assertRateLimitRejection();

        wireMock.verify(2, postRequestedFor(urlEqualTo("/query")));
        assertThat(rejections).hasValue(1);
        assertBreakerCounts(0, 2);
    }

    @Test
    void openBreakerRejectsBeforeConsumingAPermit() {
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker("rag");
        breaker.transitionToOpenState();

        assertThatThrownBy(() -> ragClient.query(TEST_QUESTION))
                .isInstanceOf(RagCircuitOpenException.class);

        wireMock.verify(0, postRequestedFor(urlEqualTo("/query")));
        assertThat(rateLimiterRegistry.rateLimiter("rag").getMetrics().getAvailablePermissions())
                .isEqualTo(2);
        assertThat(breaker.getMetrics().getNumberOfNotPermittedCalls()).isEqualTo(1);
    }

    private AtomicInteger trackRejections() {
        AtomicInteger rejections = new AtomicInteger();
        rateLimiterRegistry.rateLimiter("rag").getEventPublisher()
                .onFailure(event -> rejections.incrementAndGet());
        return rejections;
    }

    private void assertRateLimitRejection() {
        assertThatThrownBy(() -> ragClient.query(TEST_QUESTION))
                .isInstanceOf(RagRateLimitException.class)
                .hasCauseInstanceOf(RequestNotPermitted.class);
    }

    private void assertBreakerCounts(int successes, int failures) {
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker("rag");
        assertThat(breaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(successes);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(failures);
        assertThat(breaker.getMetrics().getNumberOfNotPermittedCalls()).isZero();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
