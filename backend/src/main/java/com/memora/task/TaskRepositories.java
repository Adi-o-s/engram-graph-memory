package com.memora.task;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface TaskRepository extends JpaRepository<Task, UUID> {

    Optional<Task> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<Task> findByTenantIdAndIdempotencyKey(UUID tenantId, String key);

    boolean existsByTenantIdAndId(UUID tenantId, UUID id);

    @Query("""
            SELECT t FROM Task t
            WHERE t.tenantId = :tenantId
              AND (:projectId IS NULL OR t.projectId = :projectId)
              AND (:status IS NULL OR t.status = :status)
              AND (:assignee IS NULL OR t.assigneeAgentId = :assignee)
            ORDER BY t.updatedAt DESC
            """)
    Page<Task> search(@Param("tenantId") UUID tenantId, @Param("projectId") UUID projectId,
                      @Param("status") TaskStatus status, @Param("assignee") UUID assignee, Pageable pageable);

    @Query("SELECT t.status, count(t) FROM Task t WHERE t.tenantId = :tenantId GROUP BY t.status")
    List<Object[]> countByStatus(@Param("tenantId") UUID tenantId);
}

interface TaskEventRepository extends JpaRepository<TaskEvent, UUID> {

    List<TaskEvent> findByTenantIdAndTaskIdOrderByCreatedAtAsc(UUID tenantId, UUID taskId);
}
