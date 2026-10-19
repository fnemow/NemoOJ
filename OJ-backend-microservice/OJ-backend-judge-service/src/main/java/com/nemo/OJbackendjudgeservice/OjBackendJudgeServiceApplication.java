package com.nemo.OJbackendjudgeservice;

import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

// 判题服务不直接访问数据库（题目与提交通过 QuestionFeignClient 获取），
// 但共享模块 OJ-backend-model 会传递引入 mybatis-plus/JDBC 栈；
// 若不排除，DataSourceAutoConfiguration 会在无 spring.datasource.url 时直接启动失败。
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@EnableRabbit
@EnableScheduling
// 本服务没有任何 @Aspect 切面，也不需要 AopContext。
// 此前保留 @EnableAspectJAutoProxy 只是继承了 mybatis-plus starter 传递的 aspectjweaver；
// Boot 3 下该传递依赖不再存在，反而导致启动时 NoClassDefFoundError: org/aspectj/lang/annotation/Pointcut。
@ComponentScan("com.nemo")
@EnableDiscoveryClient
@EnableFeignClients(basePackages ={"com.nemo.OJserviceclient"})
public class OjBackendJudgeServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OjBackendJudgeServiceApplication.class, args);
    }

}
