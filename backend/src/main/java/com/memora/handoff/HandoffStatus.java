package com.memora.handoff;

/**
 * PENDING -> ACKNOWLEDGED (claimed, under a lease) -> COMPLETED
 *                                                  -> FAILED -> PENDING (retry) | DEAD_LETTER (attempts exhausted)
 * PENDING | ACKNOWLEDGED -> REJECTED. An expired lease returns ACKNOWLEDGED to PENDING.
 */
public enum HandoffStatus {
    PENDING, ACKNOWLEDGED, COMPLETED, FAILED, REJECTED, DEAD_LETTER;

    public boolean isActive() {
        return this == PENDING || this == ACKNOWLEDGED;
    }
}
