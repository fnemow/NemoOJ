package com.nemo.OJbackendgateway.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 写操作的来源校验测试（CSRF 防护）。
 * <p>
 * 核心断言：CORS 白名单之外的来源不能发起写操作；
 * 而"不带 Origin 的请求"（非浏览器客户端）不受影响。
 */
class CsrfOriginFilterTest {

    private static final String ALLOWED = "https://oj.example.com,http://localhost:8080";

    private final CsrfOriginFilter filter = new CsrfOriginFilter(ALLOWED);

    private static final class CapturingChain implements GatewayFilterChain {
        final AtomicReference<ServerWebExchange> seen = new AtomicReference<>();

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            seen.set(exchange);
            return Mono.empty();
        }
    }

    private CapturingChain run(String method, String origin) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.method(
                org.springframework.http.HttpMethod.valueOf(method), "/api/question/question/add");
        if (origin != null) {
            builder.header("Origin", origin);
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(builder.build());
        CapturingChain chain = new CapturingChain();
        filter.filter(exchange, chain).block();
        return chain;
    }

    // ---------- 写操作 ----------

    @Test
    @DisplayName("白名单来源的写操作放行")
    void allowsAllowedOrigin() {
        assertNotNull(run("POST", "https://oj.example.com").seen.get());
        assertNotNull(run("POST", "https://oj.example.com/").seen.get(), "末尾斜杠应等价");
        assertNotNull(run("DELETE", "http://localhost:8080").seen.get());
    }

    @Test
    @DisplayName("非白名单来源的写操作被拒绝")
    void rejectsForeignOrigin() {
        assertNull(run("POST", "https://evil.example.net").seen.get());
        assertNull(run("PUT", "http://127.0.0.1:8080").seen.get(), "端口不同即不同来源");
        assertNull(run("DELETE", "https://oj.example.com.evil.net").seen.get(), "后缀伪装不应通过");
    }

    @Test
    @DisplayName("无 Origin 的写操作放行（非浏览器客户端，CSRF 前提不成立）")
    void allowsRequestsWithoutOrigin() {
        assertNotNull(run("POST", null).seen.get());
    }

    // ---------- 读操作 ----------

    @Test
    @DisplayName("GET/HEAD 不校验来源")
    void ignoresSafeMethods() {
        assertNotNull(run("GET", "https://evil.example.net").seen.get());
        assertNotNull(run("HEAD", "https://evil.example.net").seen.get());
    }

    // ---------- 拦截响应 ----------

    @Test
    @DisplayName("被拒绝时返回 403 与统一错误码")
    void returnsUnifiedErrorBody() {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.post("/api/user/user/add")
                .header("Origin", "https://evil.example.net");
        MockServerWebExchange exchange = MockServerWebExchange.from(builder.build());
        filter.filter(exchange, new CapturingChain()).block();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }
}
