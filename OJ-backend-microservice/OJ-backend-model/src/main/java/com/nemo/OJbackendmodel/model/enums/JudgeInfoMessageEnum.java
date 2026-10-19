package com.nemo.OJbackendmodel.model.enums;

import org.apache.commons.lang3.ObjectUtils;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 题目提交编程语言枚举
 *
 * @author <a href="https://github.com/liyupi">程序员鱼皮</a>
 * @from <a href="https://yupi.icu">编程导航知识星球</a>
 */
public enum JudgeInfoMessageEnum {

    COMPILE_ERROR("编译错误", "compile_error"),

    RUNTIME_ERROR("运行错误", "runtime_error"),

    TIME_LIMIT_EXCEEDED("时间超限", "time_limit_exceeded"),

    MEMORY_LIMIT_EXCEEDED("内存超限", "memory_limit_exceeded"),

    WRONG_ANSWER("答案错误", "wrong_answer"),

    WAITING("等待中", "Waiting"),

    PRESENTATION_ERROR("格式错误", "presentation_error"),

    OUTPUT_LIMIT_EXCEEDED("输出超限", "output_limit_exceeded"),

    DANGEROUS_OPERATION("危险操作", "dangerous_operation"),

    SYSTEM_ERROR("系统错误", "system_error"),

    LANGUAGE_NOT_SUPPORTED("语言不支持", "language_not_supported"),

    ACCEPTED("通过", "accepted");

    private final String text;

    private final String value;

    JudgeInfoMessageEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 获取值列表
     *
     * @return
     */
    public static List<String> getValues() {
        return Arrays.stream(values()).map(item -> item.value).collect(Collectors.toList());
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value
     * @return
     */
    public static JudgeInfoMessageEnum getEnumByValue(String value) {
        if (ObjectUtils.isEmpty(value)) {
            return null;
        }
        for (JudgeInfoMessageEnum anEnum : JudgeInfoMessageEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }

    public String getValue() {
        return value;
    }

    public String getText() {
        return text;
    }
}

