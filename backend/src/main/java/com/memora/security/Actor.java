package com.memora.security;

import java.util.UUID;

/**
 * The authenticated caller: a user acting in one tenant, or an agent that belongs to one tenant.
 * Services take an Actor explicitly so the tenant is always derived from authentication,
 * never from request bodies.
 */
public record Actor(UUID tenantId, ActorType type, UUID id, String name, TenantRole role) {

    public static Actor user(UUID tenantId, UUID userId, String name, TenantRole role) {
        return new Actor(tenantId, ActorType.USER, userId, name, role);
    }

    public static Actor agent(UUID tenantId, UUID agentId, String name) {
        return new Actor(tenantId, ActorType.AGENT, agentId, name, TenantRole.AGENT);
    }

    public static Actor system(UUID tenantId) {
        return new Actor(tenantId, ActorType.SYSTEM, null, "system", TenantRole.ADMIN);
    }

    public boolean isAdmin() {
        return role == TenantRole.ADMIN;
    }

    public boolean isAgent() {
        return type == ActorType.AGENT;
    }

    public boolean isUser() {
        return type == ActorType.USER;
    }

    public UUID userId() {
        return type == ActorType.USER ? id : null;
    }

    public UUID agentId() {
        return type == ActorType.AGENT ? id : null;
    }

    /** Users who are not admins only see their own conversations and user-scoped memories. */
    public boolean seesAllUserData() {
        return type != ActorType.USER || role == TenantRole.ADMIN;
    }
}
