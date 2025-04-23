package com.ratelimiter.core;

import com.ratelimiter.config.RateLimiterConfig.MethodConfig;
import com.ratelimiter.model.MessageWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Background processor that pulls messages from buffer and processes them
 * at the configured rate and concurrency
 */
@Component
@Slf4j
public class MessageProcessor {
    // Add a ThreadLocal flag to indicate processing from buffer
    private static final ThreadLocal<Boolean> PROCESSING_FROM_BUFFER = ThreadLocal.withInitial(() -> false);

    private final HybridMessageBuffer messageBuffer;
    private final ConcurrentHashMap<String, MethodConfig> methodConfigs;
    private final ApplicationContext applicationContext;
    private final Map<String, Object> targetBeans = new ConcurrentHashMap<>();
    private final Map<String, Method> targetMethods = new ConcurrentHashMap<>();
    private final Map<String, Class<?>> targetPayloadTypes = new ConcurrentHashMap<>();

    @Autowired
    public MessageProcessor(
            HybridMessageBuffer messageBuffer,
            ConcurrentHashMap<String, MethodConfig> methodConfigs,
            ApplicationContext applicationContext) {
        this.messageBuffer = messageBuffer;
        this.methodConfigs = methodConfigs;
        this.applicationContext = applicationContext;
    }

    /**
     * Public accessor for the thread local flag
     */
    public static boolean isProcessingFromBuffer() {
        return PROCESSING_FROM_BUFFER.get();
    }

    /**
     * Process messages for all registered methods at their configured rates
     */
    @Scheduled(fixedRate = 100) // 100ms for responsive processing
    @Transactional
    public void processMessages() {
        try {
            // First replenish memory buffers from database
            messageBuffer.replenishBuffers();

            // Process for each method
            methodConfigs.forEach((methodSignature, config) -> {
                // Only proceed if both rate and concurrency permits available
                if (config.getRateLimiter().tryAcquire() &&
                        config.getConcurrencyLimiter().tryAcquire()) {
                    try {
                        processNextMessageForMethod(methodSignature, config);
                    } finally {
                        config.getConcurrencyLimiter().release();
                    }
                }
            });
        } catch (Exception e) {
            log.error("Error in message processor", e);
        }
    }

    /**
     * Process next message for a specific method
     */
    private void processNextMessageForMethod(String methodSignature, MethodConfig config) {
        try {
            // Get next message from buffer
            Optional<MessageWrapper<?>> messageOpt = messageBuffer.getNextMessage(methodSignature);

            if (messageOpt.isPresent()) {
                MessageWrapper<?> wrapper = messageOpt.get();

                try {
                    // Try to find target bean and method
                    Object bean = findTargetBean(methodSignature);
                    Method method = findTargetMethod(methodSignature, bean);

                    if (bean != null && method != null) {
                        // Set flag to indicate we're processing from buffer
                        PROCESSING_FROM_BUFFER.set(true);
                        try {
                            // DIRECT INVOCATION - call the actual method on the bean
                            log.debug("Directly invoking method {} on bean {}", method.getName(), bean.getClass().getSimpleName());
                            Object result = method.invoke(bean, wrapper.getPayload());
                            log.debug("Successfully processed message with actual result: {}", result);
                        } finally {
                            // Clear flag after processing
                            PROCESSING_FROM_BUFFER.set(false);
                        }
                    } else {
                        log.warn("Could not find bean or method for {}", methodSignature);
                    }
                } catch (InvocationTargetException e) {
                    // Unwrap the exception thrown by the target method
                    log.error("Error in target method execution: {}", e.getCause().getMessage(), e.getCause());

                    // Retry logic
                    handleRetry(wrapper, methodSignature, e.getCause().getMessage(), config);
                } catch (Exception e) {
                    log.error("Error processing message: {}", e.getMessage(), e);

                    // Retry logic
                    handleRetry(wrapper, methodSignature, e.getMessage(), config);
                }
            }
        } catch (Exception e) {
            log.error("Unexpected error in message processor", e);
        }
    }

    /**
     * Handle retry logic for failed messages
     */
    private void handleRetry(MessageWrapper<?> wrapper, String methodSignature, String errorMessage, MethodConfig config) {
        int retryCount = wrapper.incrementRetryCount();
        if (retryCount < config.getMaxRetries()) {
            // Re-buffer for retry
            messageBuffer.bufferMessage(wrapper.getPayload(), methodSignature);
            log.debug("Message requeued for retry {}/{}",
                    retryCount, config.getMaxRetries());
        } else {
            // Move to dead letter
            messageBuffer.moveToDeadLetter(wrapper, errorMessage);
        }
    }

