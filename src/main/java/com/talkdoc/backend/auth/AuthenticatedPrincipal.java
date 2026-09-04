package com.talkdoc.backend.auth;

/** Result of resolving a session token. Injected into controllers as a method argument. */
public record AuthenticatedPrincipal(String sessionId, Role role, String token) {

    public boolean is(Role expected) {
        return role == expected;
    }
}
