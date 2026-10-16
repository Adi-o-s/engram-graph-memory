package com.memora.identity;

import com.memora.identity.AuthDtos.LoginRequest;
import com.memora.identity.AuthDtos.LoginResponse;
import com.memora.identity.AuthDtos.MeResponse;
import com.memora.identity.AuthDtos.SwitchTenantRequest;
import com.memora.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Login, current principal and tenant selection")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    @Operation(summary = "Exchange email and password for a tenant-scoped bearer token")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.email(), request.password(), request.tenantSlug());
    }

    @PostMapping("/switch-tenant")
    @Operation(summary = "Get a new token for another tenant the user belongs to")
    public LoginResponse switchTenant(Actor actor, @Valid @RequestBody SwitchTenantRequest request) {
        return authService.switchTenant(actor, request.tenantSlug());
    }

    @GetMapping("/me")
    @Operation(summary = "Describe the authenticated user or agent")
    public MeResponse me(Actor actor) {
        return authService.me(actor);
    }
}
