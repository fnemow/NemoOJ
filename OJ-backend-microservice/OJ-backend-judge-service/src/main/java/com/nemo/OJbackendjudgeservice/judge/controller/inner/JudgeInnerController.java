package com.nemo.OJbackendjudgeservice.judge.controller.inner;

import com.nemo.OJbackendjudgeservice.judge.JudgeService;
import com.nemo.OJbackendmodel.model.entity.QuestionSubmit;
import com.nemo.OJserviceclient.JudgeFeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

@RestController
@RequestMapping("/inner")
public class JudgeInnerController implements JudgeFeignClient {

    @Resource
    private JudgeService judgeService;
    @Resource
    private com.nemo.OJbackendjudgeservice.judge.TryRunService tryRunService;
    @PostMapping("/try-run")
    public com.nemo.OJbackendmodel.model.vo.TryRunResult tryRun(@org.springframework.web.bind.annotation.RequestBody com.nemo.OJbackendmodel.model.dto.question.TryRunExecutionRequest request) {
        return tryRunService.run(request);
    }
    @Resource
    private com.nemo.OJbackendjudgeservice.judge.RuntimeCapabilitiesClient runtimeCapabilities;
    @Resource
    private com.nemo.OJbackendjudgeservice.judge.PrivateProblemValidationService privateProblemValidator;
    @org.springframework.web.bind.annotation.GetMapping("/capabilities")
    public java.util.List<java.util.Map<String,Object>> capabilities() { return runtimeCapabilities.snapshot(); }

    @PostMapping("/private-problem/validate")
    public java.util.Map<String,Object> validatePrivateProblem(
            @org.springframework.web.bind.annotation.RequestBody com.nemo.OJbackendmodel.model.dto.question.PrivateProblemValidationRequest request) {
        return privateProblemValidator.validate(request);
    }

    /**
     * 判题接口
     * @param submitId 提交信息id
     * @return
     */
    @PostMapping("/do")
    public QuestionSubmit doJudge(@RequestParam("submitId")long submitId) {
        return judgeService.doJudge(submitId);
    }
}
