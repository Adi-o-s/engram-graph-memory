package com.memora.orchestration.tool.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memora.orchestration.tool.ToolException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceToolsTest {

    @TempDir
    Path tmp;

    @Test
    void pathsAreConfinedToTheWorkspace() throws Exception {
        Path root = Files.createDirectory(tmp.resolve("workspace"));
        Files.writeString(root.resolve("README.md"), "hello");
        Path outside = Files.writeString(tmp.resolve("secret.txt"), "no");
        WorkspaceProperties props = new WorkspaceProperties(root.toString(), Map.of(), null, null);

        assertThat(WorkspaceTools.resolveInside(props, "README.md")).isEqualTo(root.toRealPath().resolve("README.md"));
        for (String bad : List.of("../secret.txt", "a/../../secret.txt", "/etc/passwd", "\\windows", "C:/Windows")) {
            assertThatThrownBy(() -> WorkspaceTools.resolveInside(props, bad)).as(bad).isInstanceOf(ToolException.class)
                    .extracting(e -> ((ToolException) e).kind()).isEqualTo(ToolException.Kind.DENIED);
        }
        try {
            Files.createSymbolicLink(root.resolve("escape"), outside);
            assertThatThrownBy(() -> WorkspaceTools.resolveInside(props, "escape")).isInstanceOf(ToolException.class)
                    .hasMessageContaining("outside the workspace");
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            // symbolic links not permitted on this file system; the canonical-path check is covered above
        }
    }

    @Test
    void withoutAWorkspaceTheToolsAreUnavailableAndDenied() {
        WorkspaceProperties none = new WorkspaceProperties(null, Map.of(), null, null);
        assertThat(new WorkspaceTools.ReadFileTool(none).descriptor().available()).isFalse();
        assertThat(new WorkspaceTools.RunCommandTool(none).descriptor().requiresApproval()).isTrue();
        assertThatThrownBy(() -> WorkspaceTools.resolveInside(none, "x")).isInstanceOf(ToolException.class);
        WorkspaceProperties noCommands = new WorkspaceProperties(tmp.toString(), Map.of(), null, null);
        assertThat(new WorkspaceTools.RunCommandTool(noCommands).descriptor().available()).isFalse();
    }
}
