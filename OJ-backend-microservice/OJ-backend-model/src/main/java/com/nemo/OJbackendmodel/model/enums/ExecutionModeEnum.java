package com.nemo.OJbackendmodel.model.enums;

/**
 * 题目执行模式
 */
public enum ExecutionModeEnum {

    /** ACM 模式：用户提交完整程序，通过 stdin 读取输入，stdout 输出结果 */
    ACM("acm", "ACM 模式"),

    /** 核心代码模式：用户只实现指定函数，系统生成 Runner 调用 */
    FUNCTION("function", "核心代码模式");

    private final String value;
    private final String text;

    ExecutionModeEnum(String value, String text) {
        this.value = value;
        this.text = text;
    }

    public String getValue() {
        return value;
    }

    public String getText() {
        return text;
    }
}
