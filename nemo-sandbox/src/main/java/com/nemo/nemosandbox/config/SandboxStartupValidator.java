package com.nemo.nemosandbox.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 启动时校验沙箱配置与关键依赖。
 * <p>
 * 为什么必须校验 Docker：沙箱的全部执行能力都依赖 Docker。
 * 若 Docker 不可用而应用照常启动，每个判题请求都会在 Docker 调用上失败——
 * 更糟的是原实现在库缺失时只记一条日志就继续，导致 Runner 编译失败被当成
 * **用户代码的编译错误**反馈给用户。这属于"系统问题误报为用户问题"。
 * <p>
 * 因此：使用 docker 类型沙箱时，Docker 不可用即**启动失败**（fail-fast），
 * 而不是带病运行。是否强制由 {@code sandbox.docker.fail-fast} 控制，
 * 默认为 true；排查问题时可在本地临时关闭。
 */
@Slf4j
@Component
public class SandboxStartupValidator implements CommandLineRunner {

    @Resource
    private ApplicationContext applicationContext;

    @Value("${sandbox.type:docker}")
    private String sandboxType;

    @Override
    public void run(String... args) {
        String[] sandboxBeans = applicationContext.getBeanNamesForType(
                com.nemo.nemosandbox.CodeSandBox.class);
        if (sandboxBeans.length == 0) {
            throw new IllegalStateException(
                    "未找到任何注册的沙箱实现！请检查 sandbox.type 配置: " + sandboxType);
        }
        if (sandboxBeans.length > 1) {
            log.warn("注册了多个沙箱实现: {}，将使用: {}",
                    String.join(", ", sandboxBeans), sandboxBeans[0]);
        }
        log.info("当前沙箱类型: {}，使用实现: {}", sandboxType, sandboxBeans[0]);

        // Docker 可用性由 DockerClientFactory 在 Bean 初始化阶段校验（更早，且顺序正确）
    }
}
