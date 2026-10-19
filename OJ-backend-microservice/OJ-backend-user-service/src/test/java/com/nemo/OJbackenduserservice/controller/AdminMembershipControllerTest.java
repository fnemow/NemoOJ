package com.nemo.OJbackenduserservice.controller;

import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendmodel.model.entity.User;
import com.nemo.OJbackenduserservice.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AdminMembershipControllerTest {
    AdminMembershipController controller;
    UserService users;
    JdbcTemplate jdbc;
    HttpServletRequest request;
    @BeforeEach void setup() {
        controller = new AdminMembershipController(); users = mock(UserService.class); jdbc = mock(JdbcTemplate.class); request = mock(HttpServletRequest.class);
        ReflectionTestUtils.setField(controller, "userService", users); ReflectionTestUtils.setField(controller, "jdbc", jdbc);
        User admin = new User(); admin.setId(1L); admin.setUserRole("admin"); when(users.getLoginUser(request)).thenReturn(admin);
        when(jdbc.queryForList("SELECT userRole FROM user WHERE id=? AND isDelete=0 FOR UPDATE", String.class, 2L)).thenReturn(List.of("user"));
        when(jdbc.queryForList("SELECT rowVersion FROM user_membership WHERE userId=? FOR UPDATE", Long.class, 2L)).thenReturn(List.of(3L));
    }
    AdminMembershipController.Update grant(long version) { return new AdminMembershipController.Update("VIP", "2030-01-01T12:00:00+08:00", version, "测试开通"); }
    @Test void ordinaryUsersCannotReadOrChangeMemberships() {
        User user = new User(); user.setId(2L); user.setUserRole("user"); when(users.getLoginUser(request)).thenReturn(user);
        assertThrows(BusinessException.class, () -> controller.update(2L, grant(3L), request));
        assertThrows(BusinessException.class, () -> controller.list(List.of(2L), request));
        verifyNoInteractions(jdbc);
    }
    @Test void rejectsPastOrUnzonedExpiry() {
        for (String date : List.of("2020-01-01T00:00:00Z", "2030-01-01T00:00:00", "invalid")) {
            assertThrows(BusinessException.class, () -> controller.update(2L, new AdminMembershipController.Update("VIP", date, 3L, "测试"), request));
        }
        verifyNoInteractions(jdbc);
    }
    @Test void rejectsConcurrentEditsBeforeWriting() {
        assertThrows(BusinessException.class, () -> controller.update(2L, grant(2L), request));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
    @Test void refusesAdminAndBannedTargets() {
        for (String role : List.of("admin", "ban")) {
            when(jdbc.queryForList("SELECT userRole FROM user WHERE id=? AND isDelete=0 FOR UPDATE", String.class, 2L)).thenReturn(List.of(role));
            assertThrows(BusinessException.class, () -> controller.update(2L, grant(3L), request));
        }
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
    @Test void grantsAndRevokesWithoutChangingRoles() {
        assertTrue(controller.update(2L, grant(3L), request).getData());
        assertTrue(controller.update(2L, new AdminMembershipController.Update("FREE", null, 3L, "取消测试"), request).getData());
        verify(users, never()).updateById(any(User.class));
        verify(jdbc, times(2)).update(startsWith("UPDATE user_membership"), any(Object[].class));
        verify(jdbc, times(2)).update(startsWith("INSERT INTO ai_action_audit"), any(Object[].class));
    }
}
