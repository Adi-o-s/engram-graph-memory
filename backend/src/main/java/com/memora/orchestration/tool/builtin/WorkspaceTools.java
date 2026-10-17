package com.memora.orchestration.tool.builtin;

import static com.memora.orchestration.tool.builtin.Schemas.integer;
import static com.memora.orchestration.tool.builtin.Schemas.object;
import static com.memora.orchestration.tool.builtin.Schemas.req;
import static com.memora.orchestration.tool.builtin.Schemas.string;

import com.memora.common.Json;
import com.memora.orchestration.tool.Tool;
import com.memora.orchestration.tool.ToolContext;
import com.memora.orchestration.tool.ToolDescriptor;
import com.memora.orchestration.tool.ToolException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Read-only file access and allow-listed commands inside one configured directory. Paths are resolved
 * against the canonical workspace root and rejected if they leave it (including through symbolic links).
 * Commands are fixed argument vectors chosen by an operator in configuration, started without a shell;
 * agents can only name a command id, never supply arguments.
 */
public final class WorkspaceTools {

    private WorkspaceTools() {
    }

    /** Resolves a relative path inside the workspace or throws DENIED. Exposed for tests. */
    public static Path resolveInside(WorkspaceProperties props, String relative) {
        if (!props.enabled()) {
            throw ToolException.denied("No workspace is configured (set MEMORA_WORKSPACE_ROOT)");
        }
        String rel = relative == null ? "" : relative.trim();
        if (rel.contains("\0") || rel.startsWith("/") || rel.startsWith("\\") || rel.matches("^[A-Za-z]:.*")) {
            throw ToolException.denied("Workspace paths must be relative: " + rel);
        }
        try {
            Path root = Path.of(props.root()).toRealPath();
            Path target = root.resolve(rel).normalize();
            if (!target.startsWith(root)) {
                throw ToolException.denied("Path " + rel + " is outside the workspace");
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                Path real = target.toRealPath();
                if (!real.startsWith(root)) {
                    throw ToolException.denied("Path " + rel + " resolves outside the workspace");
                }
                return real;
            }
            return target;
        } catch (IOException e) {
            throw ToolException.permanent("Workspace is not readable: " + e.getMessage());
        }
    }

    static String unavailable(WorkspaceProperties props) {
        return props.enabled() ? null : "no workspace configured (MEMORA_WORKSPACE_ROOT is empty)";
    }

    @Component
    public static class ListFilesTool implements Tool {

        private final WorkspaceProperties props;

        public ListFilesTool(WorkspaceProperties props) {
            this.props = props;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor("workspace.list", "List workspace files",
                    "Lists files under a directory of the configured project workspace (read-only).",
                    "workspace", "workspace.read", false, false, 5000,
                    object(req(), "path", string("Relative directory (default: root)", 500), "maxEntries", integer("Limit", 1, 1000)),
                    object(req("entries")), List.of("Confined to MEMORA_WORKSPACE_ROOT", "No symlink escape", "Read-only"),
                    "Denied outside the workspace", props.enabled(), unavailable(props));
        }

