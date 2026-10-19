package com.nemo.nemosandbox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Docker 客户端工厂 —— 显式使用 Apache HttpClient5 传输层。
 *
 * 背景：docker-java 默认的 DockerClientBuilder.getInstance().build()
 * 在 Windows 上回退到 Jersey (OKHttp) transport，无法处理 npipe:// 协议。
 * 本项目已引入 docker-java-transport-httpclient5，通过显式构造
 * ApacheDockerHttpClient + DockerClientImpl 来解决此问题。
 *
 * 每个调用方负责 close() 返回的 DockerClient，避免连接/线程泄漏。
 */
@Slf4j
@Component
public class DockerClientFactory {

    @Value("${sandbox.docker.shared-client:true}")
    private boolean sharedClientEnabled=true;
    private DockerClient sharedClient;
    private DockerClient sharedHandle;
    private boolean closed;

    private final DockerClientConfig config;

    @Value("${sandbox.docker.connection-timeout-seconds:5}")
    private int connectionTimeoutSeconds;

    @Value("${sandbox.docker.response-timeout-seconds:240}")
    private int responseTimeoutSeconds;

    @Value("${sandbox.type:docker}")
    private String sandboxType;

    @Value("${sandbox.docker.fail-fast:true}")
    private boolean failFastOnDockerUnavailable;

    /**
     * 尽早探测 Docker 可用性。
     * <p>
     * 放在这里而不是 CommandLineRunner：后者在所有 Bean 初始化之后才执行，
     * 此时镜像校验等下游步骤已经先失败，报出来的是"镜像未就绪"这类症状，
     * 而根因其实是 Docker 连不上——排查时会被带偏。
     */
    @jakarta.annotation.PostConstruct
    void verifyDockerOnStartup() {
        if (!"docker".equalsIgnoreCase(sandboxType)) {
            return;
        }
        if (ping()) {
            log.info("Docker 连通性校验通过");
            return;
        }
        String message = "沙箱类型为 docker，但 Docker 不可用。"
                + "判题将无法执行，且失败会被误报为用户代码问题，因此拒绝启动。"
                + "请确认 Docker daemon 已运行且当前进程有权限访问；"
                + "临时排查可用 sandbox.docker.fail-fast=false 跳过本校验。";
        if (failFastOnDockerUnavailable) {
            throw new IllegalStateException(message);
        }
        log.error("{}（已按配置跳过校验，沙箱将以不可用状态运行）", message);
    }

    public DockerClientFactory() {
        this.config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        log.info("DockerClientFactory 初始化: dockerHost={}, apiVersion={}",
                config.getDockerHost(), config.getApiVersion());
    }

    /**
     * 创建一个新的 DockerClient 实例。
     * 调用方必须在 finally 块中 close() 该客户端。
     * <p>
     * 超时取较短值：Docker 不可用时必须**快速失败**。
     * 原实现连接超时 30 秒、响应超时 60 秒，实测在 Docker 未运行时
     * 请求会一直阻塞到调用方（判题服务）超时，表现为沙箱"挂起"而不是报错，
     * 用户提交会长时间停在判题中。
     */
    public DockerClient createClient() {return newClient();}

    /** Borrow the thread-safe control transport. Closing a handle never closes peers. */
    public synchronized DockerClient borrowClient() {
        if(closed)throw new IllegalStateException("Docker client factory is closed");
        if(!sharedClientEnabled)return newClient();
        if(sharedHandle==null){
            sharedClient=newClient();
            sharedHandle=(DockerClient)java.lang.reflect.Proxy.newProxyInstance(DockerClient.class.getClassLoader(),new Class<?>[]{DockerClient.class},(proxy,method,args)->{
                if(method.getName().equals("close") && method.getParameterCount()==0)return null;
                try{return method.invoke(sharedClient,args);}
                catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
            });
        }
        return sharedHandle;
    }

    @jakarta.annotation.PreDestroy
    synchronized void shutdown() throws java.io.IOException {
        if(closed)return;
        closed=true;
        if(sharedClient!=null)sharedClient.close();
    }

    DockerClient newClient() {
        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(100)
                .connectionTimeout(Duration.ofSeconds(connectionTimeoutSeconds))
                .responseTimeout(Duration.ofSeconds(responseTimeoutSeconds))
                .build();
        return DockerClientImpl.getInstance(config, httpClient);
    }

    /**
     * 探测 Docker 是否可用。启动校验与就绪判断使用。
     * <p>
     * 用极短的超时，避免探测本身成为阻塞点。
     *
     * @return 可用返回 true；不可用返回 false 并记录原因
     */
    public boolean ping() {
        DockerClient client = null;
        try {
            client = createClient();
            client.pingCmd().exec();
            return true;
        } catch (Exception e) {
            log.error("Docker 不可用: {}（dockerHost={}）", e.getMessage(), config.getDockerHost());
            return false;
        } finally {
            if (client != null) {
                try {
                    client.close();
                } catch (Exception ignored) {
                    // 关闭失败不影响探测结论
                }
            }
        }
    }
}
