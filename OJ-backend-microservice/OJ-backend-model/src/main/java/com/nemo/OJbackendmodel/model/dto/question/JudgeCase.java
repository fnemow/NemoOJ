package com.nemo.OJbackendmodel.model.dto.question;


import lombok.Data;

@Data
public class JudgeCase {
    /**
     * 输入用例
     */
    private String input;

    /**
     * 输出用例
     */
    private String output;

    /**
     * 是否为隐藏用例（隐藏用例的输入和答案不会返回给普通用户）
     */
    private Boolean isHidden;
}
