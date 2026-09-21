package com.juancasimiro.mcpgateway.config;

import com.juancasimiro.mcpgateway.SpringMcpGatewayApplication;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayStartupTokenValidationTest {

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void applicationRefusesToStartWithBlankConfiguredToken(String configuredToken) {
        var application = new SpringApplicationBuilder(SpringMcpGatewayApplication.class)
                .web(WebApplicationType.SERVLET);

        // Command-line arguments outrank application.yml; builder properties would not.
        assertThatThrownBy(() -> application.run("--server.port=0",
                "--gateway.security.api-token=" + configuredToken))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause()
                .hasMessage("gateway.security.api-token must not be blank");
    }
}
