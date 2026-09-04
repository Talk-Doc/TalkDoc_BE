package com.talkdoc.backend.auth;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;

/** Request-scoped holder for the resolved principal. */
public final class AuthContext {

    public static final String ATTRIBUTE = AuthenticatedPrincipal.class.getName();

    private AuthContext() {
    }

    public static void set(HttpServletRequest request, AuthenticatedPrincipal principal) {
        request.setAttribute(ATTRIBUTE, principal);
    }

    public static Optional<AuthenticatedPrincipal> get(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value instanceof AuthenticatedPrincipal p ? Optional.of(p) : Optional.empty();
    }
}
