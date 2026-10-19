package com.nemo.OJbackenduserservice.controller;

import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendmodel.model.dto.user.UserUpdateRequest;
import com.nemo.OJbackendmodel.model.entity.User;
import com.nemo.OJbackenduserservice.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class UserControllerRolePolicyTest {
    private UserController controller;
    private UserService userService;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        controller = new UserController();
        userService = mock(UserService.class);
        request = mock(HttpServletRequest.class);
        ReflectionTestUtils.setField(controller, "userService", userService);
    }

    @Test
    void disabledRegistrationStopsBeforeAccountOrInvitationProcessing() {
        ReflectionTestUtils.setField(controller, "registrationEnabled", false);
        BusinessException failure=assertThrows(BusinessException.class, () -> controller.userRegister(null, request));
        assertEquals(40300,failure.getCode());
        verifyNoInteractions(userService,request);
    }

    @Test
    void rejectsPromotionToAdministrator() {
        when(userService.getById(10L)).thenReturn(user(10L, "user"));
        UserUpdateRequest body = request(10L, "admin");

        assertThrows(BusinessException.class, () -> controller.updateUser(body, request));
        verify(userService, never()).updateById(any());
    }

    @Test
    void rejectsChangesToAdministratorAccounts() {
        when(userService.getById(1L)).thenReturn(user(1L, "admin"));

        assertThrows(BusinessException.class, () -> controller.updateUser(request(1L, "ban"), request));
        verify(userService, never()).updateById(any());
    }

    @Test
    void allowsOnlyUserToBanTransition() {
        when(userService.getById(10L)).thenReturn(user(10L, "user"));
        when(userService.updateById(any())).thenReturn(true);

        assertTrue(controller.updateUser(request(10L, "ban"), request).getData());
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userService).updateById(saved.capture());
        assertEquals(10L, saved.getValue().getId());
        assertEquals("ban", saved.getValue().getUserRole());
    }

    @Test
    void allowsOnlyBanToUserTransition() {
        when(userService.getById(10L)).thenReturn(user(10L, "ban"));
        when(userService.updateById(any())).thenReturn(true);

        assertTrue(controller.updateUser(request(10L, "user"), request).getData());
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userService).updateById(saved.capture());
        assertEquals("user", saved.getValue().getUserRole());
    }

    private static UserUpdateRequest request(long id, String role) {
        UserUpdateRequest request = new UserUpdateRequest();
        request.setId(id);
        request.setUserRole(role);
        return request;
    }

    private static User user(long id, String role) {
        User user = new User();
        user.setId(id);
        user.setUserRole(role);
        return user;
    }
}
