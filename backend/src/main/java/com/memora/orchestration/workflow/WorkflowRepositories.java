package com.memora.orchestration.workflow;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition, UUID> {

    List<WorkflowDefinition> findByTenantIdOrderByName(UUID tenantId);

    Optional<WorkflowDefinition> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<WorkflowDefinition> findByTenantIdAndKey(UUID tenantId, String key);

    boolean existsByTenantIdAndKey(UUID tenantId, String key);
}

interface WorkflowVersionRepository extends JpaRepository<WorkflowDefinitionVersion, UUID> {

    Optional<WorkflowDefinitionVersion> findByTenantIdAndWorkflowIdAndVersion(UUID tenantId, UUID workflowId, int version);

    List<WorkflowDefinitionVersion> findByTenantIdAndWorkflowIdOrderByVersionDesc(UUID tenantId, UUID workflowId);
}
