package com.nemo.OJbackendjudgeservice.judge;

import com.nemo.OJbackendcommon.common.ErrorCode;
import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendjudgeservice.judge.codesandbox.CodeSandboxFactory;
import com.nemo.OJbackendjudgeservice.judge.mode.AcmJudgeModeAdapter;
import com.nemo.OJbackendjudgeservice.judge.mode.FunctionJudgeModeAdapter;
import com.nemo.OJbackendmodel.model.codesandbox.ExecuteCodeRequestV2;
import com.nemo.OJbackendmodel.model.dto.question.TryRunExecutionRequest;
import com.nemo.OJbackendmodel.model.entity.QuestionSubmit;
import com.nemo.OJbackendmodel.model.execution.ExecutionCaseInput;
import com.nemo.OJbackendmodel.model.execution.ResourceLimit;
import com.nemo.OJbackendmodel.model.vo.TryRunResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.List;

/** Uses the existing disposable sandbox and trusted harness; does not write submission tables. */
@Service
public class TryRunService {
    private final AcmJudgeModeAdapter acm;
    private final FunctionJudgeModeAdapter function;
    private final CodeSandboxFactory sandboxes;
    @Value("${judge.codesandbox.type}") private String sandboxType;
    public TryRunService(AcmJudgeModeAdapter acm, FunctionJudgeModeAdapter function, CodeSandboxFactory sandboxes) {
        this.acm=acm;this.function=function;this.sandboxes=sandboxes;
    }
    public TryRunResult run(TryRunExecutionRequest request) {
        if(request==null || request.getQuestion()==null) throw new BusinessException(ErrorCode.PARAMS_ERROR);
        var submit=new QuestionSubmit();submit.setLanguage(request.getLanguage());submit.setCode(request.getCode());
        boolean core="FUNCTION".equals(request.getExecutionMode());
        var plan=(core?function:acm).buildExecutionPlan(request.getQuestion(),submit);
        // Only the selected public/custom input reaches the sandbox.
        plan.setCases(List.of(new ExecutionCaseInput(0,request.getInput())));
        plan.setRuntimeId(request.getRuntimeId());
        var limits=plan.getLimits();
        plan.setLimits(ResourceLimit.builder().timeLimitMs(Math.min(limits.getTimeLimitMs()==null?2000L:limits.getTimeLimitMs(),2000L))
                .memoryLimitKB(Math.min(limits.getMemoryLimitKB()==null?262144L:limits.getMemoryLimitKB(),262144L))
                .outputLimitBytes(65536L).build());
        var result=new TryRunResult();result.setOutput("");
        com.nemo.OJbackendmodel.model.codesandbox.ExecuteCodeResponseV2 response=null;
        var sandbox=sandboxes.newInstance(sandboxType);
        for(int attempt=0;attempt<3;attempt++) {
            try {
                response=sandbox.executeCodeV2(ExecuteCodeRequestV2.builder().submissionId(0L).executionPlan(plan).build());break;
            } catch(BusinessException error) {
                // HTTP 503 means the sandbox rejected the request before acquiring a task permit.
                if(!error.getMessage().endsWith("status=503") || attempt==2) {
                    result.setVerdict("System Error");result.setDiagnostic("沙箱当前繁忙或暂不可用，请稍后试运行");return result;
                }
                try {Thread.sleep(500L*(attempt+1));}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();result.setVerdict("System Error");result.setDiagnostic("试运行已中断");return result;}
            }
        }
        if(response==null || response.getSystemMessage()!=null) {
            result.setVerdict("System Error");result.setDiagnostic("试运行服务暂不可用，请稍后重试");return result;
        }
        if(response.getCompileMessage()!=null) {
            result.setVerdict("Compile Error");result.setDiagnostic(UserDiagnosticSanitizer.sanitize(response.getCompileMessage()));return result;
        }
        if(response.getCases()==null || response.getCases().size()!=1) {
            result.setVerdict("System Error");result.setDiagnostic("试运行未返回完整结果");return result;
        }
        var item=response.getCases().get(0);
        int status=item.getStatus()==null?-1:item.getStatus();
        result.setVerdict(switch(status){case 0->"Completed";case 1->"Compile Error";case 2->"Runtime Error";
            case 3->"Time Limit Exceeded";case 4->"Memory Limit Exceeded";case 5->"Output Limit Exceeded";default->"System Error";});
        result.setExecutedTime(item.getTime());result.setExecutedMemory(item.getMemory());result.setExitCode(item.getExitCode());
        result.setOutput(item.getStdout()==null?"":item.getStdout());
        result.setDiagnostic(status<0?"试运行服务暂不可用":UserDiagnosticSanitizer.sanitize(item.getStderr()));
        if(core && status==0) {
            try {
                var root=new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getOutput());
                if(!"OK".equals(root.path("status").asText())) throw new IllegalArgumentException();
                result.setOutput(root.path("actual").toString());
            } catch(Exception error) {result.setVerdict("Runtime Error");result.setDiagnostic("核心代码未返回有效结果，请检查函数签名和输入参数");}
        }
        return result;
    }
}
