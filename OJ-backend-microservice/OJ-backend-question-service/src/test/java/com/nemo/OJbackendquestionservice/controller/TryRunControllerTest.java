package com.nemo.OJbackendquestionservice.controller;

import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendmodel.model.dto.question.*;
import com.nemo.OJbackendmodel.model.entity.Question;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TryRunControllerTest {
    @Test void anonymousBannedAndAdminAccountsNeverReachTheSandbox() {
        var users=mock(com.nemo.OJserviceclient.UserFeignClient.class);var judge=mock(com.nemo.OJserviceclient.JudgeFeignClient.class);
        var lifecycle=mock(com.nemo.OJbackendquestionservice.service.ProblemLifecycleService.class);
        var controller=new TryRunController(users,judge,lifecycle,null,null,null,null);
        var http=mock(jakarta.servlet.http.HttpServletRequest.class);
        assertThrows(BusinessException.class,()->controller.run(new TryRunRequest(),http));
        for(String role:List.of("ban","admin")) {
            var user=new com.nemo.OJbackendmodel.model.entity.User();user.setId(1L);user.setUserRole(role);when(users.getLoginUser(http)).thenReturn(user);
            assertThrows(BusinessException.class,()->controller.run(new TryRunRequest(),http));
        }
        verifyNoInteractions(judge,lifecycle);
    }
    @Test void samplesExcludeHiddenAndMissingFlags() {
        var question=new Question();var hidden=new JudgeCase();hidden.setInput("secret");hidden.setIsHidden(true);
        var unknown=new JudgeCase();unknown.setInput("also-secret");
        var sample=new JudgeCase();sample.setInput("public");sample.setIsHidden(false);
        question.setJudgeCase(List.of(hidden,unknown,sample));
        var request=new TryRunRequest();request.setExecutionMode("ACM");request.setSampleIndex(0);
        assertEquals("public",TryRunController.selectInput(question,request));
        request.setSampleIndex(1);assertThrows(BusinessException.class,()->TryRunController.selectInput(question,request));
    }
    @Test void inputIsByteBoundedAndCoreRequiresThePublicSignature() {
        var question=new Question();var request=new TryRunRequest();request.setExecutionMode("ACM");request.setInput("中".repeat(22000));
        assertThrows(BusinessException.class,()->TryRunController.selectInput(question,request));
        request.setExecutionMode("FUNCTION");request.setInput("{}");
        assertThrows(BusinessException.class,()->TryRunController.selectInput(question,request));
        var spec=new ProblemSpec();var parameter=new ParameterSpec();parameter.setName("value");parameter.setType("int");spec.setParameters(List.of(parameter));question.setProblemSpec(spec);
        assertThrows(BusinessException.class,()->TryRunController.selectInput(question,request));
        request.setInput("{\"value\":1}");assertEquals(request.getInput(),TryRunController.selectInput(question,request));
    }
}
