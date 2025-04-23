package com.ratelimiter.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * In-memory wrapper for message data and metadata
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageWrapper<T> {
    /**
     * The original payload
     */
    private T payload;
    
    /**
     * Target method to process this message
     */
    private String targetMethod;
    
    /**
     * When the message was received
     */
    private Instant receivedTime;
    
    /**
     * Number of processing attempts
     */
    private int retryCount;
    
    public MessageWrapper(T payload, String targetMethod) {
        this.payload = payload;
        this.targetMethod = targetMethod;
        this.receivedTime = Instant.now();
        this.retryCount = 0;
    }
    
    /**
     * Increment retry count and return the new value
     */
    public int incrementRetryCount() {
        return ++retryCount;
    }
}
