package com.nemo.OJbackenduserservice.controller;

import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendmodel.model.entity.User;
import com.nemo.OJbackenduserservice.service.RegistrationInvitationService;
import com.nemo.OJbackenduserservice.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class RegistrationInvitationControllerTest {
    @Test void ordinaryUserCannotGenerateListOrRevokeInvitations() {
        var invitations = mock(RegistrationInvitationService.class);
        var users = mock(UserService.class);
        var request = mock(HttpServletRequest.class);
        var user = new User(); user.setId(3L); user.setUserRole("user");
        when(users.getLoginUser(request)).thenReturn(user);
        var controller = new RegistrationInvitationController(invitations, users);
        assertThrows(BusinessException.class, () -> controller.list(1, 20, request));
        assertThrows(BusinessException.class, () -> controller.create(new RegistrationInvitationController.CreateRequest(24, ""), request));
        assertThrows(BusinessException.class, () -> controller.revoke(1L, request));
        verifyNoInteractions(invitations);
    }
}