        @Override
        public Map<String, Object> invoke(ToolContext ctx, Map<String, Object> args) {
            Path dir = resolveInside(props, (String) args.get("path"));
            int max = args.get("maxEntries") instanceof Number n ? n.intValue() : 200;
            Path root = resolveInside(props, "");
            List<Map<String, Object>> entries = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(dir, 4)) {
                walk.filter(p -> !p.equals(dir)).filter(p -> !p.toString().contains("node_modules") && !p.toString().contains(".git"))
                        .limit(max).forEach(p -> {
                            try {
                                entries.add(Json.map("path", root.relativize(p).toString().replace('\\', '/'),
                                        "type", Files.isDirectory(p) ? "dir" : "file", "sizeBytes", Files.isDirectory(p) ? null : Files.size(p)));
                            } catch (IOException e) {
                                // skip unreadable entries
                            }
                        });
            } catch (IOException e) {
                throw ToolException.permanent("Cannot list " + args.get("path") + ": " + e.getMessage());
            }
            return Json.map("entries", entries, "count", entries.size());
        }
    }

    @Component
    public static class ReadFileTool implements Tool {

        private final WorkspaceProperties props;

        public ReadFileTool(WorkspaceProperties props) {
            this.props = props;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor("workspace.read_file", "Read workspace file",
                    "Reads a text file from the configured project workspace (read-only, size-limited).",
                    "workspace", "workspace.read", false, false, 5000,
                    object(req("path"), "path", string("Relative file path", 500), "maxBytes", integer("Limit", 1, 1_000_000)),
                    object(req("content")), List.of("Confined to MEMORA_WORKSPACE_ROOT", "No symlink escape", "Size-limited"),
                    "Denied outside the workspace; permanent failure when missing", props.enabled(), unavailable(props));
        }

        @Override
        public Map<String, Object> invoke(ToolContext ctx, Map<String, Object> args) {
            Path file = resolveInside(props, String.valueOf(args.get("path")));
            if (!Files.isRegularFile(file)) {
                throw ToolException.permanent("No file at " + args.get("path"));
            }
            int max = Math.min(args.get("maxBytes") instanceof Number n ? n.intValue() : props.maxReadBytes(), props.maxReadBytes());
            try (InputStream in = Files.newInputStream(file)) {
                byte[] bytes = in.readNBytes(max + 1);
                boolean truncated = bytes.length > max;
                String content = new String(bytes, 0, Math.min(bytes.length, max), StandardCharsets.UTF_8);
                return Json.map("path", args.get("path"), "content", content, "truncated", truncated, "sizeBytes", Files.size(file));
            } catch (IOException e) {
                throw ToolException.permanent("Cannot read " + args.get("path") + ": " + e.getMessage());
            }
        }
    }

    @Component
    public static class RunCommandTool implements Tool {

        private final WorkspaceProperties props;

        public RunCommandTool(WorkspaceProperties props) {
            this.props = props;
        }

        @Override
        public ToolDescriptor descriptor() {
            boolean available = props.enabled() && !props.commands().isEmpty();
            return new ToolDescriptor("command.run", "Run an allow-listed command",
                    "Runs one operator-configured command (for example the project's test suite) in the workspace, without a "
                            + "shell. Agents choose a command id; they cannot pass arguments. Requires an approval gate.",
                    "workspace", "command.execute", true, true, props.commandTimeoutSeconds() * 1000,
                    object(req("command"), "command", string("Command id from memora.workspace.commands", 60)),
                    object(req("exitCode", "passed")),
                    List.of("Allow-listed ids only: " + props.commands().keySet(), "No shell", "Timeout " + props.commandTimeoutSeconds() + "s",
                            "Requires approval"),
                    "Retryable on timeout; permanent for unknown ids", available,
                    available ? null : "no workspace or no allow-listed commands configured");
        }

        @Override
        public Map<String, Object> invoke(ToolContext ctx, Map<String, Object> args) {
            String id = String.valueOf(args.get("command"));
            List<String> argv = props.commands().get(id);
            if (argv == null || argv.isEmpty()) {
                throw ToolException.denied("Command '" + id + "' is not allow-listed (allowed: " + props.commands().keySet() + ")");
            }
            Path root = resolveInside(props, "");
            long start = System.nanoTime();
            Path log = null;
            try {
                // Output goes to a file so a chatty command can never block on a full pipe.
                log = Files.createTempFile("memora-cmd-", ".log");
                Process p = new ProcessBuilder(argv).directory(root.toFile()).redirectErrorStream(true)
                        .redirectOutput(log.toFile()).start();
                boolean done = p.waitFor(props.commandTimeoutSeconds(), TimeUnit.SECONDS);
                if (!done) {
                    p.destroyForcibly();
                    throw ToolException.retryable("Command " + id + " timed out after " + props.commandTimeoutSeconds() + "s");
                }
                int exit = p.exitValue();
                String text = Files.readString(log, StandardCharsets.UTF_8);
                return Json.map("command", id, "exitCode", exit, "passed", exit == 0,
                        "output", text.length() > 8000 ? text.substring(text.length() - 8000) : text,
                        "durationMs", (System.nanoTime() - start) / 1_000_000);
            } catch (IOException e) {
                throw ToolException.permanent("Could not start " + id + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ToolException.retryable("Interrupted while running " + id);
            } finally {
                if (log != null) {
                    try {
                        Files.deleteIfExists(log);
                    } catch (IOException ignored) {
                        // temp file cleanup is best effort
                    }
                }
            }
        }
    }
}
