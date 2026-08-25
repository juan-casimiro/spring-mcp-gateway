package com.juancasimiro.spring_mcp_gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RagProperties.class)
public class RagClientConfiguration {
    @Bean
    RestClient ragRestClient(RestClient.Builder builder, RagProperties ragProperties) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(ragProperties.connectionTimeout());
        requestFactory.setReadTimeout(ragProperties.readTimeout());

        return builder
                .requestFactory(requestFactory)
                .baseUrl(ragProperties.baseUrl())
                .build();
    }
}
