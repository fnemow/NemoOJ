package com.nemo.OJbackendcommon.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 请求体大小上限。
 * <p>
 * 为什么需要单独做：{@code spring.servlet.multipart.max-file-size} 只约束
 * multipart 表单，**对 JSON 请求体无效**。提交接口收的是 JSON，
 * 没有这道限制就等于允许任意大的请求体把内存和带宽吃满。
 * <p>
 * 只拦截本系统用于写操作的路径（提交、新增、编辑），不对全部请求生效，
 * 避免影响正常的读接口。
 */
@Slf4j
@Component
public class RequestBodySizeFilter extends OncePerRequestFilter {

    /** 提交代码等写操作路径片段 */
    private static final String[] GUARDED_PATHS = {
            "/question_submit/do", "/question/add", "/question/edit", "/question/update",
            "/user/add", "/user/update", "/register", "/login"
    };

    @Value("${ratelimit.max-request-body-bytes:1048576}")
    private long maxRequestBodyBytes;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!isGuarded(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        long declared = request.getContentLengthLong();
        if (declared > maxRequestBodyBytes) {
            log.warn("请求体超限: {} bytes > {} bytes, uri={}",
                    declared, maxRequestBodyBytes, request.getRequestURI());
            response.setStatus(413); // Payload Too Large
            response.setContentType("application/json;charset=UTF-8");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(
                    "{\"code\":40000,\"data\":null,\"message\":\"请求体过大\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isGuarded(String uri) {
        if (uri == null) {
            return false;
        }
        for (String path : GUARDED_PATHS) {
            if (uri.endsWith(path) || uri.contains(path + "?")) {
                return true;
            }
        }
        return false;
    }
}
