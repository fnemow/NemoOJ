package com.nemo.OJbackendcommon.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求链路标识。
 * <p>
 * 把 requestId 放进 MDC，日志模板即可自动带上，无需在每个类里手工传递。
 * 跨服务调用时该值经请求头透传，因此一次用户请求在网关、用户服务、题目服务、
 * 判题服务中的日志可以串成一条线。
 * <p>
 * 对外部传入的 requestId 做长度与字符校验后才采信：日志模板会直接输出该值，
 * 不加限制等于允许外部往日志里注入任意内容（含换行，可伪造日志行）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";

    public static final String MDC_KEY = "requestId";

    /** 合理的 requestId 长度上限 */
    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String requestId = sanitize(incoming);
        if (requestId == null) {
            requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 必须清理：线程池复用线程，残留的 MDC 会污染后续请求的日志
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * 只接受长度受限的字母数字与连字符。其余一律丢弃并生成新的。
     * 这既防止日志注入（换行、控制字符），也防止超长值撑爆日志。
     */
    static String sanitize(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_LENGTH) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) {
                return null;
            }
        }
        return value;
    }
}
