package com.nemo.OJbackendcommon.websecurity;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.method.support.AsyncHandlerMethodReturnValueHandler;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

/** Temporary REST-only policy for CVE-2026-47884/47890/47892; not a vendor patch. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter")
public class RestApiRenderingGuard {
    @Bean
    static BeanPostProcessor restApiRenderingPolicy() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                for (String blocked : new String[]{
                        "org.springframework.web.servlet.view.xslt.XsltView",
                        "org.springframework.web.servlet.view.xslt.XsltViewResolver",
                        "org.springframework.web.servlet.function.RouterFunction",
                        "org.springframework.web.reactive.function.server.RouterFunction",
                        "org.springframework.web.reactive.DispatcherHandler"}) {
                    if (isType(bean.getClass(), blocked))
                        throw new IllegalStateException("REST-only security policy rejects bean " + name);
                }
                if (isType(bean.getClass(), "org.springframework.web.servlet.view.UrlBasedViewResolver")) {
                    var getter = ReflectionUtils.findMethod(bean.getClass(), "getViewClass");
                    if (getter != null) {
                        ReflectionUtils.makeAccessible(getter);
                        Object viewClass = ReflectionUtils.invokeMethod(getter, bean);
                        if (viewClass instanceof Class<?> type && isType(type, "org.springframework.web.servlet.view.xslt.XsltView"))
                            throw new IllegalStateException("XSLT rendering is disabled");
                    }
                }
                if (bean instanceof RequestMappingHandlerAdapter adapter && adapter.getReturnValueHandlers() != null)
                    adapter.setReturnValueHandlers(adapter.getReturnValueHandlers().stream()
                            .map(GuardedHandler::new).map(h -> (HandlerMethodReturnValueHandler) h).toList());
                return bean;
            }
        };
    }

    static boolean isType(Class<?> type, String name) {
        if (type == null) return false;
        if (type.getName().equals(name)) return true;
        for (Class<?> parent : type.getInterfaces()) if (isType(parent, name)) return true;
        return isType(type.getSuperclass(), name);
    }

    private record GuardedHandler(HandlerMethodReturnValueHandler delegate) implements AsyncHandlerMethodReturnValueHandler {
        @Override public boolean isAsyncReturnValue(Object value, MethodParameter type) {
            return delegate instanceof AsyncHandlerMethodReturnValueHandler async && async.isAsyncReturnValue(value, type);
        }
        @Override public boolean supportsReturnType(MethodParameter type) { return delegate.supportsReturnType(type); }
        @Override public void handleReturnValue(Object value, MethodParameter type, ModelAndViewContainer container,
                                               NativeWebRequest request) throws Exception {
            if (value != null && (isType(value.getClass(), "org.springframework.web.servlet.View")
                    || isType(value.getClass(), "org.springframework.web.servlet.ModelAndView"))) reject();
            delegate.handleReturnValue(value, type, container, request);
            // Block implicit and named server-side views before DispatcherServlet renders them.
            if (!container.isRequestHandled() && !WebAsyncUtils.getAsyncManager(request).isConcurrentHandlingStarted()) reject();
        }
        private void reject() {
            throw new ResponseStatusException(HttpStatus.NOT_ACCEPTABLE, "Server-side view rendering is disabled");
        }
    }
}
