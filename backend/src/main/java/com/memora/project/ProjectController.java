package com.memora.project;

import com.memora.project.ProjectService.ProjectView;
import com.memora.security.Actor;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects")
@Tag(name = "Projects", description = "Workspaces inside the caller's tenant")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping
    public List<ProjectView> list(Actor actor) {
        return projectService.list(actor);
    }

    @GetMapping("/{id}")
    public ProjectView get(Actor actor, @PathVariable UUID id) {
        return projectService.get(actor, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectView create(Actor actor, @Valid @RequestBody CreateProjectRequest request) {
        return projectService.create(actor, request.slug(), request.name(), request.description());
    }

    public record CreateProjectRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "[a-z0-9][a-z0-9-]*", message = "lowercase letters, digits and dashes") String slug,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description) {
    }
}
