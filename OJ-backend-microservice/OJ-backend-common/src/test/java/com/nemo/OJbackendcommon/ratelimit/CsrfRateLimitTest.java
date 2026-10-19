package com.nemo.OJbackendcommon.ratelimit;

import com.nemo.OJbackendcommon.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CsrfRateLimitTest {
    @Test void usesDedicatedIpWindowAndRejectsWithoutCreatingSession() {
        var limiter = mock(RateLimiter.class); var guard = new RateLimitGuard(limiter);
        ReflectionTestUtils.setField(guard, "csrfIpLimit", 60);
        ReflectionTestUtils.setField(guard, "csrfWindowSeconds", 60);
        var request = new MockHttpServletRequest(); request.setRemoteAddr("192.0.2.1");
        request.addHeader("X-Forwarded-For", "192.0.2.99");
        when(limiter.tryAcquire("csrf:ip:192.0.2.1", 60, 60)).thenReturn(RateLimitDecision.allow());
        assertDoesNotThrow(() -> guard.checkCsrf(request));
        when(limiter.tryAcquire("csrf:ip:192.0.2.1", 60, 60)).thenReturn(RateLimitDecision.reject(15, "csrf", 61));
        assertThrows(BusinessException.class, () -> guard.checkCsrf(request));
        assertNull(request.getSession(false));
        verify(limiter, never()).tryAcquire(eq("csrf:ip:192.0.2.99"), anyInt(), anyInt());
    }
}
