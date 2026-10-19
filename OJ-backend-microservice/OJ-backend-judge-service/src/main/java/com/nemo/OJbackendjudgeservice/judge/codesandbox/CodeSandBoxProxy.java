package com.nemo.OJbackendjudgeservice.judge.codesandbox;


import com.nemo.OJbackendmodel.model.codesandbox.ExecuteCodeRequest;
import com.nemo.OJbackendmodel.model.codesandbox.ExecuteCodeRequestV2;
import com.nemo.OJbackendmodel.model.codesandbox.ExecuteCodeResponse;
import com.nemo.OJbackendmodel.model.codesandbox.ExecuteCodeResponseV2;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CodeSandBoxProxy implements CodeSandBox {

    private final CodeSandBox codeSandBox;

    public CodeSandBoxProxy(CodeSandBox codeSandBox) {
        this.codeSandBox = codeSandBox;
    }

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        log.info("代码沙箱请求信息: " + executeCodeRequest.toString());
        ExecuteCodeResponse executeCodeResponse = codeSandBox.executeCode(executeCodeRequest);
        log.info("代码沙箱响应信息: " + executeCodeResponse.toString());
        return executeCodeResponse;
    }

    @Override
    public ExecuteCodeResponseV2 executeCodeV2(ExecuteCodeRequestV2 request) {
        log.info("V2 沙箱请求: submissionId={}, language={}, mode={}",
                request.getSubmissionId(),
                request.getExecutionPlan() != null ? request.getExecutionPlan().getLanguage() : "?",
                request.getExecutionPlan() != null ? request.getExecutionPlan().getMode() : "?");
        ExecuteCodeResponseV2 response = codeSandBox.executeCodeV2(request);
        log.info("V2 沙箱响应: overallStatus={}, cases={}",
                response.getOverallStatus(),
                response.getCases() != null ? response.getCases().size() : 0);
        return response;
    }
}
