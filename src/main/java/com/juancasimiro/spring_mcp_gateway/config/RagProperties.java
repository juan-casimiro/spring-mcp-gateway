package com.juancasimiro.spring_mcp_gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "rag")
record RagProperties (
        URI baseUrl,
        Duration connectionTimeout,
        Duration readTimeout){}

