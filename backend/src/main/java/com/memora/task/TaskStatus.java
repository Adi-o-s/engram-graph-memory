package com.memora.task;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum TaskStatus {
    TODO, IN_PROGRESS, BLOCKED, IN_REVIEW, DONE, FAILED;

    private static final Map<TaskStatus, Set<TaskStatus>> ALLOWED = Map.of(
            TODO, EnumSet.of(IN_PROGRESS, BLOCKED, FAILED),
            IN_PROGRESS, EnumSet.of(TODO, BLOCKED, IN_REVIEW, DONE, FAILED),
            BLOCKED, EnumSet.of(TODO, IN_PROGRESS, FAILED),
            IN_REVIEW, EnumSet.of(IN_PROGRESS, DONE, FAILED),
            FAILED, EnumSet.of(TODO, IN_PROGRESS),
            DONE, EnumSet.of(IN_PROGRESS));

    public boolean canTransitionTo(TaskStatus next) {
        return this == next || ALLOWED.get(this).contains(next);
    }

    public Set<TaskStatus> allowedNext() {
        return ALLOWED.get(this);
    }
}
