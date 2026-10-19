package com.nemo.OJbackendcommon.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CsrfTokenFilterTest {
    private static final String TASK_ID = "065f5f60-9dd8-48e2-829c-84a3d3d5f78c";

    @Test void expiredSessionIsRejectedBeforeBusinessExecutionWithoutAllocatingSession() throws Exception {
        var csrf = new CsrfTokenFilter();
        ReflectionTestUtils.setField(csrf, "enabled", true);
        var request = new MockHttpServletRequest("POST", "/api/user/login");
        request.addHeader(CsrfTokenSupport.HEADER, "expired-token");
        var response = new MockHttpServletResponse(); var chain = new MockFilterChain();
        csrf.doFilter(request, response, chain);
        assertEquals(403, response.getStatus());
        assertNull(chain.getRequest());
        assertNull(request.getSession(false));
    }

    @Test void validTokenStillAllowsMutation() throws Exception {
        var csrf = new CsrfTokenFilter(); ReflectionTestUtils.setField(csrf, "enabled", true);
        var request = new MockHttpServletRequest("POST", "/api/user/login");
        request.addHeader(CsrfTokenSupport.HEADER, CsrfTokenSupport.getOrCreate(request));
        var response = new MockHttpServletResponse(); var chain = new MockFilterChain();
        csrf.doFilter(request, response, chain);
        assertNotNull(chain.getRequest());
        assertEquals(200, response.getStatus());
    }

    @Test
    void signedAgentCallbacksDoNotRequireBrowserCsrfToken() throws Exception {
        for (String action : new String[]{"claim", "context", "complete", "fail", "heartbeat",
                "validations", "validations/" + TASK_ID,
                "calls/" + TASK_ID + "/start", "calls/" + TASK_ID + "/finish"}) {
            MockFilterChain chain = filter("/api/question/internal/ai/tasks/" + TASK_ID + "/" + action);
            assertNotNull(chain.getRequest(), action);
        }
    }

    @Test
    void unrelatedInternalPostsStillRequireCsrfToken() throws Exception {
        for (String path : new String[]{
                "/api/question/internal/ai/tasks/" + TASK_ID + "/delete",
                "/api/question/internal/ai/tasks/not-a-uuid/claim",
                "/api/question/internal/ai/tasks/" + TASK_ID + "/calls/not-a-uuid/start",
                "/api/question/internal/ai/tasks/" + TASK_ID + "/calls/" + TASK_ID + "/delete",
                "/api/question/internal/ai/tasks/" + TASK_ID + "/validations/not-a-uuid",
                "/api/question/internal/ai/tasks/" + TASK_ID + "/validations/" + TASK_ID + "/delete",
                "/api/question/internal/ai/tasks/" + TASK_ID + "/heartbeat/delete",
                "/api/question/admin/problems/offline"}) {
            CsrfTokenFilter csrf = new CsrfTokenFilter();
            ReflectionTestUtils.setField(csrf, "enabled", true);
            MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            csrf.doFilter(request, response, chain);
            assertEquals(403, response.getStatus(), path);
            assertNull(chain.getRequest(), path);
        }
    }

    private MockFilterChain filter(String path) throws Exception {
        CsrfTokenFilter csrf = new CsrfTokenFilter();
        ReflectionTestUtils.setField(csrf, "enabled", true);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        MockFilterChain chain = new MockFilterChain();
        csrf.doFilter(request, new MockHttpServletResponse(), chain);
        return chain;
    }
}
