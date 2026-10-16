package com.memora.handoff;

import com.memora.security.TenantContext;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically returns handoffs with expired leases to their inbox. */
@Component
public class HandoffLeaseRecovery {

    private static final Logger log = LoggerFactory.getLogger(HandoffLeaseRecovery.class);

    private final HandoffService handoffs;

    public HandoffLeaseRecovery(HandoffService handoffs) {
        this.handoffs = handoffs;
    }

    @Scheduled(fixedDelayString = "${memora.handoff.recovery-interval-ms:30000}", initialDelay = 10000)
    public void recover() {
        try {
            List<HandoffDtos.HandoffView> recovered = TenantContext.callAsSystem(
                    () -> handoffs.recoverExpiredLeases(Instant.now(), 100));
            if (!recovered.isEmpty()) {
                log.info("Recovered {} handoff(s) with expired leases", recovered.size());
            }
        } catch (RuntimeException e) {
            log.warn("Handoff lease recovery failed: {}", e.getMessage());
        }
    }
}
