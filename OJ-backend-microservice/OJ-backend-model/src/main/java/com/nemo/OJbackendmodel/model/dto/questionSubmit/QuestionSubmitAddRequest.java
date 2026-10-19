package com.nemo.OJbackendmodel.model.dto.questionSubmit;

import lombok.Data;

import java.io.Serializable;

/**
 * 帖子点赞请求
 *
 * @author <a href="https://github.com/liyupi">程序员鱼皮</a>
 * @from <a href="https://yupi.icu">编程导航知识星球</a>
 */
@Data
public class QuestionSubmitAddRequest implements Serializable {

    /**
     * 编程语言
     */
    private String language;

    /** ACM（完整程序）或 FUNCTION（核心代码）。 */
    private String executionMode;

    /**
     * 用户代码
     */
    private String code;

    /**
     * 题目 id
     */
    private Long questionId;

    private Integer problemVersion;

    /** UUID generated once for an immutable submission snapshot. */
    private String clientRequestId;

    private static final long serialVersionUID = 1L;
}
