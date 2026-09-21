package com.juancasimiro.mcpgateway.config;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "gateway.security.api-token=test-private-token")
@EnableWireMock(@ConfigureWireMock(name = "rag-service", baseUrlProperties = "rag.base-url"))
class GatewayAuthenticationTest {
    private static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
            "protocolVersion":"2025-03-26","capabilities":{},
            "clientInfo":{"name":"test authentication client","version":"1.0"}}}
            """;

    @LocalServerPort
    private int port;

    @InjectWireMock("rag-service")
    private WireMockServer ragWireMock;

    private final HttpClient mcpHttpClient = HttpClient.newHttpClient();

    @BeforeEach
    void resetUpstream() {
        ragWireMock.resetAll();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Bearer test-wrong-token", "Bearer local-demo-token"})
    void rejectsMissingWrongAndReplacedDemoTokens(String authorization) throws Exception {
        var response = send("POST", "/mcp", INITIALIZE, authorization, null);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(
                value -> assertThat(value).isEqualTo("Bearer"));
        assertThat(ragWireMock.getAllServeEvents()).isEmpty();
    }

    @Test
    void acceptsConfiguredTokenForInitializationWithoutCreatingAnAuthenticationCookie() throws Exception {
        var response = send("POST", "/mcp", INITIALIZE, "Bearer test-private-token", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("protocolVersion");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "DELETE"})
    void existingMcpSessionDoesNotBypassAuthentication(String method) throws Exception {
        var initialized = send("POST", "/mcp", INITIALIZE, "Bearer test-private-token", null);
        assertThat(initialized.statusCode()).isEqualTo(200);
        String session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
        try {
            String toolCall = """
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                    "name":"query_research_corpus","arguments":{"question":"test rejected question"}}}
                    """;
            var response = send(method, "/mcp", toolCall, "", session);
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(ragWireMock.getAllServeEvents()).isEmpty();
        } finally {
            send("DELETE", "/mcp", "", "Bearer test-private-token", session);
        }
    }

    @Test
    void rejectsMalformedBearerHeaderWithoutCallingUpstream() throws Exception {
        var response = send("POST", "/mcp", INITIALIZE, "Bearer test token with spaces", null);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(ragWireMock.getAllServeEvents()).isEmpty();
    }

    @Test
    void doesNotAcceptTokenInQueryString() throws Exception {
        var response = send("POST", "/mcp?access_token=test-private-token", INITIALIZE, "", null);
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void doesNotPublishOAuthDiscoveryForTheStaticTokenDemo() throws Exception {
        var response = send("GET", "/.well-known/oauth-protected-resource", "", "", null);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).doesNotContain("authorization_servers", "bearer_methods_supported");
    }

    @Test
    void healthIsPublicAndMinimal() throws Exception {
        var response = send("GET", "/actuator/health", "", "", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"")
                .doesNotContain("\"components\"", "\"details\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator", "/actuator/metrics", "/actuator/circuitbreakers",
            "/actuator/circuitbreakerevents", "/actuator/retries", "/actuator/ratelimiters"})
    void otherActuatorEndpointsRequireAuthentication(String path) throws Exception {
        assertThat(send("GET", path, "", "", null).statusCode()).isEqualTo(401);
        assertThat(send("GET", path, "", "Bearer test-private-token", null).statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> send(String method, String path, String body,
                                      String authorization, String session) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .method(method, "POST".equals(method)
                        ? HttpRequest.BodyPublishers.ofString(body) : HttpRequest.BodyPublishers.noBody());
        if (!authorization.isEmpty()) {
            request.header("Authorization", authorization);
        }
        if (session != null) {
            request.header("Mcp-Session-Id", session);
        }
        return mcpHttpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