    /**
     * Improved method to find the target bean
     */
    private Object findTargetBean(String methodSignature) {
        // First check cache
        Object cachedBean = targetBeans.get(methodSignature);
        if (cachedBean != null) {
            return cachedBean;
        }

        String className = methodSignature.substring(0, methodSignature.lastIndexOf('.'));
        log.debug("Looking for bean of class: {}", className);

        try {
            // First try getting the bean by type
            Class<?> clazz = Class.forName(className);
            Object bean = null;

            try {
                bean = applicationContext.getBean(clazz);
                log.debug("Found bean by type: {}", clazz.getName());
            } catch (NoSuchBeanDefinitionException e) {
                // Try getting by bean name (last part of class name)
                String beanName = className.substring(className.lastIndexOf('.') + 1);

                // Try original case first
                try {
                    bean = applicationContext.getBean(beanName);
                    log.debug("Found bean by name: {}", beanName);
                } catch (NoSuchBeanDefinitionException e2) {
                    // Try lowercase (Spring convention)
                    try {
                        String lcBeanName = beanName.substring(0, 1).toLowerCase() + beanName.substring(1);
                        bean = applicationContext.getBean(lcBeanName);
                        log.debug("Found bean by lowercase name: {}", lcBeanName);
                    } catch (NoSuchBeanDefinitionException e3) {
                        log.warn("No bean found for class {} or names {} and {}",
                                className, beanName, beanName.toLowerCase());
                    }
                }
            }

            if (bean != null) {
                targetBeans.put(methodSignature, bean);
                return bean;
            }
        } catch (ClassNotFoundException e) {
            log.warn("Class not found: {}", className);
        } catch (Exception e) {
            log.warn("Error finding bean for {}: {}", methodSignature, e.getMessage());
        }

        return null;
    }

    /**
     * Improved method to find the target method
     */
    private Method findTargetMethod(String methodSignature, Object bean) {
        // First check cache
        Method cachedMethod = targetMethods.get(methodSignature);
        if (cachedMethod != null) {
            return cachedMethod;
        }

        if (bean == null) {
            return null;
        }

        String methodName = methodSignature.substring(methodSignature.lastIndexOf('.') + 1);
        // Extract method name without parameters
        if (methodName.contains("(")) {
            methodName = methodName.substring(0, methodName.indexOf('('));
        }

        log.debug("Looking for method {} on bean {}", methodName, bean.getClass().getName());

        // Look for method on the bean
        for (Method method : bean.getClass().getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                method.setAccessible(true);
                targetMethods.put(methodSignature, method);
                return method;
            }
        }

        // Check implemented interfaces too
        for (Class<?> iface : bean.getClass().getInterfaces()) {
            try {
                Method method = iface.getDeclaredMethod(methodName, getParameterTypes(methodSignature));
                if (method != null) {
                    method.setAccessible(true);
                    targetMethods.put(methodSignature, method);
                    return method;
                }
            } catch (Exception e) {
                // Method not found on this interface, continue
            }
        }

        log.warn("Method {} not found on bean {}", methodName, bean.getClass().getName());
        return null;
    }

    /**
     * Helper method to get parameter types for a method
     */
    private Class<?>[] getParameterTypes(String methodSignature) {
        // For now, assume a single parameter
        Class<?> payloadType = getTargetPayloadType(methodSignature);
        return payloadType != null ? new Class<?>[] { payloadType } : new Class<?>[0];
    }

    /**
     * Get the expected payload type for a method
     */
    private Class<?> getTargetPayloadType(String methodSignature) {
        Class<?> cachedType = targetPayloadTypes.get(methodSignature);
        if (cachedType != null) {
            return cachedType;
        }

        Object bean = findTargetBean(methodSignature);
        if (bean == null) {
            return null;
        }

        String methodName = methodSignature.substring(methodSignature.lastIndexOf('.') + 1);
        if (methodName.contains("(")) {
            methodName = methodName.substring(0, methodName.indexOf('('));
        }

        // Look for method on the bean
        for (Method method : bean.getClass().getDeclaredMethods()) {
            if (method.getName().equals(methodName) && method.getParameterCount() > 0) {
                Class<?> type = method.getParameterTypes()[0];
                targetPayloadTypes.put(methodSignature, type);
                return type;
            }
        }

        return null;
    }
}