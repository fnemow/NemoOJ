package com.nemo.OJbackendmodel.model.enums;

/**
 * 核心代码模式结果来源：用于指示比较返回值还是原地修改的参数
 */
public enum ResultSourceEnum {

    /** 比较函数返回值 */
    RETURN("return"),

    /** 比较第一个原地修改的参数 */
    PARAMETER_0("parameter:0"),

    /** 比较第二个原地修改的参数 */
    PARAMETER_1("parameter:1"),

    /** 比较多个参数 */
    PARAMETERS_0_1("parameters:[0,1]");

    private final String value;

    ResultSourceEnum(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
