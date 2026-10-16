package com.memora.memory;

import java.util.List;
import java.util.UUID;

/** Retrieval filters. Tenant and visibility come from the caller, never from the query. */
public record MemoryQuery(
        String q,
        List<MemoryType> types,
        boolean includeHistory,
        UUID projectId,
        boolean includeShared,
        UUID conversationId,
        UUID taskId,
        UUID agentId,
        List<String> tags,
        Integer minImportance,
        String category,
        Sort sort,
        boolean matchAll,
        int limit,
        int offset) {

    public enum Sort {
        RELEVANCE, RECENT, IMPORTANCE
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String q;
        private List<MemoryType> types = List.of();
        private boolean includeHistory;
        private UUID projectId;
        private boolean includeShared = true;
        private UUID conversationId;
        private UUID taskId;
        private UUID agentId;
        private List<String> tags = List.of();
        private Integer minImportance;
        private String category;
        private Sort sort;
        private boolean matchAll;
        private int limit = 20;
        private int offset;

        public Builder q(String v) { this.q = v; return this; }
        public Builder types(List<MemoryType> v) { this.types = v == null ? List.of() : v; return this; }
        public Builder type(MemoryType v) { this.types = v == null ? List.of() : List.of(v); return this; }
        public Builder includeHistory(boolean v) { this.includeHistory = v; return this; }
        public Builder project(UUID v) { this.projectId = v; return this; }
        public Builder includeShared(boolean v) { this.includeShared = v; return this; }
        public Builder conversation(UUID v) { this.conversationId = v; return this; }
        public Builder task(UUID v) { this.taskId = v; return this; }
        public Builder agent(UUID v) { this.agentId = v; return this; }
        public Builder tags(List<String> v) { this.tags = v == null ? List.of() : v; return this; }
        public Builder minImportance(Integer v) { this.minImportance = v; return this; }
        public Builder category(String v) { this.category = v; return this; }
        public Builder sort(Sort v) { this.sort = v; return this; }
        public Builder matchAll(boolean v) { this.matchAll = v; return this; }
        public Builder limit(int v) { this.limit = v; return this; }
        public Builder offset(int v) { this.offset = v; return this; }

        public MemoryQuery build() {
            return new MemoryQuery(q, types, includeHistory, projectId, includeShared, conversationId, taskId, agentId,
                    tags, minImportance, category, sort, matchAll, limit, offset);
        }
    }
}
