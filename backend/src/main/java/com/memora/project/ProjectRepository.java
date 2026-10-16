package com.memora.project;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    List<Project> findByTenantIdOrderByName(UUID tenantId);

    Optional<Project> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<Project> findByTenantIdAndSlug(UUID tenantId, String slug);

    boolean existsByTenantIdAndId(UUID tenantId, UUID id);
}
