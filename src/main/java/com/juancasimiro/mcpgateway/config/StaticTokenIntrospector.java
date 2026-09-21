package com.juancasimiro.mcpgateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/** Uses Spring's introspection extension point for local validation; no remote issuer is contacted. */
final class StaticTokenIntrospector implements OpaqueTokenIntrospector {
    private static final Logger logger = LoggerFactory.getLogger(StaticTokenIntrospector.class);
    private final byte[] expectedToken;

    StaticTokenIntrospector(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("gateway.security.api-token must not be blank");
        }
        expectedToken = token.getBytes(StandardCharsets.UTF_8);
        if ("local-demo-token".equals(token)) {
            logger.warn("The public demo API token is active. Set MCP_API_TOKEN before exposing the gateway beyond localhost.");
        }
    }

    @Override
    public OAuth2AuthenticatedPrincipal introspect(String token) {
        if (!MessageDigest.isEqual(expectedToken, token.getBytes(StandardCharsets.UTF_8))) {
            throw new BadOpaqueTokenException("Invalid bearer token");
        }
        return new DefaultOAuth2AuthenticatedPrincipal("gateway-client",
                Map.of("sub", "gateway-client"), List.of());
    }
}
