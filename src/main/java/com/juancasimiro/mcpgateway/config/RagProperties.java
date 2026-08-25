package com.juancasimiro.mcpgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "rag")
record RagProperties (
        URI baseUrl,
        Duration connectionTimeout,
        Duration readTimeout){}

