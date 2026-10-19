package com.nemo.OJbackendquestionservice.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.nemo.OJbackendmodel.model.dto.question.QuestionQueryRequest;
import com.nemo.OJbackendmodel.model.entity.Question;
import com.nemo.OJbackendmodel.model.vo.QuestionVO;


import jakarta.servlet.http.HttpServletRequest;

/**
* @author nemo
* @description 针对表【question(题目)】的数据库操作Service
* @createDate 2026-04-21 19:33:50
*/
public interface QuestionService extends IService<Question> {
    /**
     * 校验
     *
     * @param question
     * @param add
     */
    void validQuestion(Question question, boolean add);

    /**
     * 获取查询条件
     *
     * @param questionQueryRequest
     * @return
     */
    QueryWrapper<Question> getQueryWrapper(QuestionQueryRequest questionQueryRequest);

//    /**
//     * 从 ES 查询
//     *
//     * @param questionQueryRequest
//     * @return
//     */
//    Page<Question> searchFromEs(QuestionQueryRequest questionQueryRequest);

    /**
     * 获取帖子封装
     *
     * @param question
     * @param request
     * @return
     */
    QuestionVO getQuestionVO(Question question, HttpServletRequest request);

    /**
     * 当前访问者是否有权管理该题目（作者本人或管理员）。
     * 草稿的可见性与"能否提交"都以它为准。
     */
    boolean canManage(Question question, HttpServletRequest request);

    /**
     * 发布题目：生成一个不可变版本快照并把题目标记为已发布。
     *
     * @return 新版本号
     */
    int publishQuestion(Question question, Long publisherId);

    /**
     * 分页获取帖子封装
     *
     * @param questionPage
     * @param request
     * @return
     */
    Page<QuestionVO> getQuestionVOPage(Page<Question> questionPage, HttpServletRequest request);
}
