package com.nemo.OJbackendjudgeservice.judge.mode;

import com.nemo.OJbackendmodel.model.codesandbox.ExecuteCodeResponseV2;
import com.nemo.OJbackendmodel.model.entity.Question;
import com.nemo.OJbackendmodel.model.entity.QuestionSubmit;
import com.nemo.OJbackendmodel.model.execution.ExecutionPlan;

/**
 * 判题模式适配器：将题目的执行模式转换为沙箱可执行的 ExecutionPlan。
 */
public interface JudgeModeAdapter {

    /**
     * 根据题目和提交构建执行计划
     */
    ExecutionPlan buildExecutionPlan(Question question, QuestionSubmit submit);

    /**
     * 从沙箱 V2 响应中提取 JudgeInfo 并判断最终结果。
     * JudgeService 负责写回提交状态。
     *
     * @param question      题目（含期望答案）
     * @param sandboxResp   沙箱 V2 响应
     * @return 判题结果消息（ACCEPTED / WRONG_ANSWER / ...）
     */
    String interpretResult(Question question, ExecuteCodeResponseV2 sandboxResp);
}
