package com.juancasimiro.mcpgateway.config;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.security.oauth2.server.resource.authentication.OpaqueTokenAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
public class GatewaySecurityConfiguration {
    @Bean
    OpaqueTokenIntrospector staticTokenIntrospector(@Value("${gateway.security.api-token}") String token) {
        return new StaticTokenIntrospector(token);
    }

    @Bean
    SecurityFilterChain gatewaySecurityFilterChain(HttpSecurity http, OpaqueTokenIntrospector introspector)
            throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, exception) -> {
            response.setHeader("WWW-Authenticate", "Bearer");
            response.setStatus(401);
        };
        // The resource-server DSL also publishes OAuth discovery metadata in Security 7.1.
        // Use its bearer filter directly: this local demo has no OAuth authorization server.
        var bearerFilter = new BearerTokenAuthenticationFilter(
                new ProviderManager(new OpaqueTokenAuthenticationProvider(introspector)));
        bearerFilter.setAuthenticationEntryPoint(unauthorized);
        return http
                // Credentials are explicit bearer headers, never browser cookies.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(requests -> requests
                        // Async MCP responses and error rendering follow an already checked request.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(unauthorized))
                .addFilterBefore(bearerFilter, BasicAuthenticationFilter.class)
                .build();
    }
}
