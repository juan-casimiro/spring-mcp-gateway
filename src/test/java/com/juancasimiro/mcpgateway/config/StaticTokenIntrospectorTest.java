package com.juancasimiro.mcpgateway.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class StaticTokenIntrospectorTest {
    @Test
    void authenticatesWithoutIncludingCredentialsInPrincipal() {
        var principal = new StaticTokenIntrospector("test-private-token").introspect("test-private-token");
        assertThat(principal.getName()).isEqualTo("gateway-client");
        assertThat(principal.getAttributes()).containsOnlyKeys("sub").containsEntry("sub", "gateway-client");
        assertThat(principal.getAuthorities()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "test-private-tokeN", "test-private-token-longer", "local-demo-token"})
    void rejectsTokensThatDoNotMatchExactly(String supplied) {
        var introspector = new StaticTokenIntrospector("test-private-token");
        assertThatThrownBy(() -> introspector.introspect(supplied))
                .isInstanceOf(BadOpaqueTokenException.class).hasMessage("Invalid bearer token");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void refusesBlankConfiguration(String configured) {
        assertThatThrownBy(() -> new StaticTokenIntrospector(configured))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("gateway.security.api-token must not be blank");
    }

    @Test
    void warnsAboutDemoCredentialWithoutPrintingIt(CapturedOutput output) {
        var introspector = new StaticTokenIntrospector("local-demo-token");
        assertThat(introspector.introspect("local-demo-token").getName()).isEqualTo("gateway-client");
        assertThat(output).contains("public demo API token is active", "MCP_API_TOKEN")
                .doesNotContain("local-demo-token");
    }

    @Test
    void privateConfigurationDoesNotProduceDemoWarningOrLeakToken(CapturedOutput output) {
        new StaticTokenIntrospector("test-private-token");
        assertThat(output).doesNotContain("public demo API token is active", "test-private-token");
    }
}
