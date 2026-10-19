package com.nemo.OJbackendquestionservice.config;

import com.nemo.OJbackendcommon.constant.JudgeMqConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

/**
 * RabbitMQ 配置（生产者端）
 */
@Slf4j
@Configuration
public class RabbitMqConfig {

    @Resource
    private RabbitTemplate rabbitTemplate;

    /** 消息持久化 + JSON 序列化 + 不可路由感知 */
    @PostConstruct
    public void initRabbitTemplate() {
        rabbitTemplate.setMessageConverter(new Jackson2JsonMessageConverter());
        rabbitTemplate.setReturnsCallback(returned ->
                log.error("消息被退回（不可路由）: exchange={}, routingKey={}, replyText={}",
                        returned.getExchange(), returned.getRoutingKey(), returned.getReplyText()));
        // mandatory=true 才会在"交换机收下但找不到队列"时把消息退回（basic.return）。
        // 没有它，broker 会先 ACK 再静默丢弃，投递方会误判为成功。
        rabbitTemplate.setMandatory(true);
        rabbitTemplate.setBeforePublishPostProcessors(message -> {
            message.getMessageProperties().setDeliveryMode(
                    org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
            return message;
        });
    }

    /** 判题队列，绑定死信交换机 */
    @Bean
    public Queue judgeQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", JudgeMqConstant.JUDGE_DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", JudgeMqConstant.JUDGE_DLX_ROUTING_KEY);
        return QueueBuilder.durable(JudgeMqConstant.JUDGE_QUEUE).withArguments(args).build();
    }

    @Bean
    public DirectExchange judgeExchange() {
        return new DirectExchange(JudgeMqConstant.JUDGE_EXCHANGE);
    }

    @Bean
    public Binding judgeBinding() {
        return BindingBuilder.bind(judgeQueue()).to(judgeExchange()).with(JudgeMqConstant.JUDGE_ROUTING_KEY);
    }

    @Bean
    public Queue aiTaskQueue() {
        return QueueBuilder.durable("nemo.ai.tasks").build();
    }

    @Bean
    public DirectExchange aiTaskExchange() {
        return new DirectExchange("nemo.ai.exchange", true, false);
    }

    @Bean
    public Binding aiTaskBinding() {
        return BindingBuilder.bind(aiTaskQueue()).to(aiTaskExchange()).with("nemo.ai.task");
    }
    @Bean public Queue aiAnalysisQueue(){return QueueBuilder.durable("nemo.ai.analysis").build();}
    @Bean public Queue aiPlanQueue(){return QueueBuilder.durable("nemo.ai.plans").build();}
    @Bean public Queue aiPrivateQueue(){return QueueBuilder.durable("nemo.ai.private").build();}
    @Bean public Binding aiAnalysisBinding(){return BindingBuilder.bind(aiAnalysisQueue()).to(aiTaskExchange()).with("nemo.ai.analysis");}
    @Bean public Binding aiPlanBinding(){return BindingBuilder.bind(aiPlanQueue()).to(aiTaskExchange()).with("nemo.ai.plans");}
    @Bean public Binding aiPrivateBinding(){return BindingBuilder.bind(aiPrivateQueue()).to(aiTaskExchange()).with("nemo.ai.private");}
}
