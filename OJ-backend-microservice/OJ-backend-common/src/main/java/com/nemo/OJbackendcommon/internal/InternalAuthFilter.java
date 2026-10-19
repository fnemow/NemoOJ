package com.nemo.OJbackendcommon.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部接口（{@code /inner/**}）服务凭据校验。
 * <p>
 * 背景：网关虽然拦截了 {@code /**}{@code /inner/**}，但那只挡住了经网关的流量。
 * 业务服务端口在部署上仍可能被同网段的其它进程直接访问（调试端口、误配的端口映射、
 * 内网横向移动）。因此内部接口必须**自己**校验调用方身份，不能把网关当作唯一防线。
 * <p>
 * 约定：
 * <ul>
 *   <li>调用方在 {@link #HEADER} 头中携带与服务端一致的密钥；</li>
 *   <li>缺少或错误一律 403，不区分"没带"与"带错"，避免给出探测反馈；</li>
 *   <li>校验使用常量时间比较，避免通过响应时间逐字节猜测密钥。</li>
 * </ul>
 * <p>
 * 注意：本过滤器只证明"调用方是内部服务"，**不代表终端用户已登录**。
 * 公共接口仍必须自行做登录与角色校验，不能用服务凭据替代用户权限判断。
 */
@Slf4j
public class InternalAuthFilter extends OncePerRequestFilter {

    private final String secret;

    public InternalAuthFilter(String secret) {
        this.secret = secret;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!matches(request.getHeader(InternalAuthConstants.HEADER))) {
            log.warn("内部接口鉴权失败, uri={}, remoteAddr={}",
                    request.getRequestURI(), request.getRemoteAddr());
            reject(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean matches(String provided) {
        if (provided == null || secret == null) {
            return false;
        }
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                secret.getBytes(StandardCharsets.UTF_8));
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":40300,\"data\":null,\"message\":\"禁止访问\"}");
    }
}
