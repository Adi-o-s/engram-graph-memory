package com.memora.orchestration.agentdef;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AgentDefinitionRepository extends JpaRepository<AgentDefinition, UUID> {

    List<AgentDefinition> findByTenantIdOrderByName(UUID tenantId);

    Optional<AgentDefinition> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<AgentDefinition> findByTenantIdAndKey(UUID tenantId, String key);

    List<AgentDefinition> findByTenantIdAndKeyIn(UUID tenantId, Collection<String> keys);

    Optional<AgentDefinition> findByTenantIdAndAgentId(UUID tenantId, UUID agentId);

    boolean existsByTenantIdAndKey(UUID tenantId, String key);

    long countByTenantId(UUID tenantId);
}

interface AgentDefinitionVersionRepository extends JpaRepository<AgentDefinitionVersion, UUID> {

    List<AgentDefinitionVersion> findByTenantIdAndDefinitionIdOrderByVersionDesc(UUID tenantId, UUID definitionId);
}
