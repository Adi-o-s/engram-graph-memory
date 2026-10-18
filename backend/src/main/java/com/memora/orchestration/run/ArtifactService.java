package com.memora.orchestration.run;

import com.memora.common.ApiException;
import com.memora.extraction.SensitiveContentGuard;
import com.memora.orchestration.tool.ToolContext;
import com.memora.orchestration.tool.ToolException;
import com.memora.security.Actor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Run artifacts. Writes always target the run in the tool context (never a run named in arguments), and
 * each write of an existing name creates a new version; nothing is overwritten.
 */
@Service
public class ArtifactService {

    public static final int MAX_BYTES = 512 * 1024;
    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,119}");
    public static final Set<String> KINDS = Set.of("markdown", "json", "text", "code");

    private final ArtifactRepository artifacts;
    private final TransactionTemplate tx;

    public ArtifactService(ArtifactRepository artifacts, TransactionTemplate tx) {
        this.artifacts = artifacts;
        this.tx = tx;
    }

    public ArtifactView write(ToolContext ctx, String name, String kind, String content) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw ToolException.permanent("Artifact name must be 1-120 characters: letters, digits, dot, dash, underscore");
        }
        if (!KINDS.contains(kind)) {
            throw ToolException.permanent("Artifact kind must be one of " + KINDS);
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            throw ToolException.permanent("Artifact " + name + " is " + bytes.length + " bytes; the limit is " + MAX_BYTES);
        }
        if (SensitiveContentGuard.looksSensitive(content)) {
            throw ToolException.permanent("Artifact " + name + " looks like it contains a credential; secrets are never stored");
        }
        String checksum = sha256(bytes);
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(s -> {
                    int version = artifacts.findFirstByTenantIdAndRunIdAndNameOrderByVersionDesc(ctx.tenantId(), ctx.runId(), name)
                            .map(a -> a.getVersion() + 1).orElse(1);
                    WorkflowArtifact a = artifacts.save(new WorkflowArtifact(ctx.tenantId(), ctx.runId(), ctx.stepRunId(),
                            ctx.projectId(), name, kind, content, checksum, bytes.length, version, ctx.actor().agentId()));
                    artifacts.flush();
                    return ArtifactView.of(a, true, ctx.stepKey());
                });
            } catch (DataIntegrityViolationException e) {
                if (attempt >= 3) {
                    throw ToolException.retryable("Concurrent writes to artifact " + name);
                }
            }
        }
    }

    @Transactional(readOnly = true)
    public Optional<WorkflowArtifact> latest(UUID tenantId, UUID runId, String name) {
        return artifacts.findFirstByTenantIdAndRunIdAndNameOrderByVersionDesc(tenantId, runId, name);
    }

    @Transactional(readOnly = true)
    public Optional<WorkflowArtifact> version(UUID tenantId, UUID runId, String name, int version) {
        return artifacts.findByTenantIdAndRunIdAndNameAndVersion(tenantId, runId, name, version);
    }

    /** Latest version of each artifact in a run. */
    @Transactional(readOnly = true)
    public List<WorkflowArtifact> latestOfRun(UUID tenantId, UUID runId) {
        List<WorkflowArtifact> all = artifacts.findByTenantIdAndRunIdOrderByNameAscVersionDesc(tenantId, runId);
        return all.stream().filter(a -> all.stream().noneMatch(b -> b.getName().equals(a.getName()) && b.getVersion() > a.getVersion()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WorkflowArtifact> allOfRun(UUID tenantId, UUID runId) {
        return artifacts.findByTenantIdAndRunIdOrderByNameAscVersionDesc(tenantId, runId);
    }

    @Transactional(readOnly = true)
    public List<WorkflowArtifact> ofProject(Actor actor, UUID projectId, int limit) {
        return artifacts.findByTenantIdAndProjectIdOrderByCreatedAtDesc(actor.tenantId(), projectId, PageRequest.of(0, limit));
    }

    @Transactional(readOnly = true)
    public WorkflowArtifact get(Actor actor, UUID id) {
        return artifacts.findByTenantIdAndId(actor.tenantId(), id).orElseThrow(() -> ApiException.notFound("Artifact", id));
    }

    public record ArtifactView(UUID id, UUID runId, UUID stepRunId, String stepKey, UUID projectId, String name, String kind,
                               String checksum, int sizeBytes, int version, String content, Instant createdAt) {
        public static ArtifactView of(WorkflowArtifact a, boolean withContent, String stepKey) {
            return new ArtifactView(a.getId(), a.getRunId(), a.getStepRunId(), stepKey, a.getProjectId(), a.getName(),
                    a.getKind(), a.getChecksum(), a.getSizeBytes(), a.getVersion(), withContent ? a.getContent() : null,
                    a.getCreatedAt());
        }
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
