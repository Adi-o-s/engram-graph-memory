package com.memora.identity;

import com.memora.security.TenantRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank @Size(max = 200) String password,
                               @Size(max = 64) String tenantSlug) {
    }

    public record SwitchTenantRequest(@NotBlank @Size(max = 64) String tenantSlug) {
    }

    public record TenantSummary(UUID id, String slug, String name, TenantRole role) {
    }

    public record UserSummary(UUID id, String email, String displayName) {
    }

    public record LoginResponse(String token, Instant expiresAt, UserSummary user, TenantSummary tenant,
                                List<TenantSummary> memberships) {
    }

    public record MeResponse(String actorType, UUID actorId, String name, TenantSummary tenant,
                             List<TenantSummary> memberships) {
    }

    public record DemoAccount(String email, String displayName, List<String> tenants, String note) {
    }

    public record DemoAccountsResponse(boolean seeded, String passwordHint, List<DemoAccount> accounts) {
    }
}
