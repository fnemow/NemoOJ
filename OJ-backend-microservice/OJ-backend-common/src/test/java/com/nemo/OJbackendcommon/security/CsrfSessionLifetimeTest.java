package com.nemo.OJbackendcommon.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static com.nemo.OJbackendcommon.constant.UserConstant.USER_LOGIN_STATE;
import static org.junit.jupiter.api.Assertions.*;

class CsrfSessionLifetimeTest {
    @Test void anonymousTokenHasShortLifetimeAndIsReused() {
        var request = new MockHttpServletRequest();
        String token = CsrfTokenSupport.getOrCreate(request);
        assertEquals(900, request.getSession(false).getMaxInactiveInterval());
        assertEquals(token, CsrfTokenSupport.getOrCreate(request));
    }
    @Test void rotationPreservesAuthenticatedLifetime() {
        var request = new MockHttpServletRequest();
        var session = request.getSession();
        session.setAttribute(USER_LOGIN_STATE, new Object());
        session.setMaxInactiveInterval(2592000);
        String before = CsrfTokenSupport.getOrCreate(request);
        CsrfTokenSupport.rotate(request);
        assertNotEquals(before, CsrfTokenSupport.getOrCreate(request));
        assertEquals(2592000, session.getMaxInactiveInterval());
    }
}
