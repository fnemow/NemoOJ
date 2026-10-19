package com.nemo.OJbackendjudgeservice.observability;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 沙箱可达性健康检查。
 * <p>
 * 只用于**就绪**判定，不用于存活判定：沙箱不可用时判题服务本身没有崩溃，
 * 重启它也解决不了问题，反而会把一个本来能提供查询能力的实例一起摘掉。
 * 就绪判为 DOWN 则停止接单，等沙箱恢复后自动重新加入。
 * <p>
 * 用 TCP 连通性探测而不是发一次真实执行请求：健康检查必须廉价，
 * 每次探测都触发一次沙箱执行会反过来压垮沙箱。
 */
@Slf4j
@Component("sandbox")
public class SandboxHealthIndicator implements HealthIndicator {

    @Value("${judge.sandbox.base-url}")
    private String sandboxBaseUrl;

    @Value("${management.health.sandbox.timeout-ms:1000}")
    private int timeoutMs;

    @Override
    public Health health() {
        String host;
        int port;
        try {
            java.net.URI uri = java.net.URI.create(sandboxBaseUrl);
            host = uri.getHost();
            port = uri.getPort() > 0 ? uri.getPort() : ("https".equals(uri.getScheme()) ? 443 : 80);
        } catch (Exception e) {
            return Health.down().withDetail("reason", "沙箱地址无法解析: " + sandboxBaseUrl).build();
        }

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return Health.up().withDetail("endpoint", host + ":" + port).build();
        } catch (Exception e) {
            log.warn("沙箱健康检查失败: {}:{} - {}", host, port, e.getMessage());
            return Health.down()
                    .withDetail("endpoint", host + ":" + port)
                    .withDetail("reason", e.getClass().getSimpleName())
                    .build();
        }
    }
}
