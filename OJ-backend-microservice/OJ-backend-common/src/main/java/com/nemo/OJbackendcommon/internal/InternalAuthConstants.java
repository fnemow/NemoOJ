package com.nemo.OJbackendcommon.internal;

/**
 * 内部服务凭据约定的常量。
 * <p>
 * 单独放在这里而不是挂在 {@link InternalAuthFilter} 上：过滤器依赖 Servlet API，
 * 而网关是 WebFlux 应用、类路径上没有 Servlet。把常量放在无依赖的类里，
 * 网关才能安全引用，不必依赖"常量会在编译期内联"这一隐含前提。
 */
public final class InternalAuthConstants {

    /** 内部调用凭据的请求头名。网关会在入口处剥离该头，防止外部伪造。 */
    public static final String HEADER = "X-Internal-Token";

    /** 内部接口路径前缀（相对于各服务的 context-path）。 */
    public static final String INNER_PATH_PATTERN = "/inner/*";

    private InternalAuthConstants() {
    }
}
