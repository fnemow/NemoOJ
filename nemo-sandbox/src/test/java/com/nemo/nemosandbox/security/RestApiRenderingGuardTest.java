package com.nemo.nemosandbox.security;

import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.view.FragmentsRendering;
import org.springframework.web.servlet.view.UrlBasedViewResolver;
import org.springframework.web.servlet.view.xslt.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RestApiRenderingGuardTest {
    AnnotationConfigWebApplicationContext context;
    MockMvc mvc;
    @BeforeEach void open() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(WebConfig.class, RestApiRenderingGuard.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }
    @AfterEach void close() { context.close(); }

    @Test void jsonStillWorks() throws Exception {
        mvc.perform(get("/json")).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
    }
    @Test void blocksViewsAndFragments() throws Exception {
        mvc.perform(get("/view")).andExpect(status().isNotAcceptable());
        mvc.perform(get("/fragments")).andExpect(status().isNotAcceptable());
        mvc.perform(get("/implicit")).andExpect(status().isNotAcceptable());
        mvc.perform(get("/named")).andExpect(status().isNotAcceptable());
    }
    @Test void sseJsonCannotInjectAnotherEvent() throws Exception {
        var pending = mvc.perform(get("/events")).andExpect(request().asyncStarted()).andReturn();
        String body = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("event:progress"));
        assertTrue(body.contains("\\nevent: injected"));
        assertFalse(body.contains("\nevent: injected"));
    }
    @Test void asyncJsonStillWorks() throws Exception {
        var pending = mvc.perform(get("/async")).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
    }
    @Test void asyncFragmentsCannotBypassGuard() throws Exception {
        var pending = mvc.perform(get("/async-fragments")).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(pending)).andExpect(status().isNotAcceptable());
    }
    @Test void rejectsFunctionalServletRouterBeans() {
        org.springframework.web.servlet.function.RouterFunction<org.springframework.web.servlet.function.ServerResponse> router = request -> java.util.Optional.empty();
        assertThrows(IllegalStateException.class, () -> RestApiRenderingGuard.restApiRenderingPolicy().postProcessAfterInitialization(router, "router"));
    }
    @Test void rejectsXsltBeansAndGenericResolversPointingToXslt() {
        var guard = RestApiRenderingGuard.restApiRenderingPolicy();
        assertThrows(IllegalStateException.class, () -> guard.postProcessAfterInitialization(new XsltViewResolver(), "xslt"));
        var generic = new UrlBasedViewResolver(); generic.setViewClass(XsltView.class);
        assertThrows(IllegalStateException.class, () -> guard.postProcessAfterInitialization(generic, "generic"));
    }
    @Configuration(proxyBeanMethods = false) @EnableWebMvc
    static class WebConfig {
        @Bean Routes routes() { return new Routes(); }
        @Bean ImplicitRoutes implicitRoutes() { return new ImplicitRoutes(); }
    }
    @org.springframework.stereotype.Controller
    static class ImplicitRoutes {
        @GetMapping("/implicit") Map<String,Object> implicit() { return Map.of("ok", true); }
        @GetMapping("/named") String named() { return "unsafe"; }
    }
    @RestController
    static class Routes {
        @GetMapping("/json") Map<String,Object> json() { return Map.of("ok", true); }
        @GetMapping("/async") java.util.concurrent.CompletableFuture<Map<String,Object>> async() { return java.util.concurrent.CompletableFuture.completedFuture(Map.of("ok", true)); }
        @GetMapping("/async-fragments") java.util.concurrent.CompletableFuture<FragmentsRendering> asyncFragments() { return java.util.concurrent.CompletableFuture.completedFuture(FragmentsRendering.with("unsafe").build()); }
        @GetMapping("/view") ModelAndView view() { return new ModelAndView("unsafe"); }
        @GetMapping("/fragments") FragmentsRendering fragments() { return FragmentsRendering.with("unsafe").build(); }
        @GetMapping("/events") SseEmitter events() throws Exception {
            var emitter = new SseEmitter();
            emitter.send(SseEmitter.event().name("progress").data(Map.of("text", "a\nevent: injected\ndata: hacked")));
            emitter.complete(); return emitter;
        }
    }
}
