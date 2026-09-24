package com.juancasimiro.mcpgateway.config;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"gateway.security.api-token=test-health-token", "rag.health.enabled=true"})
@EnableWireMock(@ConfigureWireMock(name = "rag-service", baseUrlProperties = "rag.base-url"))
class RagHealthIndicatorTest {

    @LocalServerPort
    private int port;

    @InjectWireMock("rag-service")
    private WireMockServer ragWireMock;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    // Plain JDK client on purpose: RestClient's default request factory
    // re-sends a GET that got a 503, which would double-count RAG requests.
    private final HttpClient gatewayClient = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        ragWireMock.resetAll();
    }

    @Test
    void reportsUpWhenRagHealthAnswersOk() {
        ragWireMock.stubFor(get(urlEqualTo("/health")).willReturn(aResponse().withStatus(200)));

        HealthResult result = gatewayHealth();

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body()).contains("\"status\":\"UP\"");
        assertExactlyOneRagHealthRequestAndNoCircuitBreakerTraffic();
    }

    @Test
    void reportsDownWithoutRetryWhenRagIsNotReady() {
        ragWireMock.stubFor(get(urlEqualTo("/health")).willReturn(aResponse().withStatus(503)));

        HealthResult result = gatewayHealth();

        assertThat(result.status()).isEqualTo(503);
        assertThat(result.body()).contains("\"status\":\"DOWN\"");
        assertExactlyOneRagHealthRequestAndNoCircuitBreakerTraffic();
    }

    @Test
    void givesUpQuicklyOnASlowRagHealthAnswerInsteadOfWaitingForTheQueryReadTimeout() {
        ragWireMock.stubFor(get(urlEqualTo("/health"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(5_000)));

        long startedAt = System.nanoTime();
        HealthResult result = gatewayHealth();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(result.status()).isEqualTo(503);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(4));
    }

    private HealthResult gatewayHealth() {
        try {
            HttpResponse<String> response = gatewayClient.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return new HealthResult(response.statusCode(), response.body());
        } catch (IOException | InterruptedException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void assertExactlyOneRagHealthRequestAndNoCircuitBreakerTraffic() {
        ragWireMock.verify(1, getRequestedFor(urlEqualTo("/health")));
        CircuitBreaker.Metrics ragCircuitBreaker = circuitBreakerRegistry.circuitBreaker("rag").getMetrics();
        assertThat(ragCircuitBreaker.getNumberOfSuccessfulCalls()).isZero();
        assertThat(ragCircuitBreaker.getNumberOfFailedCalls()).isZero();
    }

    private record HealthResult(int status, String body) {
    }
}
