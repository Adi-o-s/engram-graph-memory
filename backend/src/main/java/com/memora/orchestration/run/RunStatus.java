package com.memora.orchestration.run;

import java.util.EnumSet;
import java.util.Set;

public enum RunStatus {
    PENDING, RUNNING, PAUSED, WAITING_APPROVAL, BLOCKED, SUCCEEDED, FAILED, CANCELLED;

    public static final Set<RunStatus> FINISHED = EnumSet.of(SUCCEEDED, FAILED, CANCELLED);

    public boolean isFinished() {
        return FINISHED.contains(this);
    }
}
