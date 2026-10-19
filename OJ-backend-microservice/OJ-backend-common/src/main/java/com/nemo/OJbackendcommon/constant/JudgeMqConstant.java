package com.nemo.OJbackendcommon.constant;

/**
 * 判题消息队列常量
 */
public interface JudgeMqConstant {

    String JUDGE_EXCHANGE = "judge.exchange";

    String JUDGE_QUEUE = "judge.queue";

    String JUDGE_ROUTING_KEY = "judge.submit";

    /** 死信交换机 */
    String JUDGE_DLX_EXCHANGE = "judge.dlx.exchange";

    /** 死信队列 */
    String JUDGE_DLX_QUEUE = "judge.dlx.queue";

    /** 死信路由键 */
    String JUDGE_DLX_ROUTING_KEY = "judge.dlx.submit";
}
