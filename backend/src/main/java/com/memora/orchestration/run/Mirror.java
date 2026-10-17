package com.memora.orchestration.run;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Coordination side effects collected while the engine changes run state, applied after that transaction
 * commits (see {@link CoordinationMirror}). Keeping task-board and handoff updates out of the engine's
 * transaction means a conflicting edit on the board can never roll back a step's result.
 */
final class Mirror {

    enum Kind { RUN_STARTED, DISPATCH, STARTED, ATTEMPT_FAILED, FINISHED, BLOCKED, RUN_FINISHED }

    record Action(Kind kind, UUID tenantId, UUID runId, UUID stepRunId, boolean external, String note) {
    }

    private final List<Action> actions = new ArrayList<>();

    void add(Kind kind, StepRun s, String note) {
        actions.add(new Action(kind, s.getTenantId(), s.getRunId(), s.getId(), "external".equals(s.getExecutor()), note));
    }

    void run(Kind kind, WorkflowRun r) {
        actions.add(new Action(kind, r.getTenantId(), r.getId(), null, false, null));
    }

    List<Action> actions() {
        return actions;
    }

    boolean isEmpty() {
        return actions.isEmpty();
    }
}
