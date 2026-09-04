package com.talkdoc.backend.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method (or class) as requiring a session token whose role is one of {@link #value()}.
 * The path must contain a {sessionId} variable; the token must belong to that session.
 * Empty value = any role, but a valid token for the path's session is still required.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {
    Role[] value() default {};
}
