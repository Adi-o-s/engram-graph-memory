package com.memora.orchestration.run;

import com.memora.common.Json;
import com.memora.orchestration.executor.AgentExecutor;
import com.memora.orchestration.executor.ExecutionContext;
import com.memora.orchestration.executor.ExecutorRegistry;
import com.memora.orchestration.executor.StepOutcome;
import com.memora.orchestration.expr.MiniSchema;
import com.memora.orchestration.run.WorkflowEngine.Claimed;
import com.memora.orchestration.run.WorkflowEngine.Prepared;
import com.memora.orchestration.tool.ToolContext;
import com.memora.orchestration.tool.ToolGateway;
import com.memora.security.Actor;
import com.memora.security.TenantContext;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Database-backed scheduler. Every poll claims as many READY steps as there are free workers
 * ({@code SELECT … FOR UPDATE SKIP LOCKED}), so independent steps run in parallel and no step is executed
 * by two workers. Workers run executors outside any transaction, enforce the step timeout, then hand the
 * outcome back to the engine, which accepts it only from the worker that still holds the claim.
 *
 * <p>Delivery is at-least-once per attempt: if a worker dies, the step's lease expires and the step is
 * retried (bounded by its retry policy). Tools with side effects should therefore be idempotent; Memora's
 * built-in ones are (memory writes deduplicate, artifacts are versioned, tasks use idempotency keys).
 */
