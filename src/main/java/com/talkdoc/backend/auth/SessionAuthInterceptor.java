package com.talkdoc.backend.auth;

import com.talkdoc.backend.common.ApiException;
import com.talkdoc.backend.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

/**
 * Enforces {@link RequireRole} on /api/** handlers.
 * Token is read from "Authorization: Bearer <token>" or "X-Session-Token".
 */
@Component
public class SessionAuthInterceptor implements HandlerInterceptor {

    public static final String HEADER_SESSION_TOKEN = "X-Session-Token";
    private static final String PATH_VAR_SESSION_ID = "sessionId";

    private final TokenResolver tokenResolver;

    public SessionAuthInterceptor(TokenResolver tokenResolver) {
        this.tokenResolver = tokenResolver;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RequireRole requireRole = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RequireRole.class);
        if (requireRole == null) {
            requireRole = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RequireRole.class);
        }
        if (requireRole == null) {
            return true;
        }

        String token = extractToken(request).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        AuthenticatedPrincipal principal = tokenResolver.resolve(token)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));

        String pathSessionId = pathSessionId(request);
        if (pathSessionId != null && !pathSessionId.equals(principal.sessionId())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "토큰이 이 세션에 속하지 않습니다.");
        }
        Role[] allowed = requireRole.value();
        if (allowed.length > 0 && Arrays.stream(allowed).noneMatch(principal::is)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "이 역할로는 허용되지 않는 요청입니다.");
        }
        AuthContext.set(request, principal);
        return true;
    }

    public static Optional<String> extractToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String value = authorization.substring(7).trim();
            if (!value.isEmpty()) return Optional.of(value);
        }
        String header = request.getHeader(HEADER_SESSION_TOKEN);
        if (header != null && !header.isBlank()) {
            return Optional.of(header.trim());
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static String pathSessionId(HttpServletRequest request) {
        Object attr = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (attr instanceof Map<?, ?> vars) {
            Object id = ((Map<String, String>) vars).get(PATH_VAR_SESSION_ID);
            return id == null ? null : id.toString();
        }
        return null;
    }
}
