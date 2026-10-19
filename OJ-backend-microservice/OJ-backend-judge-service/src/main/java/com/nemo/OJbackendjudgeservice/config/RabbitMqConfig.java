package com.nemo.OJbackendjudgeservice.config;

import com.nemo.OJbackendcommon.constant.JudgeMqConstant;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * RabbitMQ 配置（消费者端）
 */
@Configuration
public class RabbitMqConfig {

    /** Match the question service's JSON publisher for both normal and DLQ listeners. */
    @Bean
    public org.springframework.amqp.support.converter.Jackson2JsonMessageConverter judgeMessageConverter() {
        return new org.springframework.amqp.support.converter.Jackson2JsonMessageConverter();
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

    // --- 死信队列 ---

    @Bean
    public Queue judgeDlxQueue() {
        return new Queue(JudgeMqConstant.JUDGE_DLX_QUEUE, true);
    }

    @Bean
    public DirectExchange judgeDlxExchange() {
        return new DirectExchange(JudgeMqConstant.JUDGE_DLX_EXCHANGE);
    }

    @Bean
    public Binding judgeDlxBinding() {
        return BindingBuilder.bind(judgeDlxQueue()).to(judgeDlxExchange()).with(JudgeMqConstant.JUDGE_DLX_ROUTING_KEY);
    }
}
