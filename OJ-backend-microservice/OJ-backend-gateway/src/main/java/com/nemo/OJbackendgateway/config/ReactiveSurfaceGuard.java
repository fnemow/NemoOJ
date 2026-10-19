package com.nemo.OJbackendgateway.config;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The gateway must never host a DispatcherServlet alongside WebFlux. */
@Configuration(proxyBeanMethods = false)
public class ReactiveSurfaceGuard {
    @Bean
    static BeanPostProcessor reactiveSurfacePolicy() {
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                for (Class<?> type = bean.getClass(); type != null; type = type.getSuperclass())
                    verifyTypeName(type.getName());
                return bean;
            }
        };
    }
    static void verifyTypeName(String name) {
        if ("org.springframework.web.servlet.DispatcherServlet".equals(name))
            throw new IllegalStateException("Gateway security policy requires a reactive-only web stack");
    }
}
