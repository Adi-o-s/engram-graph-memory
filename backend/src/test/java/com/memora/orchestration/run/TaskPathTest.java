package com.memora.orchestration.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.memora.task.TaskStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Workflow steps drive their board tasks only through the task model's allowed transitions. */
class TaskPathTest {

    @Test
    void shortestAllowedPaths() {
        assertThat(CoordinationMirror.path(TaskStatus.TODO, TaskStatus.DONE)).containsExactly(TaskStatus.IN_PROGRESS, TaskStatus.DONE);
        assertThat(CoordinationMirror.path(TaskStatus.TODO, TaskStatus.BLOCKED)).containsExactly(TaskStatus.BLOCKED);
        assertThat(CoordinationMirror.path(TaskStatus.FAILED, TaskStatus.DONE)).containsExactly(TaskStatus.IN_PROGRESS, TaskStatus.DONE);
        assertThat(CoordinationMirror.path(TaskStatus.DONE, TaskStatus.DONE)).isEmpty();
        assertThat(CoordinationMirror.path(TaskStatus.BLOCKED, TaskStatus.IN_PROGRESS)).isEqualTo(List.of(TaskStatus.IN_PROGRESS));
    }
}
