package com.ratelimiter.repository;

import com.ratelimiter.model.MessageStatus;
import com.ratelimiter.model.PendingMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MessageRepository extends JpaRepository<PendingMessage, String> {
    /**
     * Find the first pending message by status ordered by creation time
     */
    Optional<PendingMessage> findFirstByStatusOrderByCreatedAtAsc(MessageStatus status);
    
    /**
     * Find the first pending message by status and target method ordered by creation time
     */
    Optional<PendingMessage> findFirstByStatusAndTargetMethodOrderByCreatedAtAsc(
            MessageStatus status, String targetMethod);
    
    /**
     * Find a batch of pending messages by status
     */
    List<PendingMessage> findByStatusOrderByCreatedAtAsc(
            MessageStatus status, Pageable pageable);
    
    /**
     * Find a batch of pending messages by status and target method
     */
    List<PendingMessage> findByStatusAndTargetMethodOrderByCreatedAtAsc(
            MessageStatus status, String targetMethod, Pageable pageable);
    
    /**
     * Count messages by status and target method
     */
    long countByStatusAndTargetMethod(MessageStatus status, String targetMethod);
    
    /**
     * Count messages by status
     */
    long countByStatus(MessageStatus status);
    
    /**
     * Update message status
     */
    @Modifying
    @Query("UPDATE PendingMessage m SET m.status = :status WHERE m.id = :id")
    int updateStatusById(String id, MessageStatus status);
}
