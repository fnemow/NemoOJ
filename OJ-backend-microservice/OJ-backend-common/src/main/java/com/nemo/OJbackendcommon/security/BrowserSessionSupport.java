package com.nemo.OJbackendcommon.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import static com.nemo.OJbackendcommon.constant.UserConstant.USER_LOGIN_STATE;

/** Shared by servlet services: reads never allocate a Redis session. */
public final class BrowserSessionSupport {
    public static final int ANONYMOUS_TIMEOUT_SECONDS = 15 * 60;
    public static final int AUTHENTICATED_TIMEOUT_SECONDS = 30 * 24 * 60 * 60;
    private BrowserSessionSupport() {}

    public static Object loginState(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return null;
        Object user = session.getAttribute(USER_LOGIN_STATE);
        // Adopt the short policy for legacy anonymous cookies on their next read.
        // Authenticated sessions retain their existing lifetime and ID.
        if (user == null) limitAnonymousLifetime(session);
        return user;
    }

    public static void authenticate(HttpServletRequest request, Object user) {
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute(USER_LOGIN_STATE, user);
        session.setMaxInactiveInterval(AUTHENTICATED_TIMEOUT_SECONDS);
    }

    public static void limitAnonymousLifetime(HttpSession session) {
        if (session.getAttribute(USER_LOGIN_STATE) == null) {
            session.setMaxInactiveInterval(ANONYMOUS_TIMEOUT_SECONDS);
        }
    }
}
