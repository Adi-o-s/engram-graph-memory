package com.memora.conversation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    Optional<Conversation> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<Conversation> findByTenantIdAndIdempotencyKey(UUID tenantId, String key);

    @Query("""
            SELECT c FROM Conversation c
            WHERE c.tenantId = :tenantId
              AND (:projectId IS NULL OR c.projectId = :projectId)
              AND (:ownerId IS NULL OR c.ownerUserId = :ownerId)
            ORDER BY c.updatedAt DESC
            """)
    Page<Conversation> search(@Param("tenantId") UUID tenantId, @Param("projectId") UUID projectId,
                              @Param("ownerId") UUID ownerId, Pageable pageable);
}

interface MessageRepository extends JpaRepository<Message, UUID> {

    Optional<Message> findByTenantIdAndIdempotencyKey(UUID tenantId, String key);

    Optional<Message> findByTenantIdAndId(UUID tenantId, UUID id);

    Page<Message> findByTenantIdAndConversationIdOrderBySeqAsc(UUID tenantId, UUID conversationId, Pageable pageable);

    List<Message> findByTenantIdAndConversationIdAndSeqGreaterThanOrderBySeqAsc(UUID tenantId, UUID conversationId,
                                                                               long afterSeq, Pageable pageable);

    List<Message> findByTenantIdAndConversationIdOrderBySeqDesc(UUID tenantId, UUID conversationId, Pageable pageable);
}
