package com.memora.context;

import com.memora.conversation.ConversationDtos.MessageView;
import com.memora.handoff.HandoffDtos.HandoffView;
import com.memora.memory.MemoryDtos.MemoryView;
import com.memora.task.TaskDtos.TaskView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public final class ContextDtos {

    private ContextDtos() {
    }

    public record ContextRequest(@Size(max = 1000) String query, UUID projectId, UUID conversationId, UUID taskId,
                                 @Min(1) @Max(50) Integer maxMemories, @Min(0) @Max(50) Integer maxMessages,
                                 @Min(200) @Max(20000) Integer maxChars) {
    }

    public record ContextStats(int chars, int maxChars, int memoriesIncluded, int memoriesOmitted, int messagesIncluded,
                               int messagesOmitted, boolean truncated) {
    }

    public record ContextBundle(String query, TaskView task, HandoffView latestHandoff, List<MemoryView> facts,
                                List<MemoryView> procedures, List<MemoryView> episodes, List<MessageView> messages,
                                String rendered, ContextStats stats) {
    }
}
