package com.nemo.OJbackendmodel.model.enums;

/**
 * 判题比较器类型
 */
public enum ComparatorTypeEnum {

    /** 精确比较：整数、字符串、数组、链表、树的深度结构化比较 */
    EXACT("exact"),

    /** 文本比较：ACM 模式默认，规范化行尾空格和多余换行 */
    TEXT("text"),

    /** 浮点比较：支持绝对误差和相对误差，需配置 epsilon */
    FLOAT("float"),

    /** 无序列表：不关心元素顺序 */
    UNORDERED_LIST("unordered_list"),

    /** 多重集合：不关心顺序但保留重复次数 */
    MULTISET("multiset");

    private final String value;

    ComparatorTypeEnum(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