@Component
public class WorkflowScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorkflowScheduler.class);

    private final WorkflowEngine engine;
    private final ExecutorRegistry executors;
    private final ToolGateway gateway;
    private final WorkflowProperties properties;
    private final String owner = "node-" + UUID.randomUUID().toString().substring(0, 8);
    private final ExecutorService workers;
    private final ExecutorService runners = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "memora-step-exec");
        t.setDaemon(true);
        return t;
    });
    private final Semaphore slots;
    private final AtomicBoolean polling = new AtomicBoolean();
    private volatile boolean ready;

    public WorkflowScheduler(WorkflowEngine engine, ExecutorRegistry executors, ToolGateway gateway, WorkflowProperties properties) {
        this.engine = engine;
        this.executors = executors;
        this.gateway = gateway;
        this.properties = properties;
        this.workers = Executors.newFixedThreadPool(properties.workers(), r -> {
            Thread t = new Thread(r, "memora-workflow-worker");
            t.setDaemon(true);
            return t;
        });
        this.slots = new Semaphore(properties.workers());
    }

    public String owner() {
        return owner;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (properties.recoverOnStartup()) {
            try {
                int n = TenantContext.callAsSystem(() -> engine.recoverExpired(owner, true));
                if (n > 0) {
                    log.info("Recovered {} workflow step(s) that were running when the backend stopped", n);
                }
            } catch (RuntimeException e) {
                log.warn("Startup recovery failed: {}", e.getMessage());
            }
        }
        ready = true;
    }

    @Scheduled(fixedDelayString = "${memora.workflow.poll-interval-ms:400}", initialDelay = 1000)
    public void poll() {
        if (!ready || !properties.schedulerEnabled() || !polling.compareAndSet(false, true)) {
            return;
        }
        try {
            int free = slots.availablePermits();
            if (free == 0) {
                return;
            }
            List<Claimed> claimed = TenantContext.callAsSystem(() -> engine.claim(owner, free));
            for (Claimed c : claimed) {
                slots.acquireUninterruptibly();
                try {
                    workers.submit(() -> {
                        try {
                            TenantContext.runAsTenant(c.tenantId(), () -> run(c));
                        } finally {
                            slots.release();
                        }
                        poll();
                    });
                } catch (RuntimeException e) {
                    slots.release();
                    throw e;
                }
            }
        } catch (RuntimeException e) {
            log.warn("Workflow poll failed: {}", e.getMessage());
        } finally {
            polling.set(false);
        }
    }

    @Scheduled(fixedDelayString = "${memora.workflow.recovery-interval-ms:5000}", initialDelay = 5000)
    public void recover() {
        if (!ready || !properties.schedulerEnabled()) {
            return;
        }
        try {
            int n = TenantContext.callAsSystem(() -> engine.recoverExpired(owner, false));
            if (n > 0) {
                log.info("Recovered {} workflow step(s) with expired leases", n);
            }
        } catch (RuntimeException e) {
            log.warn("Workflow lease recovery failed: {}", e.getMessage());
        }
    }

    private void run(Claimed c) {
        MDC.put("correlationId", "wf-" + c.runId().toString().substring(0, 8));
        try {
            Optional<Prepared> prepared = engine.prepare(c.tenantId(), c.stepRunId(), owner, c.attempt());
            if (prepared.isEmpty()) {
                return;
            }
            StepOutcome outcome = execute(prepared.get());
            engine.complete(c.tenantId(), c.stepRunId(), owner, c.attempt(), outcome);
        } catch (RuntimeException e) {
            log.warn("Step {} attempt {} could not be recorded: {}", c.stepRunId(), c.attempt(), e.getMessage());
        } finally {
            MDC.remove("correlationId");
        }
    }

    StepOutcome execute(Prepared p) {
        List<String> log = new ArrayList<>();
        if (p.pacingMs() > 0) {
            sleep(p.pacingMs());
        }
        if (p.fault() != null && p.attempt() <= ((Number) p.fault().getOrDefault("failAttempts", 1)).intValue()) {
            String message = "Injected fault (demo): " + p.fault().getOrDefault("message", "simulated failure")
                    + " on attempt " + p.attempt();
            log.add(message);
            return StepOutcome.failed(Boolean.FALSE.equals(p.fault().get("retryable")) ? StepOutcome.FailureKind.PERMANENT
                    : StepOutcome.FailureKind.RETRYABLE, message, log);
        }
        AgentExecutor executor = executors.find(p.agent().executor()).orElse(null);
        if (executor == null) {
            return StepOutcome.failed(StepOutcome.FailureKind.PERMANENT, "Executor '" + p.agent().executor() + "' is not available", log);
        }
        List<String> inputErrors = MiniSchema.validate(p.agent().inputSchema(), p.input());
        if (!inputErrors.isEmpty()) {
            return StepOutcome.failed(StepOutcome.FailureKind.PERMANENT,
                    "Step input does not match " + p.agent().key() + "'s input schema: " + String.join("; ", inputErrors), log);
        }
        Actor actor = Actor.agent(p.tenantId(), p.agent().agentId(), p.agent().agentName());
        ToolContext toolContext = new ToolContext(p.tenantId(), actor, p.projectId(), p.workflowId(), p.runId(), p.stepRunId(),
                p.stepKey(), p.iteration(), p.startedByUserId(), p.agent(), p.approved());
        ExecutionContext ctx = new ExecutionContext(toolContext, gateway, p.input(), p.view());
        Future<StepOutcome> future = runners.submit(TenantContext.propagate(() -> executor.execute(ctx)));
        StepOutcome outcome;
        try {
            outcome = future.get(p.timeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return StepOutcome.failed(StepOutcome.FailureKind.RETRYABLE, "Timed out after " + p.timeoutSeconds() + " s", ctx.log());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return StepOutcome.failed(StepOutcome.FailureKind.RETRYABLE, "Worker interrupted", ctx.log());
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return StepOutcome.failed(StepOutcome.FailureKind.RETRYABLE, "Executor error: " + cause.getMessage(), ctx.log());
        }
        if (outcome.status() == StepOutcome.Status.SUCCEEDED) {
            List<String> outputErrors = MiniSchema.validate(p.agent().outputSchema(), outcome.output());
            if (!outputErrors.isEmpty()) {
                return new StepOutcome(StepOutcome.Status.FAILED, outcome.output(), StepOutcome.FailureKind.PERMANENT,
                        "Output does not match " + p.agent().key() + "'s output schema: " + String.join("; ", outputErrors),
                        outcome.log());
            }
            ctx.log("Used " + ctx.toolCalls() + " tool call(s)");
        }
        return outcome;
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void shutdown() {
        ready = false;
        workers.shutdown();
        runners.shutdown();
        try {
            workers.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    Map<String, Object> describe() {
        return Json.map("owner", owner, "workers", properties.workers(), "busy", properties.workers() - slots.availablePermits());
    }
}
