package com.talkdoc.backend.auth;

import java.util.Optional;

/**
 * Looks a raw token up in storage. Implemented by the session repository (WP1).
 */
public interface TokenResolver {

    Optional<AuthenticatedPrincipal> resolve(String token);
}
