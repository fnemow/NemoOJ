package com.nemo.OJbackendgateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 写操作的来源校验（CSRF 防护的一层）。
 * <p>
 * 为什么需要它：本项目用 Cookie + Session 认证，浏览器会自动携带 Cookie，
 * 因此存在 CSRF 风险。<b>CORS 白名单不构成 CSRF 防护</b>——
 * CORS 只限制跨域<b>读取</b>响应，攻击者并不需要读到响应，只要请求发出去就够了。
 * <p>
 * 本过滤器对"会改变状态"的方法（POST/PUT/PATCH/DELETE）校验 {@code Origin}：
 * 浏览器发出的跨站表单提交与 fetch 一定会带 {@code Origin}，若它不在允许来源内则拒绝。
 * <p>
 * 三点说明：
 * <ul>
 *   <li>非浏览器客户端（curl、服务间调用）通常不带 {@code Origin}。
 *       CSRF 的前提是"浏览器自动附带凭据"，没有 Origin 的请求不满足该前提，故放行，
 *       这类请求仍受登录态与内部凭据校验约束。</li>
 *   <li>这是纵深防御的一层，不是唯一防线。Cookie 已设置 {@code SameSite=Lax}
 *       （见各服务 application-prod.yml），可阻断跨站表单提交。</li>
 *   <li>GET 不校验：按 HTTP 语义 GET 不应改变状态。若将来出现用 GET 改数据的接口，
 *       那是接口设计问题，应改接口而不是放宽这里。</li>
 * </ul>
 */
@Slf4j
@Component
public class CsrfOriginFilter implements GlobalFilter, Ordered {

    /** 会改变状态的方法 */
    private static final Set<HttpMethod> UNSAFE_METHODS = Set.of(
            HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);

    private final List<String> allowedOrigins;

    public CsrfOriginFilter(@Value("${cors.allowed-origins}") String allowedOrigins) {
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (!UNSAFE_METHODS.contains(request.getMethod())) {
            return chain.filter(exchange);
        }

        String origin = request.getHeaders().getOrigin();
        if (origin == null || origin.isEmpty()) {
            // 非浏览器客户端；CSRF 前提不成立
            return chain.filter(exchange);
        }

        if (isAllowed(origin)) {
            return chain.filter(exchange);
        }

        log.warn("写操作来源被拒绝, origin={}, path={}", origin, request.getURI().getPath());
        return reject(exchange);
    }

    private boolean isAllowed(String origin) {
        String normalized = normalize(origin);
        for (String allowed : allowedOrigins) {
            if (normalize(allowed).equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    /** 统一成 scheme://host[:port]，忽略末尾斜杠与大小写差异 */
    private String normalize(String origin) {
        try {
            URI uri = URI.create(origin.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            int port = uri.getPort();
            return port > 0 ? scheme + "://" + host + ":" + port : scheme + "://" + host;
        } catch (IllegalArgumentException e) {
            return origin.trim().toLowerCase();
        }
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().add(HttpHeaders.CONTENT_TYPE, "application/json;charset=UTF-8");
        DataBuffer buffer = response.bufferFactory()
                .wrap("{\"code\":40300,\"data\":null,\"message\":\"禁止访问\"}"
                        .getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    /** 在凭据剥离之后执行 */
    @Override
    public int getOrder() {
        return 1;
    }
}
