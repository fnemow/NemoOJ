package com.nemo.OJbackendcommon.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.security.SecureRandom;
import java.util.Base64;

public final class CsrfTokenSupport {
    public static final String SESSION_KEY = "NEMO_CSRF_TOKEN";
    public static final String HEADER = "X-CSRF-Token";
    private static final SecureRandom RANDOM = new SecureRandom();

    private CsrfTokenSupport() {}

    public static String getOrCreate(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        BrowserSessionSupport.limitAnonymousLifetime(session);
        Object existing = session.getAttribute(SESSION_KEY);
        if (existing instanceof String value && !value.isBlank()) return value;
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        session.setAttribute(SESSION_KEY, token);
        return token;
    }

    public static void rotate(HttpServletRequest request) {
        request.getSession(true).removeAttribute(SESSION_KEY);
        getOrCreate(request);
    }
}
