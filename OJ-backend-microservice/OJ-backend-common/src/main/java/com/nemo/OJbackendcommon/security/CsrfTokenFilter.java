package com.nemo.OJbackendcommon.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.regex.Pattern;

/** Synchronizer-token CSRF check. Origin/SameSite checks at the gateway remain defense in depth. */
public class CsrfTokenFilter extends OncePerRequestFilter {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Set<String> READ_ONLY_POST_SUFFIXES = Set.of(
            "/list/page", "/list/page/vo", "/my/list/page/vo", "/question_submit/list/page",
            "/question_submit/my/list/page");
    // These service-to-service callbacks require a signed request in the controller.
    // They have no browser session, so the synchronizer-token check cannot apply.
    private static final Pattern SIGNED_AI_CALLBACK = Pattern.compile(
            "^/api/question/internal/ai/tasks/[0-9a-fA-F-]{36}/"
                    + "(claim|context|complete|fail|heartbeat|validations(/[0-9a-fA-F-]{36})?"
                    + "|calls/[0-9a-fA-F-]{36}/(start|finish))$");

    @Value("${security.csrf.enabled:false}")
    private boolean enabled;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!enabled || SAFE_METHODS.contains(request.getMethod()) || path.contains("/inner/")
                || SIGNED_AI_CALLBACK.matcher(path).matches()) return true;
        return READ_ONLY_POST_SUFFIXES.stream().anyMatch(path::endsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Object expected = request.getSession(false) == null ? null
                : request.getSession(false).getAttribute(CsrfTokenSupport.SESSION_KEY);
        String actual = request.getHeader(CsrfTokenSupport.HEADER);
        if (!(expected instanceof String token) || actual == null ||
                !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"code\":40300,\"data\":null,\"message\":\"CSRF token invalid\",\"errorKey\":\"CSRF_INVALID\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
