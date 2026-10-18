package com.memora.orchestration.executor;

import java.util.List;
import java.util.Map;

/** What an executor reports back to the engine. The engine, not the executor, changes step state. */
public record StepOutcome(Status status, Map<String, Object> output, FailureKind failureKind, String error,
                          List<String> log) {

    public enum Status { SUCCEEDED, FAILED, WAITING_EXTERNAL }

    public enum FailureKind { RETRYABLE, PERMANENT }

    public static StepOutcome succeeded(Map<String, Object> output, List<String> log) {
        return new StepOutcome(Status.SUCCEEDED, output, null, null, log);
    }

    public static StepOutcome failed(FailureKind kind, String error, List<String> log) {
        return new StepOutcome(Status.FAILED, Map.of(), kind, error, log);
    }

    public static StepOutcome waitingExternal(List<String> log) {
        return new StepOutcome(Status.WAITING_EXTERNAL, Map.of(), null, null, log);
    }
}
