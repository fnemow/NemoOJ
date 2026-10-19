package com.nemo.OJbackenduserservice.service;

import com.nemo.OJbackenduserservice.service.impl.UserServiceImpl;
import com.nemo.OJbackenduserservice.mapper.UserMapper;
import com.nemo.OJbackendmodel.model.entity.User;
import com.nemo.OJbackendcommon.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import static com.nemo.OJbackendcommon.constant.UserConstant.USER_LOGIN_STATE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserSessionLifecycleTest {
    @Test void readingLegacyAnonymousSessionAdoptsShortLifetimeWithoutChangingId() {
        var users = new UserServiceImpl(); var request = new MockHttpServletRequest();
        var session = request.getSession(); session.setMaxInactiveInterval(2592000);
        String id = session.getId();
        assertNull(users.getLoginUserPermitNull(request));
        assertEquals(900, session.getMaxInactiveInterval());
        assertEquals(id, request.getSession(false).getId());
    }
    @Test void anonymousReadsAndLogoutDoNotCreateSessions() {
        var users = new UserServiceImpl();
        var request = new MockHttpServletRequest();
        assertThrows(BusinessException.class, () -> users.getLoginUser(request));
        assertNull(users.getLoginUserPermitNull(request));
        assertFalse(users.isAdmin(request));
        assertThrows(BusinessException.class, () -> users.userLogout(request));
        assertNull(request.getSession(false));
    }
    @Test void successfulLoginRotatesIdAndExtendsLifetime() {
        var users = new UserServiceImpl();
        var mapper = mock(UserMapper.class);
        ReflectionTestUtils.setField(users, "baseMapper", mapper);
        var user = new User(); user.setId(1L); user.setUserAccount("test-user");
        user.setUserPassword(new BCryptPasswordEncoder().encode("test-password"));
        when(mapper.selectOne(any())).thenReturn(user);
        var request = new MockHttpServletRequest();
        request.getSession().setMaxInactiveInterval(900);
        String oldId = request.getSession().getId();
        assertEquals(1L, users.userLogin("test-user", "test-password", request).getId());
        assertNotEquals(oldId, request.getSession(false).getId());
        assertEquals(2592000, request.getSession(false).getMaxInactiveInterval());
        assertSame(user, request.getSession(false).getAttribute(USER_LOGIN_STATE));
    }
    @Test void failedLoginDoesNotPromoteAnonymousLifetime() {
        var users = new UserServiceImpl(); var mapper = mock(UserMapper.class);
        ReflectionTestUtils.setField(users, "baseMapper", mapper);
        var request = new MockHttpServletRequest();
        request.getSession().setMaxInactiveInterval(900);
        assertThrows(BusinessException.class, () -> users.userLogin("unknown-user", "test-password", request));
        assertEquals(900, request.getSession(false).getMaxInactiveInterval());
        assertNull(request.getSession(false).getAttribute(USER_LOGIN_STATE));
    }
    @Test void logoutInvalidatesEntireSession() {
        var users = new UserServiceImpl(); var request = new MockHttpServletRequest();
        var session = (org.springframework.mock.web.MockHttpSession) request.getSession();
        session.setAttribute(USER_LOGIN_STATE, new User());
        assertTrue(users.userLogout(request));
        assertTrue(session.isInvalid());
        assertNull(request.getSession(false));
    }
}
