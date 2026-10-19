package com.nemo.OJbackendmodel.model.vo;

import cn.hutool.json.JSONUtil;

import com.nemo.OJbackendmodel.model.dto.question.ProblemSpec;
import com.nemo.OJbackendmodel.model.dto.question.JudgeConfig;
import com.nemo.OJbackendmodel.model.entity.Question;
import lombok.Data;
import org.springframework.beans.BeanUtils;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * 帖子视图
 *
 * @author <a href="https://github.com/liyupi">程序员鱼皮</a>
 * @from <a href="https://yupi.icu">编程导航知识星球</a>
 */
@Data
public class QuestionVO implements Serializable {

    /**
     * id
     */

    private Long id;

    /** Stable public natural-number identifier. */
    private Long problemNo;

    /**
     * 标题
     */
    private String title;

    /**
     * 内容
     */
    private String content;

    /**
     * 标签列表（json 数组）
     */
    private List<String> tagList;
    
    /**
     * 题目提交数
     */
    private Integer submitNum;

    /**
     * 题目通过数
     */
    private Integer acceptedNum;

    /**
     * 点赞数
     */
    private Integer thumbNum;

    /**
     * 收藏数
     */
    private Integer favourNum;

    /**
     * 创建用户 id
     */
    private Long userId;

    private UserVO user;

    /**
     * 判题规格（核心代码模式的函数签名等；null 表示 ACM 模式）
     */
    private ProblemSpec problemSpec;

    /** Public resource limits; never contains hidden test data. */
    private JudgeConfig judgeConfig;

    /** Explicitly public examples only. */
    private List<QuestionSampleVO> samples;
    private List<QuestionSampleVO> functionSamples;

    /**
     * 题目版本号
     */
    private Integer problemVersion;

    /**
     * 是否已发布
     */
    private Boolean isPublished;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    /**
     * 包装类转对象
     *
     * @param questionVO
     * @return
     */
    public static Question voToObj(QuestionVO questionVO) {
        if (questionVO == null) {
            return null;
        }
        Question question = new Question();
        BeanUtils.copyProperties(questionVO, question);
        List<String> tagList = questionVO.getTagList();
        question.setTags(JSONUtil.toJsonStr(tagList));
        return question;
    }

    /**
     * 对象转包装类
     *
     * @param question
     * @return
     */
    public static QuestionVO objToVo(Question question) {
        if (question == null) {
            return null;
        }
        QuestionVO questionVO = new QuestionVO();
        BeanUtils.copyProperties(question, questionVO);
        // The function signature is public, but problemSpec.judgeCases can
        // contain hidden inputs and expected outputs. Never reuse the entity's
        // mutable spec in a public VO.
        ProblemSpec sourceSpec = question.getProblemSpec();
        if (sourceSpec != null) {
            questionVO.setProblemSpec(new ProblemSpec(
                    sourceSpec.getMode(), sourceSpec.getClassName(), sourceSpec.getMethodName(),
                    sourceSpec.getParameters(), sourceSpec.getReturnType(), sourceSpec.getResultSource(),
                    sourceSpec.getComparator(), sourceSpec.getHarnessVersion()));
            if(sourceSpec.getDataStructures()!=null)questionVO.getProblemSpec().setDataStructures(sourceSpec.getDataStructures().stream().map(d->{
                var copy=new com.nemo.OJbackendmodel.model.dto.question.CustomStructureSpec();copy.setName(d.getName());copy.setFields(d.getFields().stream().map(f->new com.nemo.OJbackendmodel.model.dto.question.ParameterSpec(f.getName(),f.getType())).toList());return copy;
            }).toList());
        }
        questionVO.setTagList(JSONUtil.toList(question.getTags(), String.class));
        return questionVO;
    }
}
