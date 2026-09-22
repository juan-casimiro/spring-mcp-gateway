package com.juancasimiro.mcpgateway.config;

import com.juancasimiro.mcpgateway.SpringMcpGatewayApplication;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CustomHealthPathAuthenticationTest {
    @ParameterizedTest
    @CsvSource({
            "/manage, health, /manage/health, /manage/metrics",
            "/manage, ready, /manage/ready, /manage/metrics",
            "/, ready, /ready, /metrics"
    })
    void onlyTheConfiguredHealthRootIsPublic(String basePath, String healthMapping,
                                            String healthPath, String metricsPath) throws Exception {
        // Command-line arguments outrank application.yml, just as deployment overrides do.
        try (var application = new SpringApplicationBuilder(SpringMcpGatewayApplication.class).run(
                "--server.port=0", "--server.address=127.0.0.1",
                "--server.servlet.context-path=/test-gateway",
                "--management.endpoints.web.base-path=" + basePath,
                "--management.endpoints.web.path-mapping.health=" + healthMapping,
                "--gateway.security.api-token=test-health-path-token");
             var actuatorHttpClient = HttpClient.newHttpClient()) {
            int port = ((WebServerApplicationContext) application).getWebServer().getPort();
            String gatewayUrl = "http://127.0.0.1:" + port + "/test-gateway";

            var health = get(actuatorHttpClient, gatewayUrl + healthPath, false);
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(health.body()).contains("\"status\":\"UP\"")
                    .doesNotContain("\"components\"", "\"details\"");

            assertThat(get(actuatorHttpClient, gatewayUrl + metricsPath, false).statusCode()).isEqualTo(401);
            assertThat(get(actuatorHttpClient, gatewayUrl + metricsPath, true).statusCode()).isEqualTo(200);
            assertThat(get(actuatorHttpClient, gatewayUrl + "/mcp", false).statusCode()).isEqualTo(401);
            assertThat(get(actuatorHttpClient, gatewayUrl + "/actuator/health", false).statusCode()).isEqualTo(401);
            assertThat(get(actuatorHttpClient, gatewayUrl + healthPath + "/liveness", false).statusCode())
                    .isEqualTo(401);
        }
    }

    private HttpResponse<String> get(HttpClient actuatorHttpClient, String url, boolean authenticated)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10));
        if (authenticated) {
            request.header("Authorization", "Bearer test-health-path-token");
        }
        return actuatorHttpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
