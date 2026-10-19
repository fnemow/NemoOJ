package com.nemo.OJbackendgateway.filter;

import com.nemo.OJbackendcommon.internal.InternalAuthConstants;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 网关入口防线单元测试。
 * <p>
 * 覆盖 T04 的两条要求：
 * <ul>
 *   <li>外部请求不能经网关到达 {@code /inner/**}；</li>
 *   <li>外部请求伪造的内部凭据头必须被剥离，不能透传给下游。</li>
 * </ul>
 */
class GlobalAuthFilterTest {

    private final GlobalAuthFilter filter = new GlobalAuthFilter();

    /** 记录被放行时下游实际看到的请求 */
    private static final class CapturingChain implements GatewayFilterChain {
        final AtomicReference<ServerWebExchange> seen = new AtomicReference<>();

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            seen.set(exchange);
            return Mono.empty();
        }
    }

    @Test
    @DisplayName("经网关访问内部接口一律 403")
    void blocksInnerPaths() {
        for (String path : new String[]{
                "/api/user/inner/get/id",
                "/api/user/inner/get/ids",
                "/api/question/inner/question_submit/update",
                "/api/judge/inner/do"}) {

            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get(path).build());
            CapturingChain chain = new CapturingChain();

            filter.filter(exchange, chain).block();

            assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode(),
                    "内部路径应被拦截: " + path);
            assertEquals(null, chain.seen.get(), "被拦截的请求不应继续传递到下游: " + path);
        }
    }

    @Test
    @DisplayName("外部伪造的内部凭据头被剥离，不透传给下游")
    void stripsInternalHeader() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/user/get/login")
                        .header(InternalAuthConstants.HEADER, "forged-token")
                        .build());
        CapturingChain chain = new CapturingChain();

        filter.filter(exchange, chain).block();

        ServerWebExchange downstream = chain.seen.get();
        assertNotNull(downstream, "公共路径应放行");
        assertFalse(downstream.getRequest().getHeaders().containsKey(InternalAuthConstants.HEADER),
                "内部凭据头必须被剥离，否则外部可借此通过下游的内部接口校验");
    }

    @Test
    @DisplayName("公共路径正常放行且不影响其它请求头")
    void passesThroughPublicPaths() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/question/question/list")
                        .header("Cookie", "SESSION=abc")
                        .build());
        CapturingChain chain = new CapturingChain();

        filter.filter(exchange, chain).block();

        ServerWebExchange downstream = chain.seen.get();
        assertNotNull(downstream);
        assertEquals("SESSION=abc", downstream.getRequest().getHeaders().getFirst("Cookie"),
                "常规请求头不应被改动");
    }
}
