package com.juancasimiro.mcpgateway.mcp;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.security.api-token=test-mcp-token",
        "resilience4j.retry.instances.rag.max-attempts=1",
        "resilience4j.ratelimiter.instances.rag.limit-for-period=1",
        "resilience4j.ratelimiter.instances.rag.limit-refresh-period=1h"
})
@EnableWireMock(@ConfigureWireMock(name = "rag-service", baseUrlProperties = "rag.base-url"))
class McpRateLimitTransportTest {

    private static final String SAFE_MESSAGE =
            "The research service request limit has been reached. Please try again later.";

    @LocalServerPort
    private int port;

    @InjectWireMock("rag-service")
    private WireMockServer ragWireMock;

    private McpSyncClient mcpClient;

    @BeforeEach
    void connect() {
        ragWireMock.resetAll();
        mcpClient = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer test-mcp-token")).build())
                .requestTimeout(Duration.ofSeconds(10)).build();
        mcpClient.initialize();
    }

    @AfterEach
    void disconnect() {
        if (mcpClient != null) {
            mcpClient.closeGracefully();
        }
    }

    @Test
    void deliversRateLimitRejectionAsSafeMcpToolErrorWithoutCallingUpstream() {
        ragWireMock.stubFor(post(urlEqualTo("/query")).willReturn(okJson("""
                {"answer":"test answer","sources":[],"context_sufficient":true,"insufficiency_reason":null}
                """)));

        var permitted = call(Map.of("question", "test question"));
        var rejected = call(Map.of("question", "test question"));

        assertThat(permitted.isError()).isFalse();
        assertThat(rejected.isError()).isTrue();
        // Spring AI 2.0.1 repeats the safe message while unwrapping its invocation
        // exception. Pin allowed content, not that incidental repetition count.
        assertThat(text(rejected).lines().toList()).isNotEmpty().allMatch(SAFE_MESSAGE::equals);
        assertThat(rejected.structuredContent()).isNull();
        ragWireMock.verify(1, postRequestedFor(urlEqualTo("/query")));
    }

    private McpSchema.CallToolResult call(Map<String, Object> arguments) {
        return mcpClient.callTool(new McpSchema.CallToolRequest("query_research_corpus", arguments, null));
    }

    private String text(McpSchema.CallToolResult result) {
        assertThat(result.content()).singleElement().isInstanceOf(McpSchema.TextContent.class);
        return ((McpSchema.TextContent) result.content().getFirst()).text();
    }
}
