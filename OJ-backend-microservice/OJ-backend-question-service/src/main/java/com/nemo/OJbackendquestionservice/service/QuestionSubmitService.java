package com.nemo.OJbackendquestionservice.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.nemo.OJbackendmodel.model.dto.questionSubmit.QuestionSubmitAddRequest;
import com.nemo.OJbackendmodel.model.dto.questionSubmit.QuestionSubmitQueryRequest;
import com.nemo.OJbackendmodel.model.entity.QuestionSubmit;
import com.nemo.OJbackendmodel.model.entity.User;
import com.nemo.OJbackendmodel.model.vo.QuestionSubmitVO;


/**
* @author nemo
* @description 针对表【question_submit(题目提交)】的数据库操作Service
* @createDate 2026-04-21 19:33:50
*/
public interface QuestionSubmitService extends IService<QuestionSubmit> {

    long doQuestionSubmit(QuestionSubmitAddRequest questionSubmitAddRequest, User loginUser);

    long doPrivateQuestionSubmit(String privateProblemId, String privateVersionId,
                                 QuestionSubmitAddRequest request, User loginUser);

    QueryWrapper<QuestionSubmit> getQueryWrapper(QuestionSubmitQueryRequest questionSubmitQueryRequest);

    QuestionSubmitVO getQuestionSubmitVO(QuestionSubmit questionSubmit, User loginUser);

    Page<QuestionSubmitVO> getQuestionSubmitVOPage(Page<QuestionSubmit> questionSubmitPage, User loginUser);
}
