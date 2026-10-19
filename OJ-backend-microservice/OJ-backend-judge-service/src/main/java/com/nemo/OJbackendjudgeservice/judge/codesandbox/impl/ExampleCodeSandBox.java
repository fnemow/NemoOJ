package com.nemo.OJbackendjudgeservice.judge.codesandbox.impl;

import com.nemo.OJbackendjudgeservice.judge.codesandbox.CodeSandBox;
import com.nemo.OJbackendmodel.model.codesandbox.ExecuteCodeRequest;
import com.nemo.OJbackendmodel.model.codesandbox.ExecuteCodeResponse;
import com.nemo.OJbackendmodel.model.codesandbox.JudgeInfo;
import com.nemo.OJbackendmodel.model.enums.JudgeInfoMessageEnum;
import com.nemo.OJbackendmodel.model.enums.QuestionSubmitStatusEnum;

import java.util.List;

/**
 * 跑通业务流程（不是具体实现）
 */
public class ExampleCodeSandBox implements CodeSandBox {

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        List<String> inputList = executeCodeRequest.getInputList();
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        executeCodeResponse.setOutputList(inputList);
        executeCodeResponse.setMessage("测试执行成功");
        executeCodeResponse.setStatus(QuestionSubmitStatusEnum.SUCCEED.getValue());
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setMessage(JudgeInfoMessageEnum.ACCEPTED.getText());
        judgeInfo.setExecutedMemory(100L);
        judgeInfo.setExecutedTime(100L);
        executeCodeResponse.setJudgeInfo(judgeInfo);
        return executeCodeResponse;
    }
}
