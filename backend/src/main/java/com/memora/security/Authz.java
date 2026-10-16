package com.memora.security;

import com.memora.common.ApiException;

/** Small, explicit authorization checks shared by services. */
public final class Authz {

    private Authz() {
    }

    public static void requireAdmin(Actor actor) {
        if (!actor.isAdmin()) {
            throw ApiException.forbidden("This action requires the tenant ADMIN role");
        }
    }

    public static void requireUser(Actor actor) {
        if (!actor.isUser()) {
            throw ApiException.forbidden("This action is only available to signed-in users");
        }
    }
}
