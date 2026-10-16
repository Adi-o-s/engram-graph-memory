package com.memora.common;

import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Retry-safe creation. The caller first looks for an existing record with the same idempotency key;
 * if two requests race past that check, the unique (tenant_id, idempotency_key) constraint rejects
 * the loser, whose transaction has already rolled back, and we return the winner's record.
 */
public final class Idempotency {

    private Idempotency() {
    }

    public static <T> T createOnce(String key, Supplier<Optional<T>> findExisting, Supplier<T> create) {
        if (key == null || key.isBlank()) {
            return create.get();
        }
        Optional<T> existing = findExisting.get();
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return create.get();
        } catch (DataIntegrityViolationException e) {
            return findExisting.get().orElseThrow(() -> e);
        }
    }

    public static String normalize(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String trimmed = key.trim();
        if (trimmed.length() > 100) {
            throw ApiException.badRequest("Idempotency-Key must be at most 100 characters");
        }
        return trimmed;
    }
}
