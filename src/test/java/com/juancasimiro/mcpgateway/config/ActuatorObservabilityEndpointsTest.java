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

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnableWireMock(@ConfigureWireMock(name = "rag-service", baseUrlProperties = "rag.base-url"))
class ActuatorObservabilityEndpointsTest {

    @LocalServerPort
    private int port;

    @InjectWireMock("rag-service")
    private WireMockServer ragWireMock;

    @Autowired
    private QueryResearchCorpusTool queryResearchCorpusTool;

    private RestClient actuatorClient;

    @BeforeEach
    void setUp() {
        ragWireMock.resetAll();
        actuatorClient = RestClient.create("http://localhost:" + port);
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
        ragWireMock.stubFor(post(urlEqualTo("/query")).willReturn(okJson("""
                {"answer":"test answer","sources":[],"context_sufficient":true}
                """)));

        queryResearchCorpusTool.query("test actuator metrics", 8);

        String metricNames = actuatorClient.get().uri("/actuator/metrics").retrieve().body(String.class);
        assertThat(metricNames).contains("mcp.tool.duration");

        String metricDetail = actuatorClient.get()
                .uri("/actuator/metrics/mcp.tool.duration?tag=tool:query_research_corpus&tag=outcome:success")
                .retrieve().body(String.class);
        assertThat(metricDetail).contains("\"COUNT\"");
    }
}
