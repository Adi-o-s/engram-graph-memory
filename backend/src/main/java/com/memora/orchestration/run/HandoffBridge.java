package com.memora.orchestration.run;

import com.memora.handoff.HandoffFinishedEvent;
import com.memora.handoff.HandoffStatus;
import com.memora.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Lets an external agent finish a workflow step with the ordinary handoff API: completing the step's
 * handoff with {@code {"output": {...}}} completes the step; failing it schedules a retry; rejecting it
 * fails the step. Handoffs the engine itself settles are ignored here.
 */
@Component
public class HandoffBridge {

    private static final Logger log = LoggerFactory.getLogger(HandoffBridge.class);

    private final WorkflowEngine engine;

    public HandoffBridge(WorkflowEngine engine) {
        this.engine = engine;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onHandoffFinished(HandoffFinishedEvent e) {
        try {
            String status = e.status() == HandoffStatus.DEAD_LETTER ? "REJECTED" : e.status().name();
            TenantContext.runAsTenant(e.tenantId(), () -> engine.onHandoffFinished(e.tenantId(), e.handoffId(), status,
                    e.result(), e.reason()));
        } catch (RuntimeException ex) {
            log.warn("Could not apply handoff {} to its workflow step: {}", e.handoffId(), ex.getMessage());
        }
    }
}
