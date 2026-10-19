package com.nemo.OJbackendquestionservice.outbox;

/**
 * 发件箱事件状态。
 * <p>
 * 与数据库 {@code outbox_event.status} 的取值一一对应，改动需同步迁移脚本。
 */
public final class OutboxStatus {

    /** 待投递：已入库但尚未确认送达 broker */
    public static final int PENDING = 0;

    /** 投递中：已被某个实例通过租约抢占 */
    public static final int IN_FLIGHT = 1;

    /** 已投递：收到 broker 确认且路由正确 */
    public static final int DELIVERED = 2;

    /** 永久失败：重试次数耗尽，需人工介入，**不会被自动清理** */
    public static final int DEAD = 3;

    private OutboxStatus() {
    }
}
