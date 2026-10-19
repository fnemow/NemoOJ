package com.nemo.OJbackendjudgeservice.judge;


import com.nemo.OJbackendmodel.model.entity.QuestionSubmit;

/**
 * 判题服务
 * 执行代码
 */
public interface JudgeService {

    /**
     * 判题接口
     * @param submitId 提交信息id
     * @return
     */
    QuestionSubmit doJudge(long submitId);
}
