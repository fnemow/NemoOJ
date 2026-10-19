package com.nemo.OJbackendgateway.filter;

import com.nemo.OJbackendcommon.internal.InternalAuthConstants;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 网关全局拦截。
 * <p>
 * 两层作用：
 * <ol>
 *   <li><b>阻断内部路径</b>：外部请求不能经网关访问 {@code /inner/**}。</li>
 *   <li><b>剥离内部凭据头</b>：无论请求发往哪个路径，都先删掉
 *       {@code X-Internal-Token}。否则外部只要猜到该头的存在，就能把它透传给下游服务，
 *       而下游的内部接口正是凭这个头放行的——这属于"伪造透传头获得权限"。</li>
 * </ol>
 * <p>
 * 注意：网关这里是入口防线，不是唯一防线。业务服务的内部接口仍会独立校验凭据
 * （见 InternalAuthFilter），因为服务端口可能被绕过网关直接访问。
 */
@Component
public class GlobalAuthFilter implements GlobalFilter, Ordered {

    private final AntPathMatcher antPathMatcher = new AntPathMatcher();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest serverHttpRequest = exchange.getRequest();
        String path = serverHttpRequest.getURI().getPath();

        // 判断路径中是否包含 inner，只允许内部调用
        if (antPathMatcher.match("/**/inner/**", path)) {
            return reject(exchange);
        }

        // 剥离外部请求携带的内部凭据头（存在与否都删，避免被下游误信）
        ServerHttpRequest sanitized = serverHttpRequest.mutate()
                .headers(headers -> headers.remove(InternalAuthConstants.HEADER))
                .build();

        return chain.filter(exchange.mutate().request(sanitized).build());
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        DataBufferFactory dataBufferFactory = response.bufferFactory();
        DataBuffer dataBuffer = dataBufferFactory.wrap("无权限".getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(dataBuffer));
    }

    /**
     * 优先级最高
     * @return
     */
    @Override
    public int getOrder() {
        return 0;
    }
}