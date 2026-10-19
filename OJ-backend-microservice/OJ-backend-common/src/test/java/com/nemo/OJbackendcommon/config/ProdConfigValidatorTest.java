package com.nemo.OJbackendcommon.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生产配置校验单元测试。
 * <p>
 * 覆盖"缺配置启动失败"与"弱口令/示例值启动失败"两条上线门槛：
 * 生产环境既不能因缺配置静默回退，也不能带着示例密钥启动。
 */
class ProdConfigValidatorTest {

    /** 构造 prod profile 下的校验器 */
    private static ProdConfigValidator validatorWith(String key, String value) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        if (key != null) {
            env.setProperty(key, value);
        }
        return new ProdConfigValidator(env);
    }

    /** 构造非 prod profile 下的校验器（dev / test） */
    private static ProdConfigValidator devValidatorWith(String key, String value) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        if (key != null) {
            env.setProperty(key, value);
        }
        return new ProdConfigValidator(env);
    }

    // ---------- 弱值识别 ----------

    @Test
    @DisplayName("识别已知示例值与弱口令")
    void recognisesForbiddenValues() {
        for (String weak : new String[]{"secretKey", "SECRETKEY", " dev-only-change-me ", "password",
                "123456", "root", "3820", "changeme", "admin"}) {
            assertTrue(ProdConfigValidator.isForbidden(weak), "应判定为弱值: " + weak);
        }
    }

    @Test
    @DisplayName("不误判足够强度的随机密钥")
    void acceptsStrongValues() {
        assertFalse(ProdConfigValidator.isForbidden("9f3a1c7e5b2d8046a1c9e2f7b3d5a8c1"));
        assertFalse(ProdConfigValidator.isForbidden(null));
    }

    // ---------- 密钥校验 ----------

    @Test
    @DisplayName("沙箱密钥为示例值时拒绝启动")
    void rejectsExampleSandboxSecret() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                validatorWith("sandbox.auth.secret", "dev-only-change-me").validate());
        assertTrue(ex.getMessage().contains("sandbox.auth.secret"), "错误信息应指出具体配置项");
    }

    @Test
    @DisplayName("判题端沙箱密钥过短时拒绝启动")
    void rejectsShortSandboxSecret() {
        assertThrows(IllegalStateException.class, () ->
                validatorWith("judge.sandbox.secret", "short123").validate());
    }

    @Test
    @DisplayName("未使用该配置的服务跳过校验")
    void skipsAbsentKeys() {
        assertDoesNotThrow(() -> validatorWith(null, null).validate());
        assertDoesNotThrow(() -> validatorWith("some.other.key", "value").validate());
    }

    @Test
    @DisplayName("合规密钥通过校验")
    void acceptsValidSecret() {
        assertDoesNotThrow(() ->
                validatorWith("sandbox.auth.secret", "3f8c1d9a4e7b2065af13c8d5e9b47a02").validate());
    }

    // ---------- 口令校验 ----------

    @Test
    @DisplayName("数据库口令为弱口令时拒绝启动")
    void rejectsWeakDatabasePassword() {
        assertThrows(IllegalStateException.class, () ->
                validatorWith("spring.datasource.password", "3820").validate());
    }

    @Test
    @DisplayName("未设置口令时不报错（如本机无密码 Redis）")
    void allowsEmptyPassword() {
        assertDoesNotThrow(() -> validatorWith("spring.data.redis.password", "").validate());
    }

    @Test
    @DisplayName("空白口令拒绝启动")
    void rejectsBlankPassword() {
        assertThrows(IllegalStateException.class, () ->
                validatorWith("spring.datasource.password", "   ").validate());
    }

    // ---------- 所有环境：必需项缺失 ----------

    @Test
    @DisplayName("dev 下缺少数据库口令同样拒绝启动（避免'启动成功但不可用'的假象）")
    void rejectsMissingPasswordInDev() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        env.setProperty("spring.datasource.url", "jdbc:mysql://localhost:3306/x");
        env.setProperty("spring.datasource.username", "root");
        // 未设置 spring.datasource.password
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new ProdConfigValidator(env).validate());
        assertTrue(ex.getMessage().contains("spring.datasource.password"),
                "错误信息应指出缺失的配置项: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("环境变量"),
                "错误信息应告知如何提供: " + ex.getMessage());
    }

    @Test
    @DisplayName("dev 下缺用户名同样拒绝启动，且报出第一个缺失项")
    void rejectsMissingUsernameInDev() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                devValidatorWith("spring.datasource.url", "jdbc:mysql://localhost:3306/x").validate());
        assertTrue(ex.getMessage().contains("spring.datasource.username"), ex.getMessage());
    }

    @Test
    @DisplayName("未使用数据库的服务不触发必需项校验")
    void skipsDatasourceCheckWhenUnused() {
        assertDoesNotThrow(() -> devValidatorWith(null, null).validate());
    }

    @Test
    @DisplayName("dev 下弱口令不拦截（仅 prod 校验强度）")
    void devAllowsWeakPassword() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        env.setProperty("spring.datasource.url", "jdbc:mysql://localhost:3306/x");
        env.setProperty("spring.datasource.username", "root");
        env.setProperty("spring.datasource.password", "3820");
        assertDoesNotThrow(() -> new ProdConfigValidator(env).validate());
    }

    @Test
    @DisplayName("dev 下占位符无法解析时给出可操作提示")
    void reportsUnresolvedPlaceholderInDev() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        env.setProperty("spring.datasource.url", "jdbc:mysql://localhost:3306/x");
        env.setProperty("spring.datasource.username", "root");
        env.setProperty("spring.datasource.password", "${DB_PASSWORD}");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new ProdConfigValidator(env).validate());
        assertTrue(ex.getMessage().contains("spring.datasource.password"), ex.getMessage());
    }
}
