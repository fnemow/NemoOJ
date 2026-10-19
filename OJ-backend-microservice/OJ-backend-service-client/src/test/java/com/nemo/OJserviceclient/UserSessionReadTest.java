package com.nemo.OJserviceclient;

import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendmodel.model.entity.User;
import org.junit.jupiter.api.Test;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import static com.nemo.OJbackendcommon.constant.UserConstant.USER_LOGIN_STATE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserSessionReadTest {
    @Test void anonymousQuestionReadsNeverCreateSession() {
        var client = mock(UserFeignClient.class, CALLS_REAL_METHODS);
        var request = mock(HttpServletRequest.class);
        assertThrows(BusinessException.class, () -> client.getLoginUser(request));
        assertNull(client.getLoginUserPermitNull(request));
        verify(request, never()).getSession();
        verify(request, never()).getSession(true);
    }
    @Test void existingAuthenticatedSessionIsReadAcrossServices() {
        var client = mock(UserFeignClient.class, CALLS_REAL_METHODS);
        var request = mock(HttpServletRequest.class); var session = mock(HttpSession.class);
        var user = new User(); user.setId(2L);
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute(USER_LOGIN_STATE)).thenReturn(user);
        assertSame(user, client.getLoginUser(request));
        assertSame(user, client.getLoginUserPermitNull(request));
        verify(session, never()).setMaxInactiveInterval(anyInt());
    }
}
