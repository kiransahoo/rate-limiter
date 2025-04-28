package com.ratelimiter.core;

import com.ratelimiter.annotation.RateLimited;
import com.ratelimiter.config.RateLimiterConfig.MethodConfig;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aspect that intercepts methods annotated with @RateLimited
 */
@Aspect
@Component
@Slf4j
public class RateLimitingAspect {
    private final HybridMessageBuffer messageBuffer;
    private final ConcurrentHashMap<String, MethodConfig> methodConfigs;

    @Autowired
    public RateLimitingAspect(
            HybridMessageBuffer messageBuffer,
            ConcurrentHashMap<String, MethodConfig> methodConfigs) {
        this.messageBuffer = messageBuffer;
        this.methodConfigs = methodConfigs;
    }

    @PostConstruct
    public void init() {
        System.out.println("=== RATE LIMITING ASPECT INITIALIZED ===");
        log.info("Rate Limiting Aspect initialized successfully");
    }


    /**
     * Intercept methods annotated with @RateLimited
     */
    @Around("@annotation(rateLimited)")
    public Object rateLimit(ProceedingJoinPoint joinPoint, RateLimited rateLimited) throws Throwable {
        // IMPORTANT: Skip rate limiting if we're processing from buffer
        if (MessageProcessor.isProcessingFromBuffer()) {
            log.debug("Bypassing rate limiting for message processing from buffer");
            return joinPoint.proceed();
        }

        // Extract method signature
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String methodSignature = signature.getDeclaringType().getName() + "." + signature.getName();

        // Get first argument as payload (if exists)
        Object[] args = joinPoint.getArgs();
        if (args.length == 0) {
            log.warn("No arguments found in method, proceeding without rate limiting");
            return joinPoint.proceed();
        }

        Object payload = args[0];

        // Register/update method configuration
        registerMethodConfig(methodSignature, rateLimited);

        // Get the method config
        MethodConfig config = methodConfigs.get(methodSignature);

        // Check if we can process immediately (within rate and concurrency limits)
        if (config != null && config.getRateLimiter().tryAcquire() &&
                config.getConcurrencyLimiter().tryAcquire()) {
            try {
                // Actually invoke the method immediately
                log.debug("Processing message immediately within rate limits");
                return joinPoint.proceed();
            } finally {
                config.getConcurrencyLimiter().release();
            }
        } else {
            // Buffer the message for later processing
            messageBuffer.bufferMessage(payload, methodSignature);
            log.debug("Message buffered for rate-limited processing");

            // Acknowledge receipt without actually processing
            if (signature.getReturnType().equals(ResponseEntity.class)) {
                return ResponseEntity.accepted().build(); // Use 202 Accepted to indicate async processing
            } else {
                return null;
            }
        }
    }

    /**
     * Register or update method configuration
     */
    private void registerMethodConfig(String methodSignature, RateLimited config) {
        methodConfigs.compute(methodSignature, (key, existingConfig) -> {
            if (existingConfig == null ||
                    existingConfig.getRatePerSecond() != config.ratePerSecond() ||
                    existingConfig.getMaxConcurrency() != config.maxConcurrency()) {

                log.info("Registering rate limited method: {} with rate: {}/s, concurrency: {}, bufferSize: {}",
                        methodSignature, config.ratePerSecond(), config.maxConcurrency(),
                        config.memoryBufferSize());

                // Register with buffer
                messageBuffer.registerMethod(methodSignature, config.memoryBufferSize());

                // Create new config
                return MethodConfig.create(
                        config.ratePerSecond(),
                        config.maxConcurrency(),
                        config.memoryBufferSize(),
                        config.maxRetries()
                );
            }
            return existingConfig;
        });
    }
}