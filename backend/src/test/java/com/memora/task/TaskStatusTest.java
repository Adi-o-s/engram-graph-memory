package com.memora.task;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TaskStatusTest {

    @Test
    void normalLifecycleIsAllowed() {
        assertThat(TaskStatus.TODO.canTransitionTo(TaskStatus.IN_PROGRESS)).isTrue();
        assertThat(TaskStatus.IN_PROGRESS.canTransitionTo(TaskStatus.IN_REVIEW)).isTrue();
        assertThat(TaskStatus.IN_REVIEW.canTransitionTo(TaskStatus.DONE)).isTrue();
    }

    @Test
    void failedTasksCanBeRecovered() {
        assertThat(TaskStatus.FAILED.canTransitionTo(TaskStatus.TODO)).isTrue();
        assertThat(TaskStatus.FAILED.canTransitionTo(TaskStatus.IN_PROGRESS)).isTrue();
    }

    @Test
    void skippingWorkIsRejected() {
        assertThat(TaskStatus.TODO.canTransitionTo(TaskStatus.DONE)).isFalse();
        assertThat(TaskStatus.TODO.canTransitionTo(TaskStatus.IN_REVIEW)).isFalse();
        assertThat(TaskStatus.DONE.canTransitionTo(TaskStatus.FAILED)).isFalse();
    }

    @Test
    void stayingInTheSameStatusIsANoOp() {
        for (TaskStatus s : TaskStatus.values()) {
            assertThat(s.canTransitionTo(s)).isTrue();
        }
    }
}
