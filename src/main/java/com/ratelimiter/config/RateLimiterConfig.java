package com.ratelimiter.config;

import com.google.common.util.concurrent.RateLimiter;
import lombok.Builder;
import lombok.Data;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Configuration for the rate limiter
 */
@Configuration
@EnableScheduling
@EnableAspectJAutoProxy
public class RateLimiterConfig {
    /**
     * Configuration for a specific method
     */
    @Data
    @Builder
    public static class MethodConfig {
        private final int ratePerSecond;
        private final int maxConcurrency;
        private final int memoryBufferSize;
        private final int maxRetries;
        private final RateLimiter rateLimiter;
        private final Semaphore concurrencyLimiter;

        /**
         * Create configuration for a method
         */
        public static MethodConfig create(int ratePerSecond, int maxConcurrency,
                                          int memoryBufferSize, int maxRetries) {
            return MethodConfig.builder()
                    .ratePerSecond(ratePerSecond)
                    .maxConcurrency(maxConcurrency)
                    .memoryBufferSize(memoryBufferSize)
                    .maxRetries(maxRetries)
                    .rateLimiter(RateLimiter.create(ratePerSecond))
                    .concurrencyLimiter(new Semaphore(maxConcurrency))
                    .build();
        }
    }

    /**
     * Registry of method configurations
     */
    @Bean
    public ConcurrentHashMap<String, MethodConfig> methodConfigs() {
        return new ConcurrentHashMap<>();
    }

    /**
     * Object mapper with serialization/deserialization
     */
    @Bean
    public com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .findAndRegisterModules();
    }
}
