package com.nemo.OJserviceclient;

import com.nemo.OJbackendcommon.internal.InternalAuthConstants;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 为所有 Feign 调用自动附加内部服务凭据。
 * <p>
 * 放在 service-client 而不是各服务里，是为了保证"调用方"与"服务端"使用同一套约定：
 * 服务端由 {@code InternalAuthFilter} 校验 {@link InternalAuthConstants#HEADER}，
 * 调用方由本拦截器统一附加，无需业务代码逐处处理。
 * <p>
 * 密钥不设默认值：未配置时应用启动失败，避免出现"服务端要凭据、调用方没带"的静默 403。
 */
@Component
public class InternalAuthFeignInterceptor implements RequestInterceptor {

    private final String secret;

    public InternalAuthFeignInterceptor(@Value("${internal.auth.secret}") String secret) {
        this.secret = secret;
    }

    @Override
    public void apply(RequestTemplate template) {
        template.header(InternalAuthConstants.HEADER, secret);
    }
}
