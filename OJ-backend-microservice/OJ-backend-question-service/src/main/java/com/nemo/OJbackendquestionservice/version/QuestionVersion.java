package com.nemo.OJbackendquestionservice.version;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.nemo.OJbackendmodel.model.dto.question.JudgeCase;
import com.nemo.OJbackendmodel.model.dto.question.JudgeConfig;
import com.nemo.OJbackendmodel.model.dto.question.ProblemSpec;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * 题目版本快照（不可变）。
 * <p>
 * 一行代表"某次发布时的完整判题依据"。写入后**不得修改**：
 * 提交绑定到具体版本，判题只读这一行，因此改动它会篡改历史提交的判题结果。
 */
// autoResultMap 必须为 true：@TableField(typeHandler = JacksonTypeHandler.class)
// 只在自动生成的 ResultMap 中生效。缺了它，SELECT 回来的 judgeCase / judgeConfig /
// problemSpec 会是 null（而不是报错），判题拿到的就是空用例——极难排查。
@TableName(value = "question_version", autoResultMap = true)
@Data
public class QuestionVersion implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long questionId;

    private Integer version;

    private String title;

    private String content;

    private String tags;

    /** 参考答案：仅在作者/管理员的授权路径下可见 */
    private String answer;

    /**
     * 判题用例（含隐藏用例）：不得返回给普通用户。
     * 与 Question 实体使用同一套类型处理器，保证快照与原始数据序列化方式一致。
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<JudgeCase> judgeCase;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private JudgeConfig judgeConfig;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private ProblemSpec problemSpec;

    private Integer harnessVersion;

    private Long publishedBy;

    private Date createTime;

    private static final long serialVersionUID = 1L;
}
