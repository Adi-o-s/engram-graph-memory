package com.memora.lab;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class LabDtos {

    private LabDtos() {
    }

    public enum Scenario {
        /** Many workers increment one counter in a versioned task (read, update with expectedVersion, retry on 409). */
        TASK_COUNTER,
        /** Workers append messages to one conversation concurrently; sequence numbers must stay unique and gapless. */
        MESSAGE_APPEND,
        /** Workers write competing values for one fact plus unique episodic memories; exactly one fact stays CURRENT. */
        MEMORY_WRITES
    }

    public record LabRequest(@NotNull Scenario scenario, @Min(1) @Max(64) int threads, @Min(1) @Max(20000) int operations) {
    }

    public record Invariant(String name, String expected, String actual, boolean passed) {
    }

    public record LatencyStats(double p50Ms, double p95Ms, double p99Ms, double maxMs, double meanMs) {
    }

    public record LabResult(UUID runId, Scenario scenario, int threads, int operations, long succeeded, long failed,
                            long conflictsDetected, long retries, long durationMs, double throughputPerSec,
                            LatencyStats latency, List<Invariant> invariants, boolean allInvariantsPassed,
                            List<String> sampleErrors, Map<String, Object> target, Instant startedAt) {
    }
}
