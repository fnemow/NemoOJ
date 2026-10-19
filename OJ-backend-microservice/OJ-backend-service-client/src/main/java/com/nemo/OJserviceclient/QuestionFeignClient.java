package com.nemo.OJserviceclient;

import com.nemo.OJbackendmodel.model.entity.Question;
import com.nemo.OJbackendmodel.model.entity.QuestionSubmit;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 题目服务内部接口。
 * <p>
 * 提交状态的写操作全部改为**携带执行令牌**的形式（T06）：
 * 只有当前持有租约的执行者才能改动状态，旧执行者的迟到写回会被拒绝。
 * 原先不带令牌的写接口已移除，避免留下绕过令牌校验的旁路。
 */
@FeignClient(name = "OJ-backend-question-service", path = "/api/question/inner")
public interface QuestionFeignClient {
    @GetMapping("/private-validation/checkpoint")
    com.nemo.OJbackendmodel.model.dto.question.PrivateValidationCheckpoint getValidationCheckpoint(
            @RequestParam("validationId") String validationId,@RequestParam("executionHash") String executionHash);

    @PostMapping("/private-validation/checkpoint")
    boolean saveValidationCheckpoint(@RequestBody com.nemo.OJbackendmodel.model.dto.question.PrivateValidationCheckpoint checkpoint);

    /**
     * 根据 id 获取题目（判题使用的完整数据）
     */
    @GetMapping("/get/id")
    Question getQuestionById(@RequestParam("questionId") long questionId);

    /**
     * 按**版本快照**取题（判题唯一依据）。
     * <p>
     * 判题必须使用提交时绑定的版本，不能用"当前题目"——否则提交后改题会导致
     * 历史提交按新数据重判，结果与当初不一致且无法复现。
     *
     * @return 该版本的快照；版本不存在返回 null（历史提交，无法精确复现）
     */
    @GetMapping("/question_version/get")
    Question getQuestionVersion(@RequestParam("questionId") long questionId,
                                @RequestParam("version") int version);

    @GetMapping("/private_problem/version")
    Question getPrivateProblemVersion(@RequestParam("versionId") String versionId,
                                      @RequestParam("ownerUserId") long ownerUserId);

    /**
     * 根据 id 获取提交记录
     */
    @GetMapping("/question_submit/get/id")
    QuestionSubmit getQuestionSubmitById(@RequestParam("questionSubmitId") long questionSubmitId);

    /**
     * 原子抢占待判题提交：仅当状态为 WAITING 时成功，并生成新的执行令牌。
     *
     * @return 新的执行令牌；返回 null 表示抢占失败（已被他人抢占、或已是终态）
     */
    @PostMapping("/question_submit/claim")
    String claimForJudging(@RequestParam("submitId") long submitId,
                           @RequestParam("leaseSeconds") int leaseSeconds);

    /**
     * 续租：执行期间定期调用，避免长任务被误判为失联而被回收。
     *
     * @return 是否续租成功；false 表示租约已被回收，本执行不应再写回结果
     */
    @PostMapping("/question_submit/renew-lease")
    boolean renewLease(@RequestParam("submitId") long submitId,
                       @RequestParam("token") String token,
                       @RequestParam("leaseSeconds") int leaseSeconds);

    /**
     * 写回判题结果（成功/失败终态）。
     * 仅当提交仍处于 UNDER_JUDGING 且令牌匹配时生效。
     */
    @PostMapping("/question_submit/finish")
    boolean finishWithToken(@RequestBody QuestionSubmit questionSubmit,
                            @RequestParam("token") String token);

    /**
     * 把仍处于"待判题"的提交标记为系统错误终态（死信处理用）。
     * <p>
     * 刻意不接受令牌：死信路径没有令牌。安全性由"仅当状态为 WAITING 时生效"保证——
     * 已完成的提交不会被覆盖，正在执行（判题中）的提交也不会被误杀，
     * 后者由租约回收器在租约过期后接管。
     *
     * @return 是否更新成功
     */
    @PostMapping("/question_submit/mark-failed")
    boolean markFailedIfWaiting(@RequestParam("submitId") long submitId,
                                @RequestParam("reason") String reason);

    /**
     * 执行异常时回退：把提交退回 WAITING 并记录原因，等待重新投递。
     * 仅当仍处于 UNDER_JUDGING 且令牌匹配时生效。
     */
    @PostMapping("/question_submit/revert")
    boolean revertToWaitingWithToken(@RequestParam("submitId") long submitId,
                                     @RequestParam("token") String token,
                                     @RequestParam("reason") String reason);
}
