package com.memora.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memora.memory.MemoryDtos.MemoryView;
import com.memora.memory.MemoryDtos.SourceView;
import com.memora.project.ProjectService;
import com.memora.security.Actor;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retrieval over PostgreSQL full-text search (tsvector + GIN) with a trigram-indexed keyword
 * fallback. Ranking blends text relevance, importance, recency and confidence. Every query is
 * filtered by tenant and per-user visibility before anything else.
 */
@Service
public class MemorySearchService {

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final double RECENCY_HALF_LIFE_DAYS = 30.0;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ProjectService projects;

    public MemorySearchService(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper, ProjectService projects) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.projects = projects;
    }

    public record SearchResult(List<MemoryView> items, long total, int limit, int offset, String tsQuery) {
    }

    @Transactional(readOnly = true)
    public SearchResult search(Actor actor, MemoryQuery query) {
        // A project filter from another tenant is a 404, the same answer as reading that project directly.
        projects.requireInTenant(actor, query.projectId());
        String tsQuery = toTsQuery(query.q(), query.matchAll());
        boolean hasText = query.q() != null && !query.q().isBlank();
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("tenantId", actor.tenantId())
                .addValue("seesAll", actor.seesAllUserData())
                .addValue("userScope", "user:" + actor.userId())
                .addValue("tsq", tsQuery)
                .addValue("halfLife", RECENCY_HALF_LIFE_DAYS)
                .addValue("limit", query.limit())
                .addValue("offset", query.offset());

        StringBuilder where = new StringBuilder("""
                WHERE m.tenant_id = :tenantId
                  AND (:seesAll OR m.scope_key NOT LIKE 'user:%' OR m.scope_key = :userScope)
                """);
        if (!query.includeHistory()) {
            where.append(" AND m.status = 'CURRENT' AND (m.expires_at IS NULL OR m.expires_at > now())");
        }
        if (!query.types().isEmpty()) {
            where.append(" AND m.memory_type IN (:types)");
            p.addValue("types", query.types().stream().map(Enum::name).toList());
        }
        if (query.projectId() != null) {
            where.append(query.includeShared()
                    ? " AND (m.project_id = :projectId OR m.project_id IS NULL)"
                    : " AND m.project_id = :projectId");
            p.addValue("projectId", query.projectId());
        }
        if (query.conversationId() != null) {
            where.append(" AND m.conversation_id = :conversationId");
            p.addValue("conversationId", query.conversationId());
        }
        if (query.taskId() != null) {
            where.append(" AND m.task_id = :taskId");
            p.addValue("taskId", query.taskId());
        }
        if (query.agentId() != null) {
            where.append(" AND m.agent_id = :agentId");
            p.addValue("agentId", query.agentId());
        }
        if (!query.tags().isEmpty()) {
            where.append(" AND m.tags && CAST(:tags AS text[])");
            p.addValue("tags", query.tags().stream().map(t -> t.toLowerCase(Locale.ROOT)).toArray(String[]::new));
        }
        if (query.minImportance() != null) {
            where.append(" AND m.importance >= :minImportance");
            p.addValue("minImportance", query.minImportance());
        }
        if (query.category() != null && !query.category().isBlank()) {
            where.append(" AND m.category = :category");
            p.addValue("category", query.category());
        }
        if (hasText) {
            where.append(" AND ((q.query IS NOT NULL AND m.search_vector @@ q.query) OR m.search_text ILIKE :like ESCAPE '\\')");
            p.addValue("like", "%" + escapeLike(query.q().trim()) + "%");
        }

        MemoryQuery.Sort sort = query.sort() != null ? query.sort() : hasText ? MemoryQuery.Sort.RELEVANCE : MemoryQuery.Sort.RECENT;
        String orderBy = switch (sort) {
            case RELEVANCE -> "score DESC, m.created_at DESC";
            case RECENT -> "m.created_at DESC";
            case IMPORTANCE -> "m.importance DESC, m.created_at DESC";
        };

        String sql = """
                WITH q AS (SELECT CASE WHEN :tsq = '' THEN NULL ELSE to_tsquery('english', :tsq) END AS query)
                SELECT m.*, m.metadata::text AS metadata_json,
                       COALESCE(ts_rank_cd(m.search_vector, q.query, 32), 0) AS text_rank,
                       (COALESCE(ts_rank_cd(m.search_vector, q.query, 32), 0) * 3.0
                        + (m.importance / 5.0) * 0.5
                        + exp(-extract(epoch FROM (now() - m.created_at)) / 86400.0 / :halfLife) * 0.3
                        + m.confidence * 0.2) AS score,
                       count(*) OVER () AS total_count
                FROM memories m CROSS JOIN q
                """ + where + " ORDER BY " + orderBy + " LIMIT :limit OFFSET :offset";

        long[] total = {0};
        List<MemoryView> items = jdbc.query(sql, p, (rs, i) -> {
            total[0] = rs.getLong("total_count");
            return map(rs, rs.getDouble("score"));
        });
        return new SearchResult(items, total[0], query.limit(), query.offset(), tsQuery);
    }

    /** Builds a safe tsquery: words only (no operators from user input), prefix-matched, OR-ed by default. */
    static String toTsQuery(String q, boolean matchAll) {
        if (q == null || q.isBlank()) {
            return "";
        }
        List<String> words = new ArrayList<>();
        Matcher m = WORD.matcher(q.toLowerCase(Locale.ROOT));
        while (m.find() && words.size() < 16) {
            words.add(m.group() + ":*");
        }
        return String.join(matchAll ? " & " : " | ", words);
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private MemoryView map(ResultSet rs, Double score) throws SQLException {
        String scopeKey = rs.getString("scope_key");
        return new MemoryView(
                rs.getObject("id", UUID.class),
                MemoryType.valueOf(rs.getString("memory_type")),
                rs.getString("category"),
                rs.getString("content"),
                rs.getString("subject"),
                rs.getString("predicate"),
                rs.getString("object_value"),
                rs.getString("fact_key"),
                MemoryView.scopeOf(scopeKey),
                scopeKey,
                rs.getObject("user_id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getObject("conversation_id", UUID.class),
                rs.getObject("task_id", UUID.class),
                rs.getObject("agent_id", UUID.class),
                strings(rs.getArray("tags")),
                rs.getInt("importance"),
                rs.getDouble("confidence"),
                MemoryStatus.valueOf(rs.getString("status")),
                rs.getInt("version"),
                rs.getLong("lock_version"),
                rs.getObject("supersedes_id", UUID.class),
                rs.getObject("superseded_by_id", UUID.class),
                new SourceView(SourceType.valueOf(rs.getString("source_type")), rs.getObject("source_message_id", UUID.class),
                        rs.getString("source_ref"), rs.getString("extractor"),
                        rs.getObject("created_by_user_id", UUID.class), rs.getObject("created_by_agent_id", UUID.class)),
                rs.getLong("access_count"),
                instant(rs.getTimestamp("last_accessed_at")),
                instant(rs.getTimestamp("expires_at")),
                json(rs.getString("metadata_json")),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")),
                score == null ? null : Math.round(score * 1000.0) / 1000.0);
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private Map<String, Object> json(String s) {
        try {
            return s == null ? Map.of() : objectMapper.readValue(s, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }
}
