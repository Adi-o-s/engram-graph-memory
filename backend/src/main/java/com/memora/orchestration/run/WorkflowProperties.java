package com.memora.orchestration.run;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Scheduler settings. {@code recoverOnStartup} requeues steps that were RUNNING when the previous process
 * stopped; it is correct for the single-node deployment Memora ships with. With several backend instances,
 * turn it off and rely on lease expiry instead.
 */
@ConfigurationProperties(prefix = "memora.workflow")
public record WorkflowProperties(Boolean schedulerEnabled, Integer workers, Long pollIntervalMs, Long recoveryIntervalMs,
                                 Integer leaseGraceSeconds, Boolean recoverOnStartup, Integer maxPacingMs) {

    public WorkflowProperties {
        schedulerEnabled = schedulerEnabled == null || schedulerEnabled;
        workers = workers == null ? 4 : Math.max(1, workers);
        pollIntervalMs = pollIntervalMs == null ? 400L : pollIntervalMs;
        recoveryIntervalMs = recoveryIntervalMs == null ? 5000L : recoveryIntervalMs;
        leaseGraceSeconds = leaseGraceSeconds == null ? 30 : leaseGraceSeconds;
        recoverOnStartup = recoverOnStartup == null || recoverOnStartup;
        maxPacingMs = maxPacingMs == null ? 5000 : maxPacingMs;
    }
}
