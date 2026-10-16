package com.memora.identity;

import com.memora.common.ApiException;
import com.memora.identity.AuthDtos.LoginResponse;
import com.memora.identity.AuthDtos.MeResponse;
import com.memora.identity.AuthDtos.TenantSummary;
import com.memora.identity.AuthDtos.UserSummary;
import com.memora.security.Actor;
import com.memora.security.JwtService;
import com.memora.security.TenantContext;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {


    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final TenantRepository tenants;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    // Checked for unknown emails so response time does not reveal which emails exist.
    private final String dummyHash;

    public AuthService(AppUserRepository users, MembershipRepository memberships, TenantRepository tenants,
                       PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.memberships = memberships;
        this.tenants = tenants;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public LoginResponse login(String email, String password, String tenantSlug) {
        // No tenant is known before authentication, so the lookup runs in system scope.
        return TenantContext.callAsSystem(() -> {
            AppUser user = users.findByEmail(email.trim().toLowerCase()).orElse(null);
            if (user == null) {
                passwordEncoder.matches(password, dummyHash);
                throw new BadCredentialsException("bad credentials");
            }
            if (!passwordEncoder.matches(password, user.getPasswordHash())) {
                throw new BadCredentialsException("bad credentials");
            }
            return issueFor(user, tenantSlug);
        });
    }

    public LoginResponse switchTenant(Actor actor, String tenantSlug) {
        if (!actor.isUser()) {
            throw ApiException.forbidden("Only users can switch tenants");
        }
        return TenantContext.callAsSystem(() -> {
            AppUser user = users.findById(actor.userId()).orElseThrow(() -> ApiException.notFound("User", actor.userId()));
            return issueFor(user, tenantSlug);
        });
    }

    public MeResponse me(Actor actor) {
        return TenantContext.callAsSystem(() -> {
            Tenant tenant = tenants.findById(actor.tenantId()).orElseThrow();
            List<TenantSummary> all = actor.isUser() ? summaries(actor.userId()) : List.of();
            return new MeResponse(actor.type().name(), actor.id(), actor.name(),
                    new TenantSummary(tenant.getId(), tenant.getSlug(), tenant.getName(), actor.role()), all);
        });
    }

    private LoginResponse issueFor(AppUser user, String tenantSlug) {
        List<TenantSummary> all = summaries(user.getId());
        if (all.isEmpty()) {
            throw ApiException.forbidden("This user is not a member of any tenant");
        }
        TenantSummary selected = tenantSlug == null || tenantSlug.isBlank()
                ? all.get(0)
                : all.stream().filter(t -> t.slug().equals(tenantSlug)).findFirst()
                        // Same answer for "no such tenant" and "not a member": no tenant enumeration.
                        .orElseThrow(() -> ApiException.forbidden("You are not a member of tenant " + tenantSlug));
        JwtService.IssuedToken token = jwtService.issue(user.getId(), user.getDisplayName(), selected.id(), selected.role());
        return new LoginResponse(token.token(), token.expiresAt(),
                new UserSummary(user.getId(), user.getEmail(), user.getDisplayName()), selected, all);
    }

    private List<TenantSummary> summaries(UUID userId) {
        List<Membership> ms = memberships.findByUserId(userId);
        Map<UUID, Tenant> byId = tenants.findAllById(ms.stream().map(Membership::getTenantId).toList()).stream()
                .collect(Collectors.toMap(Tenant::getId, Function.identity()));
        return ms.stream()
                .map(m -> {
                    Tenant t = byId.get(m.getTenantId());
                    return new TenantSummary(t.getId(), t.getSlug(), t.getName(), m.getRole());
                })
                .sorted(Comparator.comparing(TenantSummary::slug))
                .toList();
    }
}
