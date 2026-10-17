package com.memora.orchestration.tool.builtin;

import static com.memora.orchestration.tool.builtin.Schemas.any;
import static com.memora.orchestration.tool.builtin.Schemas.enumOf;
import static com.memora.orchestration.tool.builtin.Schemas.integer;
import static com.memora.orchestration.tool.builtin.Schemas.object;
import static com.memora.orchestration.tool.builtin.Schemas.req;
import static com.memora.orchestration.tool.builtin.Schemas.string;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memora.common.Json;
import com.memora.orchestration.run.ArtifactService;
import com.memora.orchestration.run.ArtifactService.ArtifactView;
import com.memora.orchestration.run.WorkflowArtifact;
import com.memora.orchestration.tool.Tool;
import com.memora.orchestration.tool.ToolContext;
import com.memora.orchestration.tool.ToolDescriptor;
import com.memora.orchestration.tool.ToolException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Run artifacts: the inspectable documents and data each step produces. Confined to the current run. */
public final class ArtifactTools {

    private ArtifactTools() {
    }

    @Component
    public static class ArtifactWriteTool implements Tool {

        private final ArtifactService artifacts;
        private final ObjectMapper objectMapper;

        public ArtifactWriteTool(ArtifactService artifacts, ObjectMapper objectMapper) {
            this.artifacts = artifacts;
            this.objectMapper = objectMapper;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor("artifact.write", "Write artifact",
                    "Stores a document or JSON value as a new version of a named artifact in the current run, with a SHA-256 "
                            + "checksum. Existing versions are never overwritten.",
                    "artifacts", "artifact.write", true, false, 5000,
                    object(req("name", "content"), "name", string("File-like name, e.g. requirements.md", 120),
                            "kind", enumOf("markdown, json, text or code", "markdown", "json", "text", "code"),
                            "content", any("Text, or any JSON value when kind is json")),
                    object(req("id", "name", "version", "checksum")),
                    List.of("Current run only", "512 KB per version", "Content that looks like a credential is refused"),
                    "Permanent failure for invalid names, oversize or sensitive content", true, null);
        }

        @Override
        public Map<String, Object> invoke(ToolContext ctx, Map<String, Object> args) {
            String kind = args.get("kind") == null ? guessKind(String.valueOf(args.get("name"))) : String.valueOf(args.get("kind"));
            Object content = args.get("content");
            String text;
            if (content instanceof String s && !"json".equals(kind)) {
                text = s;
            } else {
                try {
                    text = content instanceof String s ? s : objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(content);
                } catch (JsonProcessingException e) {
                    throw ToolException.permanent("Content is not serialisable as JSON");
                }
            }
            ArtifactView a = artifacts.write(ctx, String.valueOf(args.get("name")), kind, text);
            return Json.map("id", a.id().toString(), "name", a.name(), "version", a.version(), "checksum", a.checksum(),
                    "sizeBytes", a.sizeBytes(), "kind", a.kind());
        }

        private static String guessKind(String name) {
            if (name.endsWith(".json")) {
                return "json";
            }
            if (name.endsWith(".md")) {
                return "markdown";
            }
            if (name.matches(".*\\.(java|ts|tsx|js|sql|py|yaml|yml|sh|css|html)$")) {
                return "code";
            }
            return "text";
        }
    }

    @Component
    public static class ArtifactReadTool implements Tool {

        private final ArtifactService artifacts;
        private final ObjectMapper objectMapper;

        public ArtifactReadTool(ArtifactService artifacts, ObjectMapper objectMapper) {
            this.artifacts = artifacts;
            this.objectMapper = objectMapper;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor("artifact.read", "Read artifact",
                    "Reads the latest (or a given) version of an artifact produced earlier in the current run. JSON artifacts "
                            + "are also returned parsed.",
                    "artifacts", "artifact.read", false, false, 5000,
                    object(req("name"), "name", string("Artifact name", 120), "version", integer("Specific version", 1, 10000)),
                    object(req("name", "content", "checksum")), List.of("Current run only"),
                    "Permanent failure when the artifact does not exist", true, null);
        }

        @Override
        public Map<String, Object> invoke(ToolContext ctx, Map<String, Object> args) {
            String name = String.valueOf(args.get("name"));
            WorkflowArtifact a = (args.get("version") instanceof Number v
                    ? artifacts.version(ctx.tenantId(), ctx.runId(), name, v.intValue())
                    : artifacts.latest(ctx.tenantId(), ctx.runId(), name))
                    .orElseThrow(() -> ToolException.permanent("No artifact named " + name + " in this run"));
            Object parsed = null;
            if ("json".equals(a.getKind())) {
                try {
                    parsed = objectMapper.readValue(a.getContent(), Object.class);
                } catch (JsonProcessingException e) {
                    parsed = null;
                }
            }
            return Json.map("name", a.getName(), "version", a.getVersion(), "kind", a.getKind(), "content", a.getContent(),
                    "json", parsed, "checksum", a.getChecksum(), "sizeBytes", a.getSizeBytes());
        }
    }

    @Component
    public static class ArtifactListTool implements Tool {

        private final ArtifactService artifacts;

        public ArtifactListTool(ArtifactService artifacts) {
            this.artifacts = artifacts;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor("artifact.list", "List artifacts", "Lists the latest version of every artifact in the current run.",
                    "artifacts", "artifact.read", false, false, 5000, object(req()), object(req("artifacts", "count")),
                    List.of("Current run only"), "Never fails for an empty run", true, null);
        }

        @Override
        public Map<String, Object> invoke(ToolContext ctx, Map<String, Object> args) {
            List<Map<String, Object>> list = artifacts.latestOfRun(ctx.tenantId(), ctx.runId()).stream()
                    .map(a -> Json.map("name", a.getName(), "version", a.getVersion(), "kind", a.getKind(),
                            "checksum", a.getChecksum(), "sizeBytes", a.getSizeBytes()))
                    .toList();
            return Json.map("artifacts", list, "count", list.size(), "names", list.stream().map(m -> m.get("name")).toList());
        }
    }
}
