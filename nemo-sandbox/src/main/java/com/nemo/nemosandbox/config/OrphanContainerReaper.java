package com.nemo.nemosandbox.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.nemo.nemosandbox.DockerClientFactory;
import com.nemo.nemosandbox.DockerExecutionEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Map;

@Slf4j
@Component
public class OrphanContainerReaper {
    private final DockerClientFactory clients;
    private final SandboxExecutionMetrics metrics;
    @Value("${sandbox.cleanup.orphan-min-age-ms:900000}") private long minimumAgeMs;

    public OrphanContainerReaper(DockerClientFactory clients, SandboxExecutionMetrics metrics) {
        this.clients = clients; this.metrics = metrics;
    }

    @PostConstruct void reapAtStartup() { reap(); }

    @Scheduled(fixedDelayString = "${sandbox.cleanup.reaper-interval-ms:300000}")
    public void reap() {
        long cutoff = System.currentTimeMillis() - Math.max(60_000, minimumAgeMs);
        DockerClient client = null;
        try {
            client = clients.createClient();
            for (Container container : client.listContainersCmd().withShowAll(true)
                    .withLabelFilter(Map.of(DockerExecutionEngine.CONTAINER_LABEL_MARKER, "true")).exec()) {
                String raw = container.getLabels() == null ? null
                        : container.getLabels().get(DockerExecutionEngine.CONTAINER_LABEL_CREATED_AT);
                long created;
                try { created = Long.parseLong(raw); }
                catch (RuntimeException invalidLabel) {
                    log.warn("跳过创建时间标签无效的沙箱容器, containerId={}, createdAt={}", container.getId(), raw);
                    continue;
                }
                if (created >= cutoff) continue;
                try {
                    client.removeContainerCmd(container.getId()).withForce(true).exec();
                    metrics.orphanReaped();
                    log.warn("已回收残留沙箱容器, containerId={}, ageMs={}", container.getId(), System.currentTimeMillis() - created);
                } catch (RuntimeException e) {
                    metrics.cleanupFailed();
                    log.error("残留沙箱容器回收失败, containerId={}", container.getId(), e);
                }
            }
        } catch (RuntimeException e) {
            log.error("扫描残留沙箱容器失败", e);
        } finally {
            if (client != null) {
                try { client.close(); }
                catch (Exception e) { log.warn("关闭残留容器扫描客户端失败", e); }
            }
        }
    }
}
