# RabbitMQ

## 使用目的
- 将判题请求从**同步调用**（OpenFeign）改为**异步消息**（RabbitMQ），解耦提交服务和判题服务
- 解决同步判题时用户需等待判题完成才能获得响应的问题，提交后立即返回 submitId
- 削峰填谷：高并发提交时消息队列缓冲请求，判题服务按自身能力消费

## 使用文档

### 依赖引入
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
```
Spring Boot 2.6 自动管理版本号。

### 配置步骤

#### 1. application.yml
```yaml
spring:
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest
    listener:
      simple:
        default-requeue-rejected: false  # 消费失败不重回队列，进入死信
```

#### 2. 声明队列、交换机、绑定 + 死信队列（生产者端）
```java
@Configuration
public class RabbitMqConfig {
    // --- 消息持久化 ---
    @PostConstruct
    public void initRabbitTemplate() {
        rabbitTemplate.setBeforePublishPostProcessors(message -> {
            message.getMessageProperties()
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return message;
        });
    }

    // --- 判题队列（绑定死信交换机） ---
    @Bean
    public Queue judgeQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", "judge.dlx.exchange");
        args.put("x-dead-letter-routing-key", "judge.dlx.submit");
        return QueueBuilder.durable("judge.queue").withArguments(args).build();
    }

    @Bean
    public DirectExchange judgeExchange() {
        return new DirectExchange("judge.exchange");
    }

    @Bean
    public Binding judgeBinding() {
        return BindingBuilder.bind(judgeQueue()).to(judgeExchange()).with("judge.submit");
    }
}
```

#### 3. 声明队列、交换机、绑定 + 死信队列（消费者端）
消费者端与生产者端声明相同的 `judgeQueue`/`judgeExchange`/`judgeBinding`，额外声明死信队列：
```java
@Configuration
public class RabbitMqConfig {
    // ... 同上 judgeQueue/judgeExchange/judgeBinding ...

    @Bean
    public Queue judgeDlxQueue() {
        return new Queue("judge.dlx.queue", true);
    }

    @Bean
    public DirectExchange judgeDlxExchange() {
        return new DirectExchange("judge.dlx.exchange");
    }

    @Bean
    public Binding judgeDlxBinding() {
        return BindingBuilder.bind(judgeDlxQueue()).to(judgeDlxExchange()).with("judge.dlx.submit");
    }
}
```

#### 4. 生产者（发送持久化消息）
```java
@Resource
private RabbitTemplate rabbitTemplate;

public void sendJudgeTask(long submitId) {
    // deliveryMode = PERSISTENT 已在 @PostConstruct 中全局设置
    rabbitTemplate.convertAndSend("judge.exchange", "judge.submit", submitId);
}
```

#### 5. 消费者（接收消息）
启动类添加 `@EnableRabbit`：
```java
@SpringBootApplication
@EnableRabbit
public class Application { ... }
```

消费者：
```java
@Component
public class JudgeMessageConsumer {
    @RabbitListener(queues = "judge.queue")
    public void onJudgeMessage(long submitId) {
        judgeService.doJudge(submitId);
    }
}
```

#### 6. 死信消费者
```java
@Slf4j
@Component
public class JudgeDlxConsumer {
    @RabbitListener(queues = "judge.dlx.queue")
    public void onDlxMessage(long submitId) {
        log.error("判题消息进入死信队列，submitId={}", submitId);
    }
}
```

### 消息流转

```
正常流程：
  Producer → judge.exchange → judge.queue → JudgeMessageConsumer → doJudge() 成功 → ACK

异常流程：
  Producer → judge.exchange → judge.queue → JudgeMessageConsumer → doJudge() 抛异常
    → 因 default-requeue-rejected=false，消息被拒绝
    → 根据 x-dead-letter-exchange 路由到 judge.dlx.exchange
    → judge.dlx.queue → JudgeDlxConsumer.onDlxMessage() → 日志告警
```

### 注意事项
- 队列声明为 `durable=true`，确保 RabbitMQ 重启后队列不丢失
- 生产者端通过 `setBeforePublishPostProcessors` 全局设置 `deliveryMode=PERSISTENT`，确保消息即使在新队列上也是持久化的
- 消费者端设置 `default-requeue-rejected: false`，消费失败的消息不会无限重回队列，而是进入死信队列
- 死信队列也需要消费者监听，至少记录日志，否则死信消息会一直被存储占用空间
- `JudgeService.doJudge()` 是幂等的（判题前检查状态为 WAITING_FOR_JUDGING），不会重复判题
- 启动顺序：先启动 RabbitMQ 服务，再启动微服务

## 横向对比

| 对比项 | RabbitMQ | RocketMQ | Kafka | Redis 队列 |
|--------|----------|----------|-------|------------|
| 协议 | AMQP 0-9-1 | 自研 | 自研 | 自定义 |
| 语言 | Erlang | Java | Java/Scala | — |
| 消息可靠性 | 高（ACK+持久化） | 高 | 高 | 低（无持久化） |
| 死信机制 | 内置 DLX | 内置 DLQ | 无内置 | 无 |
| 延迟 | 微秒级 | 毫秒级 | 毫秒级 | 极低 |
| 运维复杂度 | 低 | 中 | 高 | 无需额外服务 |
| 适用场景 | 通用异步解耦 | 大规模电商/金融 | 日志/流处理 | 轻量任务队列 |
| Spring 集成 | spring-boot-starter-amqp | rocketmq-spring-boot | spring-kafka | spring-data-redis |

## 选择理由
- Spring Boot 对 AMQP 开箱即用的支持最好（`spring-boot-starter-amqp`），零额外配置即可集成
- RabbitMQ 成熟稳定，个人项目单机部署即可，资源占用小
- 当前场景是"提交→判题"的任务分发，消息量小、不需要顺序消费、不需要回溯——RabbitMQ 最匹配
- DLX（死信交换机）是 RabbitMQ 内置机制，不需要额外组件即可实现失败消息收集
- RocketMQ 和 Kafka 更适合日均千万级消息的大规模场景，个人项目杀鸡用牛刀
- Redis 队列需要手动实现 ACK、重试、死信等机制，增加开发量
