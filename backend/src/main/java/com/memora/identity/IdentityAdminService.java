package com.memora.identity;

import com.memora.security.TenantRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Provisioning operations used by the seeder and tests. Callers choose the tenant scope. */
@Service
public class IdentityAdminService {

    private final TenantRepository tenants;
    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final PasswordEncoder passwordEncoder;

    public IdentityAdminService(TenantRepository tenants, AppUserRepository users, MembershipRepository memberships,
                                PasswordEncoder passwordEncoder) {
        this.tenants = tenants;
        this.users = users;
        this.memberships = memberships;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public Tenant createTenant(String slug, String name) {
        return tenants.save(new Tenant(slug, name));
    }

    @Transactional(readOnly = true)
    public Optional<Tenant> findTenant(String slug) {
        return tenants.findBySlug(slug);
    }

    @Transactional(readOnly = true)
    public Optional<Tenant> findTenant(UUID id) {
        return tenants.findById(id);
    }

    @Transactional
    public AppUser createUser(String email, String displayName, String rawPassword) {
        return users.save(new AppUser(email, displayName, passwordEncoder.encode(rawPassword)));
    }

    @Transactional(readOnly = true)
    public Optional<AppUser> findUser(String email) {
        return users.findByEmail(email.toLowerCase());
    }

    @Transactional
    public Membership addMembership(UUID tenantId, UUID userId, TenantRole role) {
        return memberships.save(new Membership(tenantId, userId, role));
    }

    @Transactional(readOnly = true)
    public List<Membership> membershipsOfTenant(UUID tenantId) {
        return memberships.findByTenantId(tenantId);
    }

    @Transactional(readOnly = true)
    public List<Membership> membershipsOfUser(UUID userId) {
        return memberships.findByUserId(userId);
    }

    @Transactional(readOnly = true)
    public List<AppUser> users(List<UUID> ids) {
        return users.findAllById(ids);
    }
}
