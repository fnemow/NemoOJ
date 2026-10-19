package com.nemo.OJbackendcommon.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 注册 {@link InternalAuthFilter}，只作用于 {@code /inner/*}。
 * <p>
 * 仅对 Servlet 应用生效（网关是 WebFlux，不会加载本配置）。
 * <p>
 * {@code internal.auth.secret} 不设代码内默认值：缺失时应用启动即失败，
 * 避免内部接口在无凭据状态下对外开放。各服务的 dev 配置提供本机默认值，
 * prod 配置只接受环境变量。
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class InternalAuthConfig {

    @Bean
    public FilterRegistrationBean<InternalAuthFilter> internalAuthFilter(
            @Value("${internal.auth.secret}") String internalAuthSecret) {
        FilterRegistrationBean<InternalAuthFilter> registration =
                new FilterRegistrationBean<>(new InternalAuthFilter(internalAuthSecret));
        // 只拦截内部接口，避免给公共接口增加无意义的开销
        registration.addUrlPatterns(InternalAuthConstants.INNER_PATH_PATTERN);
        registration.setName("internalAuthFilter");
        registration.setOrder(1);
        return registration;
    }
}
