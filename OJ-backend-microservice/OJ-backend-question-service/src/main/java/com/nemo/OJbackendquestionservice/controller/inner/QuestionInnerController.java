package com.nemo.OJbackendquestionservice.controller.inner;

import cn.hutool.json.JSONUtil;
import com.nemo.OJbackendmodel.model.entity.Question;
import com.nemo.OJbackendmodel.model.entity.QuestionSubmit;
import com.nemo.OJbackendquestionservice.service.JudgeLeaseService;
import com.nemo.OJbackendquestionservice.service.QuestionService;
import com.nemo.OJserviceclient.QuestionFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 题目服务内部接口。
 * <p>
 * 服务凭据由 InternalAuthFilter 统一校验（T04），这里的重点是**执行令牌**（T06）：
 * 提交状态的每次改动都要求调用方出示当前令牌，
 * 因此"谁在执行这次判题"是可验证的，旧执行的迟到写回会被拒绝。
 */
@Slf4j
@RestController
@RequestMapping("/inner")
public class QuestionInnerController implements QuestionFeignClient {

    @Resource
    private QuestionService questionService;

    @Resource
    private JudgeLeaseService judgeLeaseService;

    @Resource
    private com.nemo.OJbackendquestionservice.version.QuestionVersionService questionVersionService;

    @Resource
    private JdbcTemplate jdbcTemplate;
    @Resource
    private com.nemo.OJbackendquestionservice.service.PrivateValidationCheckpointService validationCheckpoints;

    @Override
    @GetMapping("/private-validation/checkpoint")
    public com.nemo.OJbackendmodel.model.dto.question.PrivateValidationCheckpoint getValidationCheckpoint(@RequestParam("validationId") String validationId,@RequestParam("executionHash") String executionHash){
        return validationCheckpoints.load(validationId,executionHash);
    }
    @Override
    @PostMapping("/private-validation/checkpoint")
    public boolean saveValidationCheckpoint(@RequestBody com.nemo.OJbackendmodel.model.dto.question.PrivateValidationCheckpoint checkpoint){validationCheckpoints.save(checkpoint);return true;}

    @Override
    @GetMapping("/get/id")
    public Question getQuestionById(@RequestParam("questionId") long questionId) {
        return questionService.getById(questionId);
    }

    @Override
    @GetMapping("/question_version/get")
    public Question getQuestionVersion(@RequestParam("questionId") long questionId,
                                       @RequestParam("version") int version) {
        return questionVersionService.asQuestion(questionId, version);
    }

    @Override
    @GetMapping("/private_problem/version")
    public Question getPrivateProblemVersion(@RequestParam("versionId") String versionId,
                                             @RequestParam("ownerUserId") long ownerUserId) {
        return jdbcTemplate.query("SELECT p.title,v.versionNumber,v.contentMarkdown,v.problemSpecJson, "
                        + "v.judgeCaseJson,v.judgeConfigJson FROM private_problem p "
                        + "JOIN private_problem_version v ON v.privateProblemId=p.id "
                        + "WHERE p.ownerUserId=? AND p.status='READY' AND v.id=? AND v.status='READY'",
                rs -> {
                    if (!rs.next()) return null;
                    Question question = new Question();
                    question.setTitle(rs.getString("title")); question.setContent(rs.getString("contentMarkdown"));
                    question.setProblemVersion(rs.getInt("versionNumber")); question.setIsPublished(true);
                    String rawSpec=rs.getString("problemSpecJson");
                    if(rawSpec!=null && !"null".equalsIgnoreCase(rawSpec.trim()) && !"{}".equals(rawSpec.trim()))
                        question.setProblemSpec(JSONUtil.toBean(rawSpec,com.nemo.OJbackendmodel.model.dto.question.ProblemSpec.class));
                    question.setJudgeCase(JSONUtil.toList(rs.getString("judgeCaseJson"),
                            com.nemo.OJbackendmodel.model.dto.question.JudgeCase.class));
                    question.setJudgeConfig(JSONUtil.toBean(rs.getString("judgeConfigJson"),
                            com.nemo.OJbackendmodel.model.dto.question.JudgeConfig.class));
                    return question;
                }, ownerUserId, versionId);
    }

    @Override
    @GetMapping("/question_submit/get/id")
    public QuestionSubmit getQuestionSubmitById(@RequestParam("questionSubmitId") long questionSubmitId) {
        return judgeLeaseService.getById(questionSubmitId);
    }

    @Override
    @PostMapping("/question_submit/claim")
    public String claimForJudging(@RequestParam("submitId") long submitId,
                                  @RequestParam("leaseSeconds") int leaseSeconds) {
        return judgeLeaseService.claim(submitId, leaseSeconds);
    }

    @Override
    @PostMapping("/question_submit/renew-lease")
    public boolean renewLease(@RequestParam("submitId") long submitId,
                              @RequestParam("token") String token,
                              @RequestParam("leaseSeconds") int leaseSeconds) {
        return judgeLeaseService.renewLease(submitId, token, leaseSeconds);
    }

    @Override
    @PostMapping("/question_submit/finish")
    public boolean finishWithToken(@RequestBody QuestionSubmit questionSubmit,
                                   @RequestParam("token") String token) {
        if (questionSubmit == null || questionSubmit.getId() == null) {
            return false;
        }
        String judgeInfoJson = questionSubmit.getJudgeInfo() == null
                ? null : JSONUtil.toJsonStr(questionSubmit.getJudgeInfo());
        return judgeLeaseService.finish(questionSubmit.getId(), token,
                questionSubmit.getStatus(), judgeInfoJson);
    }

    @Override
    @PostMapping("/question_submit/mark-failed")
    public boolean markFailedIfWaiting(@RequestParam("submitId") long submitId,
                                       @RequestParam("reason") String reason) {
        return judgeLeaseService.markFailedIfWaiting(submitId, reason);
    }

    @Override
    @PostMapping("/question_submit/revert")
    public boolean revertToWaitingWithToken(@RequestParam("submitId") long submitId,
                                            @RequestParam("token") String token,
                                            @RequestParam("reason") String reason) {
        return judgeLeaseService.revertToWaiting(submitId, token, reason);
    }
}
