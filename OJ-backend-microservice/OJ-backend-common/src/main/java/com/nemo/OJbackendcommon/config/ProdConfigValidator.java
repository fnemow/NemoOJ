package com.nemo.OJbackendcommon.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 启动配置校验。
 * <p>
 * 分两层，目的不同：
 * <ol>
 *   <li><b>所有环境</b>：必需的配置项必须存在且非空。
 *       若占位符（如 {@code ${DB_PASSWORD}}）无法解析，Spring 默认只会在**首次访问数据库时**
 *       才报错——应用已经监听端口、看似启动成功。实测确认过这一点：dev profile 缺少
 *       {@code DB_PASSWORD} 时 Tomcat 仍会启动并持续运行，直到有请求访问数据库才失败。
 *       这里提前到 Bean 初始化阶段暴露，避免"启动成功但不可用"的假象。</li>
 *   <li><b>仅 prod</b>：配置项不得使用示例值或弱口令，密钥需达到最小长度。
 *       "已设置"不等于"设置正确"，占位符解析无法发现 {@code SANDBOX_SECRET=secretKey} 这类问题。</li>
 * </ol>
 */
@Slf4j
@Component
public class ProdConfigValidator {

    /**
     * 已知的示例/弱值。命中即拒绝启动。
     * 这些字符串同样出现在开发配置与本仓库历史中，属于必须轮换的对象。
     */
    private static final Set<String> FORBIDDEN_VALUES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "secretkey", "secret_key", "secret",
                    "dev-only-change-me", "change-me", "changeme",
                    "dev-only-internal-token", "internal-token",
                    "password", "passwd", "123456", "12345678",
                    "root", "admin", "test", "example",
                    "3820"
            )));

    /** 密钥类配置的最小长度 */
    private static final int MIN_SECRET_LENGTH = 16;

    /** 需要按"密钥"标准校验的配置项（本服务不存在该项时自动跳过） */
    private static final List<String> SECRET_KEYS = Arrays.asList(
            "sandbox.auth.secret",
            "judge.sandbox.secret",
            "internal.auth.secret");

    /** 需要按"口令"标准校验的配置项（本服务不存在该项时自动跳过） */
    private static final List<String> PASSWORD_KEYS = Arrays.asList(
            "spring.datasource.password",
            "spring.data.redis.password",
            "spring.rabbitmq.password");

    /** 使用数据库时必须有值的配置项 */
    private static final List<String> DATASOURCE_REQUIRED_KEYS = Arrays.asList(
            "spring.datasource.url",
            "spring.datasource.username",
            "spring.datasource.password");

    private final Environment environment;

    /** 是否处于生产 profile */
    private final boolean production;

    public ProdConfigValidator(Environment environment) {
        this.environment = environment;
        this.production = Arrays.asList(environment.getActiveProfiles()).contains("prod");
    }

    /**
     * 必须用 {@code @PostConstruct}（Bean 初始化期）而不是 ApplicationRunner（启动完成后）：
     * 后者会在 Tomcat 已经监听端口之后才抛异常，出现"短暂可服务随后崩溃"的窗口。
     * Bean 初始化发生在 Web 容器启动之前，因此失败时端口不会被打开。
     */
    @PostConstruct
    void validate() {
        validateRequired();
        if (production) {
            validateSecrets();
            validatePasswords();
        }
        log.info("配置校验通过（profile: {}）",
                production ? "prod" : String.join(",", environment.getActiveProfiles()));
    }

    /**
     * 所有环境：使用了数据库的服务必须能解析出连接信息。
     * 这是"dev 缺 DB_PASSWORD 也能启动"这一假象的修复点。
     */
    private void validateRequired() {
        // 判断本服务是否使用数据库：只有真正配置了 datasource 的服务才检查
        if (resolve("spring.datasource.url") == null) {
            return;
        }
        for (String key : DATASOURCE_REQUIRED_KEYS) {
            String value = resolve(key);
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalStateException(missingMessage(key));
            }
        }
    }

    private void validateSecrets() {
        for (String key : SECRET_KEYS) {
            String value = resolve(key);
            if (value == null) {
                continue; // 该服务不使用此配置
            }
            rejectIfBlank(key, value);
            rejectIfForbidden(key, value);
            if (value.length() < MIN_SECRET_LENGTH) {
                throw new IllegalStateException(
                        "生产配置校验失败：" + key + " 长度不足 " + MIN_SECRET_LENGTH + " 位，请使用足够强度的随机密钥");
            }
        }
    }

    private void validatePasswords() {
        for (String key : PASSWORD_KEYS) {
            String value = resolve(key);
            if (value == null || value.isEmpty()) {
                continue; // 未配置口令（如本机无密码 Redis）不在本校验范围内
            }
            rejectIfBlank(key, value);
            rejectIfForbidden(key, value);
        }
    }

    /**
     * 读取配置；占位符无法解析时转换为可操作的错误信息。
     * Spring 对未解析的占位符会抛 IllegalArgumentException，原始信息不指出该设哪个环境变量。
     */
    private String resolve(String key) {
        try {
            return environment.getProperty(key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(missingMessage(key), e);
        }
    }

    private String missingMessage(String key) {
        return "缺少必需配置：" + key + "。请通过环境变量提供（本地开发参见 build/README.md）";
    }

    private void rejectIfBlank(String key, String value) {
        if (value.trim().isEmpty()) {
            throw new IllegalStateException("生产配置校验失败：" + key + " 为空");
        }
    }

    private void rejectIfForbidden(String key, String value) {
        if (isForbidden(value)) {
            throw new IllegalStateException(
                    "生产配置校验失败：" + key + " 使用了示例值或弱口令，必须在部署时替换（不得写回仓库）");
        }
    }

    /**
     * 供单元测试直接调用，避免测试依赖完整 Spring 上下文。
     */
    public static boolean isForbidden(String value) {
        return value != null && FORBIDDEN_VALUES.contains(value.trim().toLowerCase());
    }
}
