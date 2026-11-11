package com.compliance.gateway.config;

import org.springframework.context.annotation.Configuration;

@Configuration
public class WebConfig {
    // CORS configuration is handled by CorsFilter in GatewayApplication.java
    // This prevents duplicate CORS configuration
}