package com.ratelimiter.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.model.MessageStatus;
import com.ratelimiter.model.MessageWrapper;
import com.ratelimiter.model.PendingMessage;
import com.ratelimiter.repository.MessageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A hybrid buffer that keeps messages in memory when possible
 * and overflows to database when memory buffer is full
 */
@Component
@Slf4j
public class HybridMessageBuffer {
    private final MessageRepository repository;
    private final ObjectMapper objectMapper;
    
    // Separate memory buffer per target method
    private final ConcurrentHashMap<String, BufferContext> methodBuffers = new ConcurrentHashMap<>();
    
    public HybridMessageBuffer(MessageRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }
    
    /**
     * Registers a method and ensures a buffer is created for it
     */
    public void registerMethod(String methodSignature, int bufferSize) {
        methodBuffers.computeIfAbsent(methodSignature, k -> {
            log.info("Creating buffer for method: {} with size: {}", methodSignature, bufferSize);
            return new BufferContext(bufferSize);
        });
    }
    
    /**
     * Buffer a message, using memory first then database if full
     */
    public <T> void bufferMessage(T payload, String targetMethod) {
        BufferContext bufferContext = getOrCreateBuffer(targetMethod);
        MessageWrapper<T> wrapper = new MessageWrapper<>(payload, targetMethod);
        
        // Try to add to memory buffer first
        boolean addedToMemory = bufferContext.memoryBuffer.offer(wrapper);
        
        // If memory buffer is full, save to database
        if (!addedToMemory) {
            try {
                PendingMessage dbMessage = convertToDbEntity(wrapper);
                repository.save(dbMessage);
                bufferContext.overflowCount.incrementAndGet();
                log.debug("Memory buffer full, message saved to database: {}", generateId(payload));
            } catch (Exception e) {
                log.error("Failed to save overflow message to database", e);
            }
        }
    }
    
    /**
     * Get next message to process for a specific method
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<MessageWrapper<T>> getNextMessage(String targetMethod, Class<T> payloadType) {
        BufferContext bufferContext = getOrCreateBuffer(targetMethod);
        
        // First try memory buffer
        MessageWrapper<?> wrapper = bufferContext.memoryBuffer.poll();
        if (wrapper != null && payloadType.isInstance(wrapper.getPayload())) {
            return Optional.of((MessageWrapper<T>) wrapper);
        }
        
        // If memory buffer empty or type mismatch, try database
        if (bufferContext.overflowCount.get() > 0) {
            Optional<PendingMessage> dbMessageOpt = repository
                    .findFirstByStatusAndTargetMethodOrderByCreatedAtAsc(
                        MessageStatus.PENDING, targetMethod);
                        
            if (dbMessageOpt.isPresent()) {
                PendingMessage dbMessage = dbMessageOpt.get();
                try {
                    // Convert to memory format
                    MessageWrapper<T> loadedWrapper = convertFromDbEntity(dbMessage, payloadType);
                    
                    // Remove from database
                    repository.delete(dbMessage);
                    bufferContext.overflowCount.decrementAndGet();
                    
                    return Optional.of(loadedWrapper);
                } catch (Exception e) {
                    log.error("Failed to process database message: {}", dbMessage.getId(), e);
                }
            }
        }
        
        // Nothing to process
        return Optional.empty();
    }
    
    /**
     * Get next message to process for a specific method (without type parameter)
     */
    public Optional<MessageWrapper<?>> getNextMessage(String targetMethod) {
        BufferContext bufferContext = getOrCreateBuffer(targetMethod);
        
        // First try memory buffer
        MessageWrapper<?> wrapper = bufferContext.memoryBuffer.poll();
        if (wrapper != null) {
            return Optional.of(wrapper);
        }
        
        // If memory buffer empty, try database
        if (bufferContext.overflowCount.get() > 0) {
            Optional<PendingMessage> dbMessageOpt = repository
                    .findFirstByStatusAndTargetMethodOrderByCreatedAtAsc(
                        MessageStatus.PENDING, targetMethod);
                        
            if (dbMessageOpt.isPresent()) {
                PendingMessage dbMessage = dbMessageOpt.get();
                try {
                    // Convert to memory format
                    MessageWrapper<?> loadedWrapper = convertFromDbEntity(dbMessage);
                    
                    // Remove from database
                    repository.delete(dbMessage);
                    bufferContext.overflowCount.decrementAndGet();
                    
                    return Optional.of(loadedWrapper);
                } catch (Exception e) {
                    log.error("Failed to process database message: {}", dbMessage.getId(), e);
                }
            }
        }
        
        // Nothing to process
        return Optional.empty();
    }
    
    /**
     * Move messages from database back to memory when space available
     */
    public void replenishBuffers() {
        methodBuffers.forEach((methodSignature, bufferContext) -> {
            // Only replenish if we have overflow and buffer space
            int availableCapacity = bufferContext.memoryBuffer.remainingCapacity();
            int overflowCount = bufferContext.overflowCount.get();
            
            if (availableCapacity > 0 && overflowCount > 0) {
                int toReplenish = Math.min(availableCapacity, Math.min(overflowCount, 10));
                
                List<PendingMessage> messages = repository.findByStatusAndTargetMethodOrderByCreatedAtAsc(
                        MessageStatus.PENDING, methodSignature, PageRequest.of(0, toReplenish));
                        
                for (PendingMessage dbMessage : messages) {
                    try {
                        MessageWrapper<?> wrapper = convertFromDbEntity(dbMessage);
                        boolean added = bufferContext.memoryBuffer.offer(wrapper);
                        
                        if (added) {
                            repository.delete(dbMessage);
                            bufferContext.overflowCount.decrementAndGet();
                            log.debug("Replenished message from DB to memory: {}", dbMessage.getId());
                        } else {
                            // Unexpected - buffer should have space
                            log.warn("Failed to replenish message to memory buffer: {}", dbMessage.getId());
                            break;
                        }
                    } catch (Exception e) {
                        log.error("Error replenishing message: {}", dbMessage.getId(), e);
                    }
                }
            }
        });
    }
    
