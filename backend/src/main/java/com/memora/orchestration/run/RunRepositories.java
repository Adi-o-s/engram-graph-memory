package com.memora.orchestration.run;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface WorkflowRunRepository extends JpaRepository<WorkflowRun, UUID> {

    Optional<WorkflowRun> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<WorkflowRun> findByTenantIdAndIdempotencyKey(UUID tenantId, String key);

    /** Serializes everything that decides a run's next state: completions, approvals, retries, cancellation. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM WorkflowRun r WHERE r.tenantId = :tenantId AND r.id = :id")
    Optional<WorkflowRun> lock(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    @Query("""
            SELECT r FROM WorkflowRun r
            WHERE r.tenantId = :tenantId
              AND (:projectId IS NULL OR r.projectId = :projectId)
              AND (:workflowId IS NULL OR r.workflowId = :workflowId)
              AND (:status IS NULL OR r.status = :status)
            ORDER BY r.createdAt DESC
            """)
    Page<WorkflowRun> search(@Param("tenantId") UUID tenantId, @Param("projectId") UUID projectId,
                             @Param("workflowId") UUID workflowId, @Param("status") RunStatus status, Pageable pageable);

    List<WorkflowRun> findByTenantIdAndProjectIdOrderByCreatedAtDesc(UUID tenantId, UUID projectId);
}

interface StepRunRepository extends JpaRepository<StepRun, UUID> {

    Optional<StepRun> findByTenantIdAndId(UUID tenantId, UUID id);

    List<StepRun> findByTenantIdAndRunIdOrderByCreatedAtAscIterationAsc(UUID tenantId, UUID runId);

    Optional<StepRun> findByTenantIdAndHandoffId(UUID tenantId, UUID handoffId);
}

interface ArtifactRepository extends JpaRepository<WorkflowArtifact, UUID> {

    Optional<WorkflowArtifact> findByTenantIdAndId(UUID tenantId, UUID id);

    List<WorkflowArtifact> findByTenantIdAndRunIdOrderByNameAscVersionDesc(UUID tenantId, UUID runId);

    Optional<WorkflowArtifact> findFirstByTenantIdAndRunIdAndNameOrderByVersionDesc(UUID tenantId, UUID runId, String name);

    Optional<WorkflowArtifact> findByTenantIdAndRunIdAndNameAndVersion(UUID tenantId, UUID runId, String name, int version);

    List<WorkflowArtifact> findByTenantIdAndProjectIdOrderByCreatedAtDesc(UUID tenantId, UUID projectId, Pageable pageable);
}

interface ApprovalRepository extends JpaRepository<WorkflowApproval, UUID> {

    Optional<WorkflowApproval> findByTenantIdAndStepRunId(UUID tenantId, UUID stepRunId);

    List<WorkflowApproval> findByTenantIdAndRunIdOrderByCreatedAtAsc(UUID tenantId, UUID runId);
}
