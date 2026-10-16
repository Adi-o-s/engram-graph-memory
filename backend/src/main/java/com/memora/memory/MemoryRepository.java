package com.memora.memory;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface MemoryRepository extends JpaRepository<Memory, UUID> {

    Optional<Memory> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<Memory> findByTenantIdAndIdempotencyKey(UUID tenantId, String key);

    List<Memory> findByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids);

    /** Locks the current fact so concurrent writers of the same fact serialize. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT m FROM Memory m
            WHERE m.tenantId = :tenantId AND m.scopeKey = :scopeKey AND m.factKey = :factKey
              AND m.status = com.memora.memory.MemoryStatus.CURRENT
            """)
    Optional<Memory> lockCurrentFact(@Param("tenantId") UUID tenantId, @Param("scopeKey") String scopeKey,
                                     @Param("factKey") String factKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Memory m WHERE m.tenantId = :tenantId AND m.id = :id")
    Optional<Memory> lockById(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    @Query("""
            SELECT m FROM Memory m
            WHERE m.tenantId = :tenantId AND m.scopeKey = :scopeKey AND m.factKey = :factKey
            ORDER BY m.revision ASC, m.createdAt ASC
            """)
    List<Memory> factHistory(@Param("tenantId") UUID tenantId, @Param("scopeKey") String scopeKey,
                             @Param("factKey") String factKey);

    @Query("""
            SELECT m FROM Memory m
            WHERE m.tenantId = :tenantId AND m.scopeKey = :scopeKey AND m.type = :type
              AND m.status = com.memora.memory.MemoryStatus.CURRENT AND m.factKey IS NULL
              AND lower(m.content) = lower(:content)
            """)
    List<Memory> findSameContent(@Param("tenantId") UUID tenantId, @Param("scopeKey") String scopeKey,
                                 @Param("type") MemoryType type, @Param("content") String content);

    List<Memory> findByTenantIdAndSourceMessageIdOrderByCreatedAt(UUID tenantId, UUID sourceMessageId);

    /** Atomic access counter; concurrent readers never lose an increment. */
    @Modifying
    @Query(value = "UPDATE memories SET access_count = access_count + 1, last_accessed_at = now() "
            + "WHERE tenant_id = :tenantId AND id IN (:ids)", nativeQuery = true)
    int recordAccess(@Param("tenantId") UUID tenantId, @Param("ids") Collection<UUID> ids);
}
