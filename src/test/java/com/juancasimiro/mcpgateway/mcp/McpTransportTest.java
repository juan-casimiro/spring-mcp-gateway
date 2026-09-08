package com.juancasimiro.mcpgateway.mcp;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "resilience4j.retry.instances.rag.max-attempts=1",
        "resilience4j.circuitbreaker.instances.rag.minimum-number-of-calls=100",
        "resilience4j.circuitbreaker.instances.rag.sliding-window-size=100",
        "resilience4j.ratelimiter.instances.rag.limit-for-period=10000"
})
@EnableWireMock(@ConfigureWireMock(name = "rag-service", baseUrlProperties = "rag.base-url"))
class McpTransportTest {

    @LocalServerPort
    private int port;

    @InjectWireMock("rag-service")
    private WireMockServer wireMock;

    private McpSyncClient client;

    @BeforeEach
    void connect() {
        wireMock.resetAll();
        client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port).build())
                .requestTimeout(Duration.ofSeconds(10)).build();
        client.initialize();
    }

    @AfterEach
    void disconnect() {
        if (client != null) {
            client.closeGracefully();
        }
    }

    @Test
    void discoversToolWithRequiredQuestionOptionalCountAndDocumentedBounds() {
        assertThat(client.listTools().tools()).singleElement().satisfies(tool -> {
            assertThat(tool.name()).isEqualTo("query_research_corpus");
            var schema = JsonMapper.builder().build().valueToTree(tool.inputSchema());
            assertThat(schema.get("required").toString()).isEqualTo("[\"question\"]");
            assertThat(schema.at("/properties/question/type").asString()).isEqualTo("string");
            assertThat(schema.at("/properties/question/description").asString()).contains("1,000", "trimming");
            assertThat(schema.at("/properties/resultCount/type").asString()).isEqualTo("integer");
            assertThat(schema.at("/properties/resultCount/description").asString()).contains("1 and 20");
        });
    }

    @ParameterizedTest
    @CsvSource({"true,", "false,test missing evidence"})
    void returnsResearchOutcomeAsSuccessfulMcpResult(boolean sufficient, String reason) {
        String body = """
                {"answer":"test answer","sources":["z-source","a-source"],
                 "context_sufficient":%s,"insufficiency_reason":%s}
                """.formatted(sufficient, reason == null ? "null" : "\"" + reason + "\"");
        wireMock.stubFor(post(urlEqualTo("/query")).willReturn(okJson(body)));

        var result = call(Map.of("question", "  test question  "));

        assertThat(result.isError()).isFalse();
        var json = JsonMapper.builder().build().readTree(text(result));
        assertThat(json.get("answer").asString()).isEqualTo("test answer");
        assertThat(json.get("sources").toString()).isEqualTo("[\"z-source\",\"a-source\"]");
        assertThat(json.get("contextSufficient").asBoolean()).isEqualTo(sufficient);
        if (reason == null) {
            assertThat(json.get("insufficiencyReason").isNull()).isTrue();
        } else {
            assertThat(json.get("insufficiencyReason").asString()).isEqualTo(reason);
        }
        wireMock.verify(1, postRequestedFor(urlEqualTo("/query")).withRequestBody(equalToJson("""
                {"question":"test question","n_results":8,"use_bm25":false,"use_query_rewriting":false}
                """)));
    }

    @ParameterizedTest
    @CsvSource({
            "503,The research corpus is currently unavailable. Do not answer from general knowledge; tell the user that retrieval failed.",
            "504,The research request timed out. Do not answer from general knowledge; tell the user that retrieval did not complete.",
            "422,The research service could not process this request. This is an internal error; do not retry with the same input."
    })
    void deliversTechnicalFailureAsSafeMcpToolError(int status, String message) {
        wireMock.stubFor(post(urlEqualTo("/query")).willReturn(aResponse().withStatus(status)
                .withBody("test upstream diagnostic that must not reach the caller")));

        var result = call(Map.of("question", "test question", "resultCount", 3));

        assertThat(result.isError()).isTrue();
        assertSafeError(result, message);
        assertThat(result.structuredContent()).isNull();
        wireMock.verify(1, postRequestedFor(urlEqualTo("/query"))
                .withRequestBody(matchingJsonPath("$.n_results", equalTo("3"))));
    }

    @Test
    void rejectsInvalidInputAsMcpErrorWithoutCallingUpstream() {
        var result = call(Map.of("question", "test question", "resultCount", 21));

        assertThat(result.isError()).isTrue();
        assertSafeError(result, "The result count must be between 1 and 20.");
        wireMock.verify(0, postRequestedFor(urlEqualTo("/query")));
    }

    private void assertSafeError(McpSchema.CallToolResult result, String message) {
        // Spring AI 2.0.1 repeats the safe message while unwrapping its invocation
        // exception. Pin allowed content, not that incidental repetition count.
        assertThat(text(result).lines().toList()).isNotEmpty().allMatch(message::equals);
    }

    private McpSchema.CallToolResult call(Map<String, Object> arguments) {
        return client.callTool(new McpSchema.CallToolRequest("query_research_corpus", arguments, null));
    }

    private String text(McpSchema.CallToolResult result) {
        assertThat(result.content()).singleElement().isInstanceOf(McpSchema.TextContent.class);
        return ((McpSchema.TextContent) result.content().getFirst()).text();
    }
}
