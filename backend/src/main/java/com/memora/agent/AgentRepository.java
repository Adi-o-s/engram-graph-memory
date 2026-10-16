package com.memora.agent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentRepository extends JpaRepository<Agent, UUID> {

    List<Agent> findByTenantIdOrderByName(UUID tenantId);

    Optional<Agent> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<Agent> findByTenantIdAndName(UUID tenantId, String name);

    Optional<Agent> findFirstByTenantIdAndRoleOrderByCreatedAt(UUID tenantId, AgentRole role);

    /** Atomic counter: concurrent handoffs never lose an increment. */
    @Modifying
    @Query(value = "UPDATE agents SET handoffs_sent = handoffs_sent + 1, last_seen_at = now() "
            + "WHERE tenant_id = :tenantId AND id = :agentId", nativeQuery = true)
    int incrementSent(@Param("tenantId") UUID tenantId, @Param("agentId") UUID agentId);

    @Modifying
    @Query(value = "UPDATE agents SET handoffs_received = handoffs_received + 1 "
            + "WHERE tenant_id = :tenantId AND id = :agentId", nativeQuery = true)
    int incrementReceived(@Param("tenantId") UUID tenantId, @Param("agentId") UUID agentId);

    @Modifying
    @Query(value = "UPDATE agents SET last_seen_at = now() WHERE tenant_id = :tenantId AND id = :agentId",
            nativeQuery = true)
    int touch(@Param("tenantId") UUID tenantId, @Param("agentId") UUID agentId);
}
