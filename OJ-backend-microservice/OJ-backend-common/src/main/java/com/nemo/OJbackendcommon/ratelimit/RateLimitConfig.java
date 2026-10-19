package com.nemo.OJbackendcommon.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 限流装配。
 * <p>
 * 优先使用 Redis 共享计数（多实例配额正确）；Redis 不可用时降级为**收紧后的**本地限额。
 * 降级不是"放行"——高成本接口在无保护状态下敞开比限流误伤更危险。
 * <p>
 * 配置类按类路径上是否存在 spring-data-redis **拆成两个**：
 * `common` 把该依赖标记为 optional，因此并非所有引入 common 的服务都有它
 * （例如判题服务就不需要 Redis）。若在同一个 `@Bean` 方法签名里直接写
 * `ObjectProvider&lt;StringRedisTemplate&gt;`，即使方法体不用它，
 * Spring 解析泛型时仍会加载该类，导致没有该依赖的服务**启动失败**。
 * 拆开之后，缺少依赖的服务只会装配本地实现。
 */
@Slf4j
@Configuration
public class RateLimitConfig {

    /** 类路径上有 spring-data-redis：使用共享计数 */
    @Configuration
    @ConditionalOnClass(StringRedisTemplate.class)
    static class RedisBackedConfig {

        @Bean
        @ConditionalOnMissingBean(RateLimiter.class)
        RateLimiter redisRateLimiter(ObjectProvider<StringRedisTemplate> redisProvider) {
            return new RedisRateLimiter(redisProvider, new InMemoryRateLimiter());
        }
    }

    /** 类路径上没有 spring-data-redis：直接用本地实现 */
    @Configuration
    @ConditionalOnMissingClass("org.springframework.data.redis.core.StringRedisTemplate")
    static class LocalOnlyConfig {

        @Bean
        @ConditionalOnMissingBean(RateLimiter.class)
        RateLimiter inMemoryRateLimiter() {
            log.warn("类路径上未引入 Redis，限流使用本地实现；"
                    + "多实例部署下配额会按实例数放大，请确认该服务是否需要共享限流");
            return new InMemoryRateLimiter();
        }
    }
}
