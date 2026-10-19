package com.nemo.OJbackendgateway.config;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ReactiveSurfaceGuardTest {
    @Test void rejectsServletAndAllowsReactiveTypes() {
        assertThrows(IllegalStateException.class, () -> ReactiveSurfaceGuard.verifyTypeName("org.springframework.web.servlet.DispatcherServlet"));
        assertDoesNotThrow(() -> ReactiveSurfaceGuard.verifyTypeName("org.springframework.web.reactive.DispatcherHandler"));
        Object bean = new Object();
        assertSame(bean, ReactiveSurfaceGuard.reactiveSurfacePolicy().postProcessAfterInitialization(bean, "ordinary"));
    }
}
