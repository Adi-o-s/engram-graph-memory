package com.memora.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public class ActorAuthentication extends AbstractAuthenticationToken {

    private final Actor actor;

    public ActorAuthentication(Actor actor) {
        super(List.of(new SimpleGrantedAuthority("ROLE_" + actor.role().name())));
        this.actor = actor;
        setAuthenticated(true);
    }

    public Actor actor() {
        return actor;
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public Object getPrincipal() {
        return actor;
    }

    @Override
    public String getName() {
        return actor.name();
    }
}
