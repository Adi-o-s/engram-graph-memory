package com.memora.demo;

import com.memora.agent.AgentRole;
import com.memora.agent.AgentService;
import com.memora.conversation.ConversationDtos.ConversationView;
import com.memora.conversation.ConversationService;
import com.memora.conversation.MessageRole;
import com.memora.extraction.MessageIngestionService;
import com.memora.identity.AppUser;
import com.memora.identity.AuthDtos.DemoAccount;
import com.memora.identity.AuthDtos.DemoAccountsResponse;
import com.memora.identity.IdentityAdminService;
import com.memora.identity.Tenant;
import com.memora.project.ProjectService;
import com.memora.project.ProjectService.ProjectView;
import com.memora.security.Actor;
import com.memora.security.TenantContext;
import com.memora.security.TenantRole;
import com.memora.task.TaskDtos.CreateTaskRequest;
import com.memora.task.TaskService;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Creates two demo tenants with visibly different data on first start (when
 * {@code memora.seed.enabled=true}). Idempotent: does nothing if the tenants already exist.
 * Sample memories are produced by the real extraction pipeline, not inserted directly.
 */
@Component
public class DemoSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    record SeedUser(String email, String name, Map<String, TenantRole> memberships) {
    }

    static final List<SeedUser> USERS = List.of(
            new SeedUser("alice@acme.test", "Alice Admin", Map.of("acme", TenantRole.ADMIN)),
            new SeedUser("aaron@acme.test", "Aaron Member", Map.of("acme", TenantRole.MEMBER)),
            new SeedUser("bob@globex.test", "Bob Admin", Map.of("globex", TenantRole.ADMIN)),
            new SeedUser("casey@consultant.test", "Casey Consultant",
                    Map.of("acme", TenantRole.MEMBER, "globex", TenantRole.MEMBER)));

    private final DemoProperties properties;
    private final IdentityAdminService identity;
    private final ProjectService projects;
    private final AgentService agents;
    private final ConversationService conversations;
    private final MessageIngestionService ingestion;
    private final TaskService tasks;

    public DemoSeeder(DemoProperties properties, IdentityAdminService identity, ProjectService projects,
                      AgentService agents, ConversationService conversations, MessageIngestionService ingestion,
                      TaskService tasks) {
        this.properties = properties;
        this.identity = identity;
        this.projects = projects;
        this.agents = agents;
        this.conversations = conversations;
        this.ingestion = ingestion;
        this.tasks = tasks;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        if (!properties.enabled()) {
            return;
        }
        boolean exists = TenantContext.callAsSystem(() -> identity.findTenant("acme").isPresent());
        if (exists) {
            log.info("Demo data already present; skipping seed");
            return;
        }
        log.info("Seeding demo tenants (acme, globex)");
        Tenant acme = TenantContext.callAsSystem(() -> identity.createTenant("acme", "Acme Robotics"));
        Tenant globex = TenantContext.callAsSystem(() -> identity.createTenant("globex", "Globex Health"));
        Map<String, Tenant> tenants = Map.of("acme", acme, "globex", globex);
        Map<String, AppUser> users = new java.util.HashMap<>();
        TenantContext.runAsSystem(() -> {
            for (SeedUser u : USERS) {
                AppUser user = identity.createUser(u.email(), u.name(), properties.demoPassword());
                users.put(u.email(), user);
                u.memberships().forEach((slug, role) -> identity.addMembership(tenants.get(slug).getId(), user.getId(), role));
            }
        });

        AppUser alice = users.get("alice@acme.test");
        TenantContext.runAsTenant(acme.getId(), () -> seedTenant(
                Actor.user(acme.getId(), alice.getId(), alice.getDisplayName(), TenantRole.ADMIN),
                "atlas", "Atlas Warehouse Robots", "Fleet management backend for warehouse robots",
                List.of(
                        "We use Java and Spring Boot for the backend.",
                        "We use MySQL.",
                        "We switched from MySQL to PostgreSQL.",
                        "I prefer Java over Python.",
                        "When an integration test fails, check database migrations first.",
                        "The fleet API must respond within 200 ms.",
                        "Yesterday the staging deploy failed because a Flyway migration was missing."),
                "Add battery telemetry endpoint"));

        AppUser bob = users.get("bob@globex.test");
        TenantContext.runAsTenant(globex.getId(), () -> seedTenant(
                Actor.user(globex.getId(), bob.getId(), bob.getDisplayName(), TenantRole.ADMIN),
                "pulse", "Pulse Patient Portal", "Patient-facing scheduling portal",
                List.of(
                        "We use Python and Django.",
                        "Our database is MongoDB.",
                        "I prefer Python over Java.",
                        "Never log patient identifiers.",
                        "The patient portal must support two-factor authentication.",
                        "Yesterday the nightly import job failed because of a schema mismatch.",
                        "Decision: appointment reminders are sent by SMS and email."),
                "Add appointment reminder preferences"));
        log.info("Demo data seeded");
    }

    private void seedTenant(Actor admin, String projectSlug, String projectName, String description,
                            List<String> kickoffMessages, String taskTitle) {
        ProjectView project = projects.create(admin, projectSlug, projectName, description);
        for (AgentRole role : List.of(AgentRole.PLANNER, AgentRole.DEVELOPER, AgentRole.TESTER)) {
            String name = role.name().charAt(0) + role.name().substring(1).toLowerCase();
            agents.register(admin, name, role, DemoAgents.describe(role), DemoAgents.capabilities(role), false);
        }
        ConversationView kickoff = conversations.create(admin, project.id(), projectName + " kickoff", "seed-session", null);
        for (String message : kickoffMessages) {
            ingestion.append(admin, kickoff.id(), MessageRole.USER, message, Map.of("seed", true), true, null);
        }
        tasks.create(admin, new CreateTaskRequest(project.id(), taskTitle, "Seeded example task", 3, null,
                Map.of("seeded", true)), null);
    }

    public DemoAccountsResponse describeAccounts() {
        if (!properties.enabled()) {
            return new DemoAccountsResponse(false, null, List.of());
        }
        String hint = "memora-demo".equals(properties.demoPassword())
                ? "memora-demo" : "the value of MEMORA_DEMO_PASSWORD";
        return new DemoAccountsResponse(true, hint, USERS.stream()
                .map(u -> new DemoAccount(u.email(), u.name(),
                        u.memberships().entrySet().stream().map(e -> e.getKey() + " (" + e.getValue() + ")").sorted().toList(),
                        note(u.email())))
                .toList());
    }

    private static String note(String email) {
        return switch (email) {
            case "alice@acme.test" -> "Acme admin: sees all Acme data";
            case "aaron@acme.test" -> "Acme member: sees only his own conversations and personal memories";
            case "bob@globex.test" -> "Globex admin: completely separate data";
            default -> "Member of both tenants; switch tenants to see each one separately";
        };
    }
}
