package com.nemo.OJbackendcommon.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内部接口凭据校验单元测试。
 * <p>
 * 对应 T04 验收："直接访问业务服务内部接口时，无凭据/错误凭据均被拒绝，合法服务调用成功"。
 */
class InternalAuthFilterTest {

    private static final String SECRET = "test-internal-secret-0123456789";

    private MockHttpServletResponse run(String providedToken) throws Exception {
        InternalAuthFilter filter = new InternalAuthFilter(SECRET);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/inner/get/id");
        if (providedToken != null) {
            request.addHeader(InternalAuthConstants.HEADER, providedToken);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    @DisplayName("正确凭据放行")
    void allowsCorrectToken() throws Exception {
        MockHttpServletResponse response = run(SECRET);
        assertEquals(200, response.getStatus(), "携带正确凭据应放行");
    }

    @Test
    @DisplayName("缺少凭据返回 403")
    void rejectsMissingToken() throws Exception {
        MockHttpServletResponse response = run(null);
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("40300"),
                "应返回统一错误码，便于客户端识别");
    }

    @Test
    @DisplayName("错误凭据返回 403")
    void rejectsWrongToken() throws Exception {
        assertEquals(403, run("wrong-token").getStatus());
    }

    @Test
    @DisplayName("空字符串凭据返回 403")
    void rejectsEmptyToken() throws Exception {
        assertEquals(403, run("").getStatus());
    }

    @Test
    @DisplayName("服务端密钥为空时不放行任何请求（失败关闭）")
    void failsClosedWhenSecretMissing() throws Exception {
        InternalAuthFilter filter = new InternalAuthFilter(null);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/inner/get/id");
        request.addHeader(InternalAuthConstants.HEADER, "anything");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(403, response.getStatus(), "密钥未配置时必须拒绝，而不是放行");
    }

    @Test
    @DisplayName("凭据不区分大小写前缀之类的近似串不应被接受")
    void rejectsSimilarToken() throws Exception {
        assertEquals(403, run(SECRET + "x").getStatus());
        assertEquals(403, run(SECRET.substring(0, SECRET.length() - 1)).getStatus());
        assertFalse(SECRET.equalsIgnoreCase("x" + SECRET), "前置条件自检");
    }
}
