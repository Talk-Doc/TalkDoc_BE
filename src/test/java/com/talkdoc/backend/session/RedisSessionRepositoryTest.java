package com.talkdoc.backend.session;

import com.talkdoc.backend.auth.AuthenticatedPrincipal;
import com.talkdoc.backend.auth.Role;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the pure token-value parsing logic; no Redis connection or Spring context involved.
 */
class RedisSessionRepositoryTest {

    @Test
    void parsesDoctorPrincipal() {
        Optional<AuthenticatedPrincipal> result = RedisSessionRepository.parsePrincipal("tok-1", "session-abc:DOCTOR");

        assertThat(result).contains(new AuthenticatedPrincipal("session-abc", Role.DOCTOR, "tok-1"));
    }

    @Test
    void parsesPatientPrincipal() {
        Optional<AuthenticatedPrincipal> result = RedisSessionRepository.parsePrincipal("tok-2", "session-xyz:PATIENT");

        assertThat(result).contains(new AuthenticatedPrincipal("session-xyz", Role.PATIENT, "tok-2"));
    }

    @Test
    void sessionIdMayContainColons() {
        // Defensive: sessionId itself never contains ':' (UUID), but the parser should still take the
        // last segment as the role rather than breaking on the first colon.
        Optional<AuthenticatedPrincipal> result = RedisSessionRepository.parsePrincipal("tok", "a:b:DOCTOR");

        assertThat(result).contains(new AuthenticatedPrincipal("a:b", Role.DOCTOR, "tok"));
    }

    @Test
    void returnsEmptyForNullValue() {
        assertThat(RedisSessionRepository.parsePrincipal("tok", null)).isEmpty();
    }

    @Test
    void returnsEmptyWhenNoColon() {
        assertThat(RedisSessionRepository.parsePrincipal("tok", "no-colon-here")).isEmpty();
    }

    @Test
    void returnsEmptyForUnknownRole() {
        assertThat(RedisSessionRepository.parsePrincipal("tok", "session-abc:UNKNOWN")).isEmpty();
    }

    @Test
    void returnsEmptyForBlankSessionId() {
        assertThat(RedisSessionRepository.parsePrincipal("tok", ":DOCTOR")).isEmpty();
    }

    @Test
    void returnsEmptyForTrailingColon() {
        assertThat(RedisSessionRepository.parsePrincipal("tok", "session-abc:")).isEmpty();
    }
}
