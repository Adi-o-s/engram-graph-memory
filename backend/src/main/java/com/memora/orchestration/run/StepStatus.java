package com.memora.orchestration.run;

import java.util.EnumSet;
import java.util.Set;

/**
 * PENDING waits for dependencies; READY may be claimed; RUNNING is claimed under a lease;
 * WAITING_APPROVAL waits for a person; BLOCKED has a failed or cancelled dependency; SKIPPED means its
 * condition was false. A dependency is satisfied by SUCCEEDED or SKIPPED.
 */
public enum StepStatus {
    PENDING, READY, RUNNING, WAITING_APPROVAL, BLOCKED, SUCCEEDED, FAILED, SKIPPED, CANCELLED;

    public static final Set<StepStatus> TERMINAL = EnumSet.of(SUCCEEDED, FAILED, SKIPPED, CANCELLED);
    public static final Set<StepStatus> SATISFIED = EnumSet.of(SUCCEEDED, SKIPPED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
