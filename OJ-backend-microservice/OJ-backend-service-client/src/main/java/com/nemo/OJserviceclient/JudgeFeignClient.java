package com.nemo.OJserviceclient;


import com.nemo.OJbackendmodel.model.entity.QuestionSubmit;
import com.nemo.OJbackendmodel.model.dto.question.PrivateProblemValidationRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 判题服务
 * 执行代码
 */
@FeignClient(name = "OJ-backend-judge-service", path = "/api/judge/inner")
public interface JudgeFeignClient {
    @PostMapping("/try-run")
    com.nemo.OJbackendmodel.model.vo.TryRunResult tryRun(@org.springframework.web.bind.annotation.RequestBody com.nemo.OJbackendmodel.model.dto.question.TryRunExecutionRequest request);
    @org.springframework.web.bind.annotation.GetMapping("/capabilities")
    java.util.List<java.util.Map<String,Object>> capabilities();

    /**
     * 判题接口
     * @param submitId 提交信息id
     * @return
     */
    @PostMapping("/do")
    QuestionSubmit doJudge(@RequestParam("submitId")long submitId);

    @PostMapping("/private-problem/validate")
    java.util.Map<String,Object> validatePrivateProblem(@org.springframework.web.bind.annotation.RequestBody PrivateProblemValidationRequest request);
}
