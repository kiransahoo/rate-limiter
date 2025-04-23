package com.ratelimiter.model;

/**
 * Possible states for a message in the processing pipeline
 */
public enum MessageStatus {
    /**
     * Initial state, waiting to be processed
     */
    PENDING,
    
    /**
     * Currently being processed
     */
    PROCESSING,
    
    /**
     * Successfully processed
     */
    COMPLETED,
    
    /**
     * Failed processing but eligible for retry
     */
    FAILED,
    
    /**
     * Failed processing after maximum retries
     */
    DEAD_LETTER
}
