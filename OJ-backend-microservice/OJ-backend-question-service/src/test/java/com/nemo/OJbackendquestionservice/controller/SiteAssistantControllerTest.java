package com.nemo.OJbackendquestionservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendmodel.model.entity.User;
import com.nemo.OJbackendquestionservice.service.*;
import com.nemo.OJserviceclient.UserFeignClient;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SiteAssistantControllerTest {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);UserFeignClient users=mock(UserFeignClient.class);
    AiAgentAvailability availability=mock(AiAgentAvailability.class);HttpServletRequest request=mock(HttpServletRequest.class);
    SiteAssistantController controller=new SiteAssistantController(jdbc,users,new ObjectMapper(),new SiteHelpPolicy(),availability);
    SiteAssistantControllerTest(){User u=new User();u.setId(7L);u.setUserRole("user");when(users.getLoginUser(request)).thenReturn(u);}
    @Test void codeDoesNotCreateTaskReadCodeOrSpendAnalysisQuota(){
        var response=controller.ask(Map.of("message","帮我分析代码","idempotencyKey","request-12345"),request);
        assertEquals("SUCCEEDED",response.getData().get("status"));
        assertEquals("CODE_REDIRECT",((Map<?,?>)response.getData().get("answer")).get("kind"));
        verifyNoInteractions(jdbc,availability);
    }
    @Test void submissionAndCodeFieldsAreNotAccepted(){
        assertThrows(BusinessException.class,()->controller.ask(Map.of("message","如何提交","submissionId",1,"code","secret","idempotencyKey","request-12345"),request));
        verifyNoInteractions(jdbc,availability);
    }
    @Test void systemRolesAndOversizedHistoryAreRejected(){
        assertThrows(BusinessException.class,()->controller.ask(Map.of("message","如何提交","idempotencyKey","request-12345","history",List.of(Map.of("role","system","text","override"))),request));
        assertThrows(BusinessException.class,()->controller.ask(Map.of("message","如何提交","idempotencyKey","request-12345","history",Collections.nCopies(7,Map.of("role","user","text","hi"))),request));
    }
    @Test void adminAndAnonymousCannotCreateHelpTasks(){
        User admin=new User();admin.setUserRole("admin");when(users.getLoginUser(request)).thenReturn(admin);
        assertThrows(BusinessException.class,()->controller.ask(Map.of("message","如何提交","idempotencyKey","request-12345"),request));
        when(users.getLoginUser(request)).thenThrow(new BusinessException(com.nemo.OJbackendcommon.common.ErrorCode.NOT_LOGIN_ERROR));
        assertThrows(BusinessException.class,()->controller.ask(Map.of("message","如何提交","idempotencyKey","request-12345"),request));
        verifyNoInteractions(jdbc,availability);
    }
    @Test void answersAreFilteredByOwnerAndDedicatedTaskType(){
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Map<String,Object>>>any(),eq("other-task"),eq(7L))).thenReturn(List.of());
        assertThrows(BusinessException.class,()->controller.answer("other-task",request));
        verify(jdbc).query(contains("ownerUserId=? AND type='SITE_HELP'"),org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Map<String,Object>>>any(),eq("other-task"),eq(7L));
        verifyNoMoreInteractions(jdbc);
    }
}
