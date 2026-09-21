package com.juancasimiro.mcpgateway.config;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.juancasimiro.mcpgateway.mcp.QueryResearchCorpusTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "gateway.security.api-token=test-actuator-token")
@EnableWireMock(@ConfigureWireMock(name = "rag-service", baseUrlProperties = "rag.base-url"))
class ActuatorObservabilityEndpointsTest {

    @LocalServerPort
    private int port;

    @InjectWireMock("rag-service")
    private WireMockServer ragWireMock;

    @Autowired
    private QueryResearchCorpusTool queryResearchCorpusTool;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private RestClient actuatorClient;

    @BeforeEach
    void setUp() {
        ragWireMock.resetAll();
        actuatorClient = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", "Bearer test-actuator-token").build();
    }

    @Test
    void exposesTheRagCircuitBreakerAndRateLimiterViaActuator() {
        String circuitBreakers = actuatorClient.get().uri("/actuator/circuitbreakers")
                .retrieve().body(String.class);
        assertThat(circuitBreakers).contains("\"rag\"");

        String rateLimiters = actuatorClient.get().uri("/actuator/ratelimiters")
                .retrieve().body(String.class);
        assertThat(rateLimiters).contains("\"rag\"");
    }

    @Test
    void exposesThePerToolTimerThroughActuatorMetricsAfterAToolCall() {
        stubSuccessfulQuery();

        queryResearchCorpusTool.query("test actuator metrics", 8);

        assertThat(metricNames()).contains("mcp.tool.duration");
        assertThat(measurement("mcp.tool.duration", "COUNT", "tool:query_research_corpus", "outcome:success"))
                .isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void exposesResilience4jMetricsForTheRagInstanceThroughActuatorMetrics() {
        stubSuccessfulQuery();
        double successfulCallsBefore = measurement(
                "resilience4j.circuitbreaker.calls", "COUNT", "name:rag", "kind:successful");
        double retriesNotNeededBefore = measurement(
                "resilience4j.retry.calls", "COUNT", "name:rag", "kind:successful_without_retry");

        queryResearchCorpusTool.query("test actuator metrics", 8);

        assertThat(metricNames()).contains(
                "resilience4j.circuitbreaker.calls",
                "resilience4j.circuitbreaker.state",
                "resilience4j.retry.calls",
                "resilience4j.ratelimiter.available.permissions");
        assertThat(measurement("resilience4j.circuitbreaker.calls", "COUNT", "name:rag", "kind:successful"))
                .isEqualTo(successfulCallsBefore + 1);
        assertThat(measurement("resilience4j.retry.calls", "COUNT", "name:rag", "kind:successful_without_retry"))
                .isEqualTo(retriesNotNeededBefore + 1);
        assertThat(measurement("resilience4j.circuitbreaker.state", "VALUE", "name:rag", "state:closed"))
                .isEqualTo(1.0);
        assertThat(measurement("resilience4j.ratelimiter.available.permissions", "VALUE", "name:rag"))
                .isBetween(0.0, 30.0);
    }

    private void stubSuccessfulQuery() {
        ragWireMock.stubFor(post(urlEqualTo("/query")).willReturn(okJson("""
                {"answer":"test answer","sources":[],"context_sufficient":true}
                """)));
    }

    private String metricNames() {
        return actuatorClient.get().uri("/actuator/metrics").retrieve().body(String.class);
    }

    private double measurement(String metric, String statistic, String... tags) {
        StringBuilder uri = new StringBuilder("/actuator/metrics/").append(metric);
        for (int index = 0; index < tags.length; index++) {
            uri.append(index == 0 ? '?' : '&').append("tag=").append(tags[index]);
        }
        JsonNode body = jsonMapper.readTree(actuatorClient.get().uri(uri.toString()).retrieve().body(String.class));
        for (JsonNode measurement : body.get("measurements")) {
            if (statistic.equals(measurement.get("statistic").asString())) {
                return measurement.get("value").asDouble();
            }
        }
        throw new AssertionError("No " + statistic + " measurement for " + uri);
    }
}
