package com.memora.handoff;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface HandoffRepository extends JpaRepository<Handoff, UUID> {

    Optional<Handoff> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<Handoff> findByTenantIdAndIdempotencyKey(UUID tenantId, String key);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT h FROM Handoff h WHERE h.tenantId = :tenantId AND h.id = :id")
    Optional<Handoff> lock(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    List<Handoff> findByTenantIdAndToAgentIdAndStatusInOrderByCreatedAtAsc(UUID tenantId, UUID toAgentId,
                                                                          Collection<HandoffStatus> statuses);

    List<Handoff> findByTenantIdAndTaskIdOrderByCreatedAtAsc(UUID tenantId, UUID taskId);

    @Query("""
            SELECT h FROM Handoff h
            WHERE h.tenantId = :tenantId AND (:status IS NULL OR h.status = :status)
            ORDER BY h.createdAt DESC
            """)
    List<Handoff> recent(@Param("tenantId") UUID tenantId, @Param("status") HandoffStatus status, Pageable pageable);

    @Query("SELECT h.status, count(h) FROM Handoff h WHERE h.tenantId = :tenantId GROUP BY h.status")
    List<Object[]> countByStatus(@Param("tenantId") UUID tenantId);

    /** Expired leases across all tenants; only the system-scope recovery job calls this. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT h FROM Handoff h WHERE h.status = com.memora.handoff.HandoffStatus.ACKNOWLEDGED AND h.leaseExpiresAt < :now")
    List<Handoff> findExpiredLeases(@Param("now") Instant now, Pageable pageable);
}