    /**
     * Move a message to dead letter queue
     */
    public <T> void moveToDeadLetter(MessageWrapper<T> wrapper, String errorMessage) {
        try {
            // Create dead letter entity
            PendingMessage deadLetter = convertToDbEntity(wrapper);
            deadLetter.setStatus(MessageStatus.DEAD_LETTER);
            deadLetter.setErrorMessage(errorMessage);
            deadLetter.setRetryCount(wrapper.getRetryCount());
            
            // Save to database
            repository.save(deadLetter);
            log.info("Message moved to dead letter: {}", generateId(wrapper.getPayload()));
        } catch (Exception e) {
            log.error("Failed to move message to dead letter", e);
        }
    }
    
    /**
     * Get or create a buffer for a method
     */
    private BufferContext getOrCreateBuffer(String targetMethod) {
        return methodBuffers.computeIfAbsent(targetMethod, k -> {
            log.info("Creating default buffer for method: {}", targetMethod);
            return new BufferContext(100); // Default size
        });
    }
    
    /**
     * Convert memory message to database entity
     */
    private <T> PendingMessage convertToDbEntity(MessageWrapper<T> wrapper) throws JsonProcessingException {
        T payload = wrapper.getPayload();
        
        return PendingMessage.builder()
                .id(UUID.randomUUID().toString())
                .messageId(generateId(payload))
                .targetMethod(wrapper.getTargetMethod())
                .payload(objectMapper.writeValueAsString(payload))
                .payloadType(payload.getClass().getName())
                .createdAt(wrapper.getReceivedTime())
                .retryCount(wrapper.getRetryCount())
                .status(MessageStatus.PENDING)
                .build();
    }
    
    /**
     * Convert database entity to memory message with type checking
     */
    @SuppressWarnings("unchecked")
    private <T> MessageWrapper<T> convertFromDbEntity(PendingMessage dbMessage, Class<T> expectedType) throws IOException, ClassNotFoundException {
        String payloadType = dbMessage.getPayloadType();
        Class<?> actualType = Class.forName(payloadType);
        
        if (!expectedType.isAssignableFrom(actualType)) {
            throw new ClassCastException("Stored payload type " + payloadType + 
                                         " is not compatible with expected type " + expectedType.getName());
        }
        
        T payload = (T) objectMapper.readValue(dbMessage.getPayload(), actualType);
        
        return new MessageWrapper<>(
                payload, 
                dbMessage.getTargetMethod(),
                dbMessage.getCreatedAt(),
                dbMessage.getRetryCount()
        );
    }
    
    /**
     * Convert database entity to memory message without type parameter
     */
    private MessageWrapper<?> convertFromDbEntity(PendingMessage dbMessage) throws IOException, ClassNotFoundException {
        String payloadType = dbMessage.getPayloadType();
        Class<?> actualType = Class.forName(payloadType);
        
        Object payload = objectMapper.readValue(dbMessage.getPayload(), actualType);
        
        return new MessageWrapper<>(
                payload, 
                dbMessage.getTargetMethod(),
                dbMessage.getCreatedAt(),
                dbMessage.getRetryCount()
        );
    }
    
    /**
     * Generate an ID for a message
     */
    private String generateId(Object payload) {
        if (payload == null) {
            return UUID.randomUUID().toString();
        }
        
        // Try to get ID from map
        if (payload instanceof Map) {
            Object id = ((Map<?,?>) payload).get("id");
            if (id != null) {
                return id.toString();
            }
        }
        
        // Try reflection to find an "id" or "getId" method
        try {
            // Try direct field access
            try {
                java.lang.reflect.Field field = payload.getClass().getDeclaredField("id");
                field.setAccessible(true);
                Object id = field.get(payload);
                if (id != null) {
                    return id.toString();
                }
            } catch (NoSuchFieldException e) {
                // Field not found, try getter method
            }
            
            // Try getter method
            try {
                Method method = payload.getClass().getMethod("getId");
                Object id = method.invoke(payload);
                if (id != null) {
                    return id.toString();
                }
            } catch (NoSuchMethodException e) {
                // Method not found
            }
        } catch (Exception e) {
            // Ignore reflection errors
        }
        
        // Fallback: use the object's hashcode and class name
        return payload.getClass().getSimpleName() + "-" + payload.hashCode();
    }
    
    /**
     * Buffer context for a specific method
     */
    private static class BufferContext {
        private final BlockingQueue<MessageWrapper<?>> memoryBuffer;
        private final AtomicInteger overflowCount = new AtomicInteger(0);
        
        public BufferContext(int bufferSize) {
            this.memoryBuffer = new ArrayBlockingQueue<>(bufferSize);
        }
    }
}
