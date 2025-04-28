package com.ratelimiter.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Database entity for persisted messages
 */
@Entity
@Table(name = "rate_limiter_messages", 
       indexes = {
           @Index(name = "idx_status_method", columnList = "status,targetMethod"),
           @Index(name = "idx_created_at", columnList = "createdAt")
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingMessage {
    /**
     * Unique message identifier
     */
    @Id
    private String id;
    
    /**
     * Original message ID if available
     */
    private String messageId;
    
    /**
     * Target method to process this message
     */
    private String targetMethod;
    
    /**
     * Type of the payload for deserialization
     */
    private String payloadType;
    
    /**
     * Serialized payload
     */
    @Column(columnDefinition = "LONGTEXT")
    private String payload;
    
    /**
     * When the message was received
     */
    private Instant createdAt;
    
    /**
     * When the message was processed
     */
    private Instant processedAt;
    
    /**
     * Number of processing attempts
     */
    private int retryCount;
    
    /**
     * Current message status
     */
    @Enumerated(EnumType.STRING)
    private MessageStatus status;
    
    /**
     * Error message if processing failed
     */
    @Column(length = 4000)
    private String errorMessage;
}
