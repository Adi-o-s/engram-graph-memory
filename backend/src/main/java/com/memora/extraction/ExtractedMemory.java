package com.memora.extraction;

import com.memora.memory.MemoryType;
import java.util.List;
import java.util.Map;

/**
 * A candidate memory produced by an extractor. {@code subject} and {@code predicate} are set only
 * for facts that can conflict with each other; {@code userScoped} marks personal facts such as preferences.
 */
public record ExtractedMemory(
        MemoryType type,
        String category,
        String content,
        String subject,
        String predicate,
        String objectValue,
        boolean userScoped,
        int importance,
        double confidence,
        List<String> tags,
        String rule,
        Map<String, Object> metadata) {

    public boolean isFact() {
        return subject != null && predicate != null;
    }
}
