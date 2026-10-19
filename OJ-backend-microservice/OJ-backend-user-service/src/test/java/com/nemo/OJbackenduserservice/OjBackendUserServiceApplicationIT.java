package com.nemo.OJbackenduserservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 上下文加载冒烟测试。
 * 使用 it profile（见 src/test/resources/application-it.yml），
 * 它指向独立的测试库并关闭注册中心，避免依赖本地 Nacos 与开发库。
 */
@SpringBootTest
@ActiveProfiles("it")
class OjBackendUserServiceApplicationIT {

    @Test
    void contextLoads() {
    }

}
