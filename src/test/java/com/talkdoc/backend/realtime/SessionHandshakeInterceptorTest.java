package com.talkdoc.backend.realtime;

import com.talkdoc.backend.auth.AuthenticatedPrincipal;
import com.talkdoc.backend.auth.Role;
import com.talkdoc.backend.auth.TokenResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionHandshakeInterceptorTest {

    private TokenResolver tokenResolver;
    private SessionHandshakeInterceptor interceptor;
    private WebSocketHandler wsHandler;

    @BeforeEach
    void setUp() {
        tokenResolver = mock(TokenResolver.class);
        interceptor = new SessionHandshakeInterceptor(tokenResolver);
        wsHandler = mock(WebSocketHandler.class);
    }

    private static ServletServerHttpRequest requestFor(String path, String query) {
        MockHttpServletRequest mockRequest = new MockHttpServletRequest("GET", path);
        if (query != null) {
            mockRequest.setQueryString(query);
        }
        return new ServletServerHttpRequest(mockRequest);
    }

    @Test
    void extractSessionIdReadsLastPathSegment() {
        assertThat(SessionHandshakeInterceptor.extractSessionId("/ws/sessions/abc-123")).isEqualTo("abc-123");
        assertThat(SessionHandshakeInterceptor.extractSessionId("/ws/sessions/abc-123/")).isEqualTo("abc-123");
        assertThat(SessionHandshakeInterceptor.extractSessionId("/ws/sessions/")).isNull();
        assertThat(SessionHandshakeInterceptor.extractSessionId("/other/path")).isNull();
    }

    @Test
    void missingTokenReturnsUnauthorized() {
        ServletServerHttpRequest request = requestFor("/ws/sessions/session-1", null);
        ServletServerHttpResponse response = new ServletServerHttpResponse(new MockHttpServletResponse());
        Map<String, Object> attrs = new HashMap<>();

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attrs);

        assertThat(result).isFalse();
        assertThat(response.getServletResponse().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(attrs).isEmpty();
    }

    @Test
    void invalidTokenReturnsUnauthorized() {
        when(tokenResolver.resolve("bad")).thenReturn(Optional.empty());
        ServletServerHttpRequest request = requestFor("/ws/sessions/session-1", "token=bad");
        ServletServerHttpResponse response = new ServletServerHttpResponse(new MockHttpServletResponse());
        Map<String, Object> attrs = new HashMap<>();

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attrs);

        assertThat(result).isFalse();
        assertThat(response.getServletResponse().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void tokenForAnotherSessionReturnsForbidden() {
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal("other-session", Role.DOCTOR, "tok");
        when(tokenResolver.resolve("tok")).thenReturn(Optional.of(principal));
        ServletServerHttpRequest request = requestFor("/ws/sessions/session-1", "token=tok");
        ServletServerHttpResponse response = new ServletServerHttpResponse(new MockHttpServletResponse());
        Map<String, Object> attrs = new HashMap<>();

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attrs);

        assertThat(result).isFalse();
        assertThat(response.getServletResponse().getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void validTokenSucceedsAndPopulatesAttributes() {
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal("session-1", Role.PATIENT, "tok");
        when(tokenResolver.resolve("tok")).thenReturn(Optional.of(principal));
        ServletServerHttpRequest request = requestFor("/ws/sessions/session-1", "token=tok");
        ServletServerHttpResponse response = new ServletServerHttpResponse(new MockHttpServletResponse());
        Map<String, Object> attrs = new HashMap<>();

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attrs);

        assertThat(result).isTrue();
        assertThat(attrs.get(SessionHandshakeInterceptor.ATTR_PRINCIPAL)).isEqualTo(principal);
        assertThat(attrs.get(SessionHandshakeInterceptor.ATTR_SESSION_ID)).isEqualTo("session-1");
    }

    @Test
    void tokenFallsBackToHeaderWhenQueryParamMissing() {
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal("session-1", Role.DOCTOR, "tok-header");
        when(tokenResolver.resolve("tok-header")).thenReturn(Optional.of(principal));
        MockHttpServletRequest mockRequest = new MockHttpServletRequest("GET", "/ws/sessions/session-1");
        mockRequest.addHeader("X-Session-Token", "tok-header");
        ServletServerHttpRequest request = new ServletServerHttpRequest(mockRequest);
        ServletServerHttpResponse response = new ServletServerHttpResponse(new MockHttpServletResponse());
        Map<String, Object> attrs = new HashMap<>();

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attrs);

        assertThat(result).isTrue();
        assertThat(attrs.get(SessionHandshakeInterceptor.ATTR_SESSION_ID)).isEqualTo("session-1");
    }

    @Test
    void queryParamTakesPrecedenceOverHeader() {
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal("session-1", Role.DOCTOR, "tok-query");
        when(tokenResolver.resolve("tok-query")).thenReturn(Optional.of(principal));
        MockHttpServletRequest mockRequest = new MockHttpServletRequest("GET", "/ws/sessions/session-1");
        mockRequest.setQueryString("token=tok-query");
        mockRequest.addHeader("X-Session-Token", "tok-header-should-be-ignored");
        ServletServerHttpRequest request = new ServletServerHttpRequest(mockRequest);
        ServletServerHttpResponse response = new ServletServerHttpResponse(new MockHttpServletResponse());
        Map<String, Object> attrs = new HashMap<>();

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attrs);

        assertThat(result).isTrue();
    }
}
