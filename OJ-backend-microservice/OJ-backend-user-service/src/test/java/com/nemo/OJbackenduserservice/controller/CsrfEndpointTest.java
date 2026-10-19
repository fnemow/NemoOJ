package com.nemo.OJbackenduserservice.controller;

import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendcommon.common.ErrorCode;
import com.nemo.OJbackendcommon.ratelimit.RateLimitGuard;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CsrfEndpointTest {
    @Test void rateLimitRejectsBeforeSessionAllocation() {
        var guard = mock(RateLimitGuard.class); var controller = new UserController();
        ReflectionTestUtils.setField(controller, "rateLimitGuard", guard);
        var request = new MockHttpServletRequest();
        doThrow(new BusinessException(ErrorCode.OPERATION_ERROR)).when(guard).checkCsrf(request);
        assertThrows(BusinessException.class, () -> controller.csrf(request, new MockHttpServletResponse()));
        assertNull(request.getSession(false));
    }
    @Test void tokenResponseCannotBeCachedAndReusesSession() {
        var controller = new UserController();
        ReflectionTestUtils.setField(controller, "rateLimitGuard", mock(RateLimitGuard.class));
        var request = new MockHttpServletRequest(); var response = new MockHttpServletResponse();
        String token = controller.csrf(request, response).getData().get("token");
        var session = request.getSession(false);
        assertEquals(token, controller.csrf(request, response).getData().get("token"));
        assertSame(session, request.getSession(false));
        assertEquals(900, session.getMaxInactiveInterval());
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }
}
