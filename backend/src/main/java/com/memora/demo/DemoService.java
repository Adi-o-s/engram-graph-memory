package com.memora.demo;

import com.memora.agent.Agent;
import com.memora.agent.AgentRole;
import com.memora.agent.AgentService;
import com.memora.common.ApiException;
import com.memora.conversation.ConversationDtos.ConversationView;
import com.memora.conversation.ConversationService;
import com.memora.conversation.MessageRole;
import com.memora.extraction.MessageIngestionService;
import com.memora.extraction.MessageIngestionService.ExtractedWrite;
import com.memora.extraction.MessageIngestionService.IngestResult;
import com.memora.handoff.HandoffDtos.HandoffView;
import com.memora.handoff.HandoffService;
import com.memora.memory.MemoryDtos.MemoryView;
import com.memora.memory.MemoryQuery;
import com.memora.memory.MemorySearchService;
import com.memora.project.ProjectService;
import com.memora.project.ProjectService.ProjectView;
import com.memora.security.Actor;
import com.memora.task.TaskDtos.TaskEventView;
import com.memora.task.TaskDtos.TaskView;
import com.memora.task.TaskService;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;

/**
 * The guided demo. Each run gets its own project so it is repeatable and never disturbs other data:
 * teach (user messages -> extracted memories, including a superseded fact), then Planner,
 * Developer and Tester steps that coordinate only through persisted handoffs and shared memory.
 */
@Service
public class DemoService {

    static final List<String> KICKOFF = List.of(
            "We use Java and Spring Boot for the backend.",
            "We use MySQL.",
            "We switched from MySQL to PostgreSQL.",
            "I prefer small pull requests over big ones.",
            "When an integration test fails, check database migrations first.",
            "Yesterday the staging deploy failed because a migration was missing.");

    private final ProjectService projects;
    private final ConversationService conversations;
    private final MessageIngestionService ingestion;
    private final AgentService agents;
    private final DemoAgents demoAgents;
    private final TaskService tasks;
    private final HandoffService handoffs;
    private final MemorySearchService search;

    public DemoService(ProjectService projects, ConversationService conversations, MessageIngestionService ingestion,
                       AgentService agents, DemoAgents demoAgents, TaskService tasks, HandoffService handoffs,
                       MemorySearchService search) {
        this.projects = projects;
        this.conversations = conversations;
        this.ingestion = ingestion;
        this.agents = agents;
        this.demoAgents = demoAgents;
        this.tasks = tasks;
        this.handoffs = handoffs;
        this.search = search;
    }

    public record TeachResult(ProjectView project, ConversationView conversation, List<TaughtMessage> messages) {
    }

    public record TaughtMessage(String text, List<ExtractedWrite> extracted) {
    }

    public record RunStatus(ProjectView project, ConversationView conversation, TaskView task, List<HandoffView> handoffs,
                            List<TaskEventView> events, List<MemoryView> memories, String nextStep) {
    }

    /** Step 1: a user states facts in a new demo project; the extractor turns them into memories. */
    public TeachResult start(Actor user) {
        requireUser(user);
        String stamp = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("MMdd-HHmmss"));
        String slug = "demo-" + stamp + "-" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000, 0xffff));
        // Projects are admin-managed; the demo creates its own sandbox project on the user's behalf.
        ProjectView project = projects.create(Actor.system(user.tenantId()), slug, "Guided demo " + stamp,
                "Sandbox project created by the guided demo");
        ConversationView conversation = conversations.create(user, project.id(), "Kickoff (" + stamp + ")", "demo-" + stamp, null);
        List<TaughtMessage> taught = new ArrayList<>();
        for (String text : KICKOFF) {
            IngestResult r = ingestion.append(user, conversation.id(), MessageRole.USER, text, Map.of("demo", true), true, null);
            taught.add(new TaughtMessage(text, r.extracted()));
        }
        return new TeachResult(project, conversations.get(user, conversation.id()), taught);
    }

    public DemoAgents.StepResult plan(Actor user, UUID projectId) {
        requireUser(user);
        projects.get(user, projectId);
        Agent planner = agents.requireByRole(user, AgentRole.PLANNER);
        Agent developer = agents.requireByRole(user, AgentRole.DEVELOPER);
        return demoAgents.plan(asAgent(user, planner), projectId, developer.getId(), kickoffConversation(user, projectId));
    }

    public DemoAgents.StepResult develop(Actor user, UUID projectId) {
        requireUser(user);
        projects.get(user, projectId);
        Agent developer = agents.requireByRole(user, AgentRole.DEVELOPER);
        Agent tester = agents.requireByRole(user, AgentRole.TESTER);
        return demoAgents.develop(asAgent(user, developer), projectId, tester.getId(), kickoffConversation(user, projectId));
    }

    public DemoAgents.StepResult test(Actor user, UUID projectId, boolean failFirst) {
        requireUser(user);
        projects.get(user, projectId);
        Agent tester = agents.requireByRole(user, AgentRole.TESTER);
        return demoAgents.test(asAgent(user, tester), projectId, failFirst, kickoffConversation(user, projectId));
    }

    public RunStatus status(Actor user, UUID projectId) {
        ProjectView project = projects.get(user, projectId);
        var convs = conversations.list(user, projectId, null, 0, 1).items();
        ConversationView conversation = convs.isEmpty() ? null : convs.get(0);
        var taskList = tasks.list(user, projectId, null, null, 0, 1).items();
        TaskView task = taskList.isEmpty() ? null : taskList.get(0);
        List<HandoffView> taskHandoffs = task == null ? List.of() : handoffs.forTask(user, task.id());
        List<TaskEventView> events = task == null ? List.of() : tasks.events(user, task.id());
        List<MemoryView> memories = search.search(user, MemoryQuery.builder().project(projectId).includeShared(false)
                .includeHistory(true).limit(100).sort(MemoryQuery.Sort.RECENT).build()).items();
        return new RunStatus(project, conversation, task, taskHandoffs, events, memories, nextStep(task, taskHandoffs));
    }

    public List<ProjectView> runs(Actor user) {
        return projects.list(user).stream().filter(p -> p.slug().startsWith("demo-"))
                .sorted((a, b) -> b.createdAt().compareTo(a.createdAt())).toList();
    }

    private static String nextStep(TaskView task, List<HandoffView> hs) {
        if (task == null) {
            return "plan";
        }
        if (task.status().name().equals("DONE")) {
            return "done";
        }
        if (hs.isEmpty()) {
            return "plan";
        }
        HandoffView last = hs.get(hs.size() - 1);
        boolean toTester = hs.size() >= 2;
        if (!toTester) {
            return "develop";
        }
        return last.status().name().equals("DEAD_LETTER") ? "done" : "test";
    }

    private UUID kickoffConversation(Actor user, UUID projectId) {
        var convs = conversations.list(user, projectId, null, 0, 1).items();
        return convs.isEmpty() ? null : convs.get(0).id();
    }

    /** Demo agents act under their own identity in the caller's tenant. */
    private static Actor asAgent(Actor user, Agent agent) {
        return Actor.agent(user.tenantId(), agent.getId(), agent.getName());
    }

    private static void requireUser(Actor actor) {
        if (!actor.isUser()) {
            throw ApiException.forbidden("The guided demo is started by a signed-in user");
        }
    }
}
