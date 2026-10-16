package com.memora.security;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Thread-bound database scope. Every pooled connection handed out by {@link TenantAwareDataSource}
 * is stamped with this scope, which PostgreSQL row-level security then enforces.
 *
 * <ul>
 *   <li>tenant scope: only that tenant's rows are visible (all authenticated requests)</li>
 *   <li>system scope: RLS bypass, used only for login lookups, seeding and lease recovery</li>
 *   <li>no scope: nothing tenant-owned is visible</li>
 * </ul>
 */
public final class TenantContext {

    private record Scope(UUID tenantId, boolean system) {
    }

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static UUID tenantId() {
        Scope scope = CURRENT.get();
        return scope == null ? null : scope.tenantId();
    }

    public static boolean isSystem() {
        Scope scope = CURRENT.get();
        return scope != null && scope.system();
    }

    public static void setTenant(UUID tenantId) {
        CURRENT.set(new Scope(tenantId, false));
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static <T> T callAsTenant(UUID tenantId, Supplier<T> work) {
        return withScope(new Scope(tenantId, false), work);
    }

    public static void runAsTenant(UUID tenantId, Runnable work) {
        withScope(new Scope(tenantId, false), () -> {
            work.run();
            return null;
        });
    }

    public static <T> T callAsSystem(Supplier<T> work) {
        return withScope(new Scope(null, true), work);
    }

    public static void runAsSystem(Runnable work) {
        callAsSystem(() -> {
            work.run();
            return null;
        });
    }

    /** Wraps a task so it runs on another thread with the caller's scope. */
    public static <T> Callable<T> propagate(Callable<T> task) {
        Scope captured = CURRENT.get();
        return () -> {
            Scope previous = CURRENT.get();
            restore(captured);
            try {
                return task.call();
            } finally {
                restore(previous);
            }
        };
    }

    private static <T> T withScope(Scope scope, Supplier<T> work) {
        Scope previous = CURRENT.get();
        CURRENT.set(scope);
        try {
            return work.get();
        } finally {
            restore(previous);
        }
    }

    private static void restore(Scope scope) {
        if (scope == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(scope);
        }
    }
}
