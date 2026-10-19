package com.nemo.OJbackendgateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 网关跨域配置。
 * <p>
 * 允许来源通过 {@code cors.allowed-origins} 注入（逗号分隔），生产环境必须显式配置。
 * 注意：CORS 白名单只约束浏览器跨域读取，**不是** CSRF 或权限防护；
 * 需要 Cookie 才能生效的写操作仍依赖 SameSite 与登录态校验（见 T04）。
 */
@Slf4j
@Configuration
public class CorsConfig {

    @Value("${cors.allowed-origins}")
    private String allowedOrigins;

    @Bean
    public CorsWebFilter corsWebFilter() {
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        if (origins.isEmpty()) {
            throw new IllegalStateException(
                    "cors.allowed-origins 不能为空，请配置允许的前端来源（逗号分隔）");
        }
        if (origins.contains("*")) {
            // 带凭据的请求不允许通配来源；这里直接拒绝启动，避免误配成开放跨域
            throw new IllegalStateException(
                    "cors.allowed-origins 不允许使用通配符 '*'（已启用 allowCredentials）");
        }

        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedMethod("*");
        config.addAllowedHeader("*");
        config.setAllowCredentials(true);
        // setAllowedOriginPatterns 支持端口/子域匹配，比 setAllowedOrigins 更灵活且不会误开全站
        config.setAllowedOriginPatterns(origins);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        log.info("CORS 允许来源: {}", origins);
        return new CorsWebFilter(source);
    }
}
