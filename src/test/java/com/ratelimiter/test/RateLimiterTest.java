package com.ratelimiter.test;

import com.ratelimiter.annotation.RateLimited;
import com.ratelimiter.config.RateLimiterConfig;
import com.ratelimiter.core.HybridMessageBuffer;
import com.ratelimiter.core.MessageProcessor;
import com.ratelimiter.core.RateLimitingAspect;
import com.ratelimiter.model.MessageWrapper;
import com.ratelimiter.repository.MessageRepository;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RateLimiterTest {

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private ApplicationContext applicationContext;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private MethodSignature methodSignature;

    private RateLimitingAspect aspect;
    private HybridMessageBuffer buffer;
    private String testMessage;
    private RateLimited annotation;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        // Set up ObjectMapper
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .findAndRegisterModules();

        // Set up test message
        testMessage = "Test message " + UUID.randomUUID();

        // Set up annotation
        annotation = Mockito.mock(RateLimited.class);
        when(annotation.ratePerSecond()).thenReturn(10);
        when(annotation.maxConcurrency()).thenReturn(5);
        when(annotation.memoryBufferSize()).thenReturn(20);
        when(annotation.maxRetries()).thenReturn(3);

        // Set up method signature
        when(joinPoint.getSignature()).thenReturn(methodSignature);
        when(methodSignature.getDeclaringType()).thenReturn((Class) TestController.class);
        when(methodSignature.getName()).thenReturn("handleMessage");
        when(methodSignature.getReturnType()).thenReturn((Class) ResponseEntity.class);

        // Set up join point args
        when(joinPoint.getArgs()).thenReturn(new Object[]{testMessage});

        // Set up components
        ConcurrentHashMap<String, RateLimiterConfig.MethodConfig> methodConfigs = new ConcurrentHashMap<>();
        buffer = new HybridMessageBuffer(messageRepository, objectMapper);
        aspect = new RateLimitingAspect(buffer, methodConfigs);
    }

    @Test
    void testAspectBuffersMessageAndReturnsOk() throws Throwable {
        // Act
        Object result = aspect.rateLimit(joinPoint, annotation);

        // Assert
        assertNotNull(result);
        assertTrue(result instanceof ResponseEntity);
        assertEquals(200, ((ResponseEntity<?>) result).getStatusCodeValue());

        // Verify we tried to buffer the message
        verify(messageRepository, times(0)).save(any()); // Should use memory buffer first
    }

    @Test
    void testProcessorImplementation() {
        // Set up
        ConcurrentHashMap<String, RateLimiterConfig.MethodConfig> methodConfigs = new ConcurrentHashMap<>();
        MessageProcessor processor = new MessageProcessor(buffer, methodConfigs, applicationContext);

        // Register a method config for testing
        String methodSignature = "com.test.TestController.handleMessage";
        RateLimiterConfig.MethodConfig config = RateLimiterConfig.MethodConfig.create(
                10, 5, 20, 3);
        methodConfigs.put(methodSignature, config);

        // Act - this would normally be called by Spring scheduler
        processor.processMessages();

        // Just verify no exceptions are thrown
        // In a real test, we would mock more behavior and verify processing
    }

    // Sample test controller class for testing
    static class TestController {
        @RateLimited(ratePerSecond = 10, maxConcurrency = 5)
        public ResponseEntity<?> handleMessage(String message) {
            return ResponseEntity.ok().build();
        }
    }
}
