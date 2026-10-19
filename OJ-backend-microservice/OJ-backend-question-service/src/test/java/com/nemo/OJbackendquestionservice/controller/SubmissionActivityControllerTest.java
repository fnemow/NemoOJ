package com.nemo.OJbackendquestionservice.controller;

import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendmodel.model.entity.User;
import com.nemo.OJbackendquestionservice.service.SubmissionActivityService;
import com.nemo.OJserviceclient.UserFeignClient;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubmissionActivityControllerTest {
    @Test void anonymousBannedAndAdminAccountsCannotReadActivity() {
        var users=mock(UserFeignClient.class);var activity=mock(SubmissionActivityService.class);
        var controller=new SubmissionActivityController(users,activity);
        var request=mock(HttpServletRequest.class);var response=mock(HttpServletResponse.class);
        assertThrows(BusinessException.class,()->controller.activity("2026-10",request,response));
        for(String role:new String[]{"ban","admin"}) {
            var user=new User();user.setId(5L);user.setUserRole(role);when(users.getLoginUser(request)).thenReturn(user);
            assertThrows(BusinessException.class,()->controller.activity("2026-10",request,response));
        }
        verifyNoInteractions(activity);
    }
    @Test void alwaysUsesAuthenticatedOwnerAndPreventsSharedResponseCaching() {
        var users=mock(UserFeignClient.class);var activity=mock(SubmissionActivityService.class);
        var controller=new SubmissionActivityController(users,activity);
        var request=mock(HttpServletRequest.class);var response=mock(HttpServletResponse.class);
        var user=new User();user.setId(42L);user.setUserRole("user");when(users.getLoginUser(request)).thenReturn(user);
        // A forged query parameter is deliberately never consulted.
        when(request.getParameter("userId")).thenReturn("99");
        controller.activity("2026-10",request,response);
        verify(activity).month(42L,"2026-10");verifyNoMoreInteractions(activity);
        verify(response).setHeader("Cache-Control","private, no-store");
        verify(request,never()).getParameter("userId");
    }
}
