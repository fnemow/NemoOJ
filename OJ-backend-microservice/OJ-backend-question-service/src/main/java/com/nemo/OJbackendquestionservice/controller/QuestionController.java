package com.nemo.OJbackendquestionservice.controller;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import com.nemo.OJbackendcommon.annotation.AuthCheck;
import com.nemo.OJbackendcommon.common.BaseResponse;
import com.nemo.OJbackendcommon.common.DeleteRequest;
import com.nemo.OJbackendcommon.common.ErrorCode;
import com.nemo.OJbackendcommon.common.ResultUtils;
import com.nemo.OJbackendcommon.constant.UserConstant;
import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendcommon.exception.ThrowUtils;
import com.nemo.OJbackendmodel.model.dto.question.QuestionAddRequest;
import com.nemo.OJbackendmodel.model.dto.question.QuestionEditRequest;
import com.nemo.OJbackendmodel.model.dto.question.QuestionQueryRequest;
import com.nemo.OJbackendmodel.model.dto.question.QuestionUpdateRequest;
import com.nemo.OJbackendmodel.model.dto.question.AdminVersionRequest;
import com.nemo.OJbackendmodel.model.dto.question.AdminProblemQueryRequest;
import com.nemo.OJbackendmodel.model.dto.question.ProblemLifecycleRequest;
import com.nemo.OJbackendmodel.model.entity.ProblemIdentity;
import com.nemo.OJbackendmodel.model.entity.Question;
import com.nemo.OJbackendmodel.model.entity.User;
import com.nemo.OJbackendmodel.model.dto.questionSubmit.QuestionSubmitAddRequest;
import com.nemo.OJbackendmodel.model.dto.questionSubmit.QuestionSubmitQueryRequest;
import com.nemo.OJbackendmodel.model.entity.QuestionSubmit;
import com.nemo.OJbackendmodel.model.vo.QuestionSubmitVO;
import com.nemo.OJbackendmodel.model.vo.QuestionVO;
import com.nemo.OJbackendmodel.model.vo.AdminProblemSummaryVO;

import com.nemo.OJbackendquestionservice.service.QuestionService;
import com.nemo.OJbackendquestionservice.service.QuestionSubmitService;
import com.nemo.OJbackendquestionservice.service.ProblemLifecycleService;
import com.nemo.OJserviceclient.UserFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 题目接口
 *
 * @author <a href="https://github.com/liyupi">程序员鱼皮</a>
 * @from <a href="https://yupi.icu">编程导航知识星球</a>
 */
@RestController
@RequestMapping("/")
@Slf4j
public class QuestionController {

    @Resource
    private QuestionService questionService;
    @Resource
    private com.nemo.OJbackendquestionservice.service.LanguageCapabilityService languageCapabilities;
    @GetMapping("/languages")
    public BaseResponse<Map<String,Object>> languages(@RequestParam("questionId") Long questionId) {
        Question question=findPublishedQuestion(questionId);
        if(question==null || !Boolean.TRUE.equals(question.getIsPublished())) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        return ResultUtils.success(languageCapabilities.problemCapabilities(question));
    }

    @GetMapping("/code_template")
    public BaseResponse<Map<String,Object>> codeTemplate(@RequestParam("questionId") Long questionId,
            @RequestParam("language") String language,@RequestParam("problemVersion") Integer problemVersion,
            @RequestParam(value="executionMode",required=false) String executionMode) {
        Question question=findPublishedQuestion(questionId);
        if(question==null || !Boolean.TRUE.equals(question.getIsPublished())) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        return ResultUtils.success(languageCapabilities.codeTemplate(question,language,problemVersion,executionMode));
    }

    @Resource
    private UserFeignClient userFeignClient;

    @Resource
    private QuestionSubmitService questionSubmitService;

    @Resource
    private ProblemLifecycleService problemLifecycleService;

    @Resource
    private com.nemo.OJbackendcommon.ratelimit.RateLimitGuard rateLimitGuard;

    @Resource
    private com.nemo.OJbackendquestionservice.progress.SubmissionProgressHub submissionProgressHub;

    // region 增删改查

    /**
     * 创建
     *
     * @param questionAddRequest
     * @param request
     * @return
     */
    @PostMapping("/add")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse<Long> addQuestion(@RequestBody QuestionAddRequest questionAddRequest, HttpServletRequest request) {
        if (questionAddRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Question question = new Question();
        BeanUtils.copyProperties(questionAddRequest, question);
        List<String> tags = questionAddRequest.getTags();
        if (tags != null) {
            question.setTags(JSONUtil.toJsonStr(tags));
        }
        questionService.validQuestion(question, true);
        User loginUser = userFeignClient.getLoginUser(request);
        question.setUserId(loginUser.getId());
        question.setFavourNum(0);
        question.setThumbNum(0);
        question.setSubmitNum(0);
        question.setAcceptedNum(0);
        question.setIsPublished(false);
        question.setProblemVersion(1);
        question.setRowVersion(0L);
        boolean result = questionService.save(question);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        problemLifecycleService.createIdentity(question);
        long newQuestionId = question.getId();
        return ResultUtils.success(newQuestionId);
    }

    /**
     * 删除
     *
     * @param deleteRequest
     * @param request
     * @return
     */
    @PostMapping("/delete")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> deleteQuestion(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User user = userFeignClient.getLoginUser(request);
        long id = deleteRequest.getId();
        // 判断是否存在
        Question oldQuestion = questionService.getById(id);
        ThrowUtils.throwIf(oldQuestion == null, ErrorCode.NOT_FOUND_ERROR);
        if (Boolean.TRUE.equals(oldQuestion.getIsPublished()) || questionSubmitService.count(
                new QueryWrapper<QuestionSubmit>().eq("questionId", id)) > 0) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "只能删除无提交引用的未发布草稿");
        }
        boolean b = questionService.removeById(id);
        return ResultUtils.success(b);
    }

    /**
     * 更新（仅管理员）
     *
     * @param questionUpdateRequest
     * @return
     */
    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateQuestion(@RequestBody QuestionUpdateRequest questionUpdateRequest) {
        if (questionUpdateRequest == null || questionUpdateRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Question question = new Question();
        BeanUtils.copyProperties(questionUpdateRequest, question);
        List<String> tags = questionUpdateRequest.getTags();
        if (tags != null) {
            question.setTags(JSONUtil.toJsonStr(tags));
        }
        // 参数校验
        questionService.validQuestion(question, false);
        long id = questionUpdateRequest.getId();
        // 判断是否存在
        Question oldQuestion = questionService.getById(id);
        ThrowUtils.throwIf(oldQuestion == null, ErrorCode.NOT_FOUND_ERROR);
        Long expected = questionUpdateRequest.getExpectedRowVersion();
        if (expected == null) throw new BusinessException(ErrorCode.PARAMS_ERROR, "缺少 expectedRowVersion");
        boolean result = questionService.update(question, new UpdateWrapper<Question>()
                .eq("id", id).eq("rowVersion", expected).set("rowVersion", expected + 1));
        if (!result) throw new BusinessException(ErrorCode.OPERATION_ERROR, "题目已被其他管理员修改", "EDIT_CONFLICT");
        return ResultUtils.success(result);
    }

    /**
     * 根据 id 获取
     *
     * @param id
     * @return
     */
    @GetMapping("/get/vo")
    public BaseResponse<QuestionVO> getQuestionVOById(long id, HttpServletRequest request) {
        if (id <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Question question = problemLifecycleService.resolveOnline(id);
        return ResultUtils.success(questionService.getQuestionVO(question, request));
    }

    /** Public natural-number route; legacy long-id route remains available. */
    @GetMapping("/get/vo/by-number")
    public BaseResponse<QuestionVO> getQuestionVOByNumber(@RequestParam("problemNo") long problemNo,
                                                            HttpServletRequest request) {
        if (problemNo <= 0) throw new BusinessException(ErrorCode.PARAMS_ERROR);
        Question question = problemLifecycleService.resolveOnline(problemNo);
        return ResultUtils.success(questionService.getQuestionVO(question, request));
    }

    /**
     * 发布题目：把当前题目内容固化为一个不可变版本快照，并标记为已发布。
     * <p>
     * 只有作者本人或管理员可以发布。发布后再次编辑不会影响已生成的版本，
     * 需要重新调用本接口才会产生新版本。
     */
    @PostMapping("/publish")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Integer> publishQuestion(@RequestBody DeleteRequest publishRequest,
                                                 HttpServletRequest request) {
        if (publishRequest == null || publishRequest.getId() == null || publishRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        long questionId = publishRequest.getId();
        Question question = questionService.getById(questionId);
        if (question == null) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        User loginUser = userFeignClient.getLoginUser(request);
        Map<String,Object> published = problemLifecycleService.publish(questionId,
                question.getRowVersion() == null ? 0L : question.getRowVersion(), loginUser.getId());
        int version = ((Number) published.get("problemVersion")).intValue();
        return ResultUtils.success(version);
    }

    /**
     * 分页获取列表（仅管理员）
     *
     * @param questionQueryRequest
     * @return
     */
    @PostMapping("/list/page")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<Question>> listQuestionByPage(@RequestBody QuestionQueryRequest questionQueryRequest) {
        long current = questionQueryRequest.getCurrent();
        long size = questionQueryRequest.getPageSize();
        Page<Question> questionPage = questionService.page(new Page<>(current, size),
                questionService.getQueryWrapper(questionQueryRequest));
        return ResultUtils.success(questionPage);
    }

    /**
     * 分页获取列表（封装类）
     *
     * @param questionQueryRequest
     * @param request
     * @return
     */
    @PostMapping("/list/page/vo")
    public BaseResponse<Page<QuestionVO>> listQuestionVOByPage(@RequestBody QuestionQueryRequest questionQueryRequest,
            HttpServletRequest request) {
        long current = questionQueryRequest.getCurrent();
        long size = questionQueryRequest.getPageSize();
        // 限制爬虫
        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR);
        QueryWrapper<Question> queryWrapper = questionService.getQueryWrapper(questionQueryRequest);
        // 公开列表只返回已发布题目。管理员仍可用 /list/page（需 ADMIN 角色）查看全部。
        // 注意：这里由服务端强制加条件，不接受请求参数控制，避免被绕过。
        queryWrapper.eq("isPublished", true)
                .inSql("id", "SELECT currentQuestionId FROM question_public_number " +
                        "WHERE status='ONLINE' AND currentQuestionId IS NOT NULL");
        Page<Question> questionPage = questionService.page(new Page<>(current, size), queryWrapper);
        return ResultUtils.success(questionService.getQuestionVOPage(questionPage, request));
    }

    /**
     * 分页获取当前用户创建的资源列表
     *
     * @param questionQueryRequest
     * @param request
     * @return
     */
    @PostMapping("/my/list/page/vo")
    public BaseResponse<Page<QuestionVO>> listMyQuestionVOByPage(@RequestBody QuestionQueryRequest questionQueryRequest,
            HttpServletRequest request) {
        if (questionQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userFeignClient.getLoginUser(request);
        questionQueryRequest.setUserId(loginUser.getId());
        long current = questionQueryRequest.getCurrent();
        long size = questionQueryRequest.getPageSize();
        // 限制爬虫
        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR);
        QueryWrapper<Question> queryWrapper = questionService.getQueryWrapper(questionQueryRequest);
        // "我创建的"列表按 userId 过滤，作者需要看到自己的草稿，因此**不**加发布状态条件
        Page<Question> questionPage = questionService.page(new Page<>(current, size), queryWrapper);
        return ResultUtils.success(questionService.getQuestionVOPage(questionPage, request));
    }

    // endregion

//    /**
//     * 分页搜索（从 ES 查询，封装类）
//     *
//     * @param questionQueryRequest
//     * @param request
//     * @return
//     */
//    @PostMapping("/search/page/vo")
//    public BaseResponse<Page<QuestionVO>> searchQuestionVOByPage(@RequestBody QuestionQueryRequest questionQueryRequest,
//            HttpServletRequest request) {
//        long size = questionQueryRequest.getPageSize();
//        // 限制爬虫
//        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR);
//        Page<Question> questionPage = questionService.searchFromEs(questionQueryRequest);
//        return ResultUtils.success(questionService.getQuestionVOPage(questionPage, request));
//    }

    /**
     * 编辑（用户）
     *
     * @param questionEditRequest
     * @param request
     * @return
     */
    @PostMapping("/edit")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> editQuestion(@RequestBody QuestionEditRequest questionEditRequest, HttpServletRequest request) {
        if (questionEditRequest == null || questionEditRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Question question = new Question();
        BeanUtils.copyProperties(questionEditRequest, question);
        List<String> tags = questionEditRequest.getTags();
        if (tags != null) {
            question.setTags(JSONUtil.toJsonStr(tags));
        }
        // 参数校验
        questionService.validQuestion(question, false);
        User loginUser = userFeignClient.getLoginUser(request);
        long id = questionEditRequest.getId();
        // 判断是否存在
        Question oldQuestion = questionService.getById(id);
        ThrowUtils.throwIf(oldQuestion == null, ErrorCode.NOT_FOUND_ERROR);
        Long expected = questionEditRequest.getExpectedRowVersion();
        if(expected==null) throw new BusinessException(ErrorCode.PARAMS_ERROR,"缺少 expectedRowVersion");
        boolean result = questionService.update(question,new UpdateWrapper<Question>()
                .eq("id",id).eq("rowVersion",expected).set("rowVersion",expected+1));
        if(!result) throw new BusinessException(ErrorCode.OPERATION_ERROR,"题目已被其他管理员修改","EDIT_CONFLICT");
        return ResultUtils.success(result);
    }

    @GetMapping("/admin/detail")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Map<String,Object>> adminDetail(@RequestParam("id") Long id) {
        Question question=questionService.getById(id);
        ThrowUtils.throwIf(question==null,ErrorCode.NOT_FOUND_ERROR);
        Map<String,Object> dto=new LinkedHashMap<>();
        dto.put("id",question.getId()); dto.put("title",question.getTitle()); dto.put("content",question.getContent());
        dto.put("tags",JSONUtil.toList(question.getTags(),String.class)); dto.put("answer",question.getAnswer());
        dto.put("judgeCase",question.getJudgeCase()); dto.put("judgeConfig",question.getJudgeConfig());
        dto.put("problemSpec",question.getProblemSpec()); dto.put("problemVersion",question.getProblemVersion());
        dto.put("isPublished",question.getIsPublished()); dto.put("rowVersion",question.getRowVersion());
        dto.put("sourceQuestionId",question.getSourceQuestionId());
        dto.put("problemNo", question.getProblemNo());
        ProblemIdentity identity = problemLifecycleService.findByNumber(question.getProblemNo());
        if (identity != null) {
            dto.put("lifecycleStatus", identity.getStatus());
            dto.put("lifecycleVersion", identity.getLifecycleVersion());
        }
        return ResultUtils.success(dto);
    }

    @PostMapping("/admin/preview")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Map<String,Object>> adminPreview(@RequestBody QuestionAddRequest body) {
        if(body==null) throw new BusinessException(ErrorCode.PARAMS_ERROR);
        Question transientQuestion=new Question(); BeanUtils.copyProperties(body,transientQuestion);
        transientQuestion.setTags(JSONUtil.toJsonStr(body.getTags()));
        questionService.validQuestion(transientQuestion,true);
        return ResultUtils.success(Map.of("valid",true,"templates",
                languageCapabilities.previewTemplates(body.getProblemSpec())));
    }

    @PostMapping("/admin/publish")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Map<String,Object>> adminPublish(@RequestBody AdminVersionRequest body,HttpServletRequest request) {
        if(body==null || body.getId()==null || body.getExpectedRowVersion()==null)
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(problemLifecycleService.publish(body.getId(), body.getExpectedRowVersion(),
                userFeignClient.getLoginUser(request).getId()));
    }

    @PostMapping("/admin/new_version")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Long> adminNewVersion(@RequestBody AdminVersionRequest body,HttpServletRequest request) {
        if(body==null || body.getId()==null || body.getExpectedRowVersion()==null)
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(problemLifecycleService.createDraft(body.getId(), body.getExpectedRowVersion(),
                userFeignClient.getLoginUser(request).getId()));
    }

    @PostMapping("/admin/problems/list/page")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<AdminProblemSummaryVO>> adminProblemList(@RequestBody AdminProblemQueryRequest body) {
        return ResultUtils.success(problemLifecycleService.listAdminProblems(body));
    }

    @PostMapping("/admin/offline")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<ProblemIdentity> adminOffline(@RequestBody ProblemLifecycleRequest body,
                                                       HttpServletRequest request) {
        if (body == null || body.getProblemNo() == null || body.getExpectedLifecycleVersion() == null)
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        User admin = userFeignClient.getLoginUser(request);
        return ResultUtils.success(problemLifecycleService.takeOffline(body.getProblemNo(),
                body.getExpectedLifecycleVersion(), body.getReason(), admin.getId()));
    }

    @PostMapping("/admin/online")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<ProblemIdentity> adminOnline(@RequestBody ProblemLifecycleRequest body,
                                                      HttpServletRequest request) {
        if (body == null || body.getProblemNo() == null || body.getExpectedLifecycleVersion() == null)
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        User admin = userFeignClient.getLoginUser(request);
        return ResultUtils.success(problemLifecycleService.putOnline(body.getProblemNo(),
                body.getExpectedLifecycleVersion(), admin.getId()));
    }

    // region 提交管理

    /**
     * 提交题目
     *
     * @param questionSubmitAddRequest
     * @param request
     * @return
     */
    @PostMapping("/question_submit/do")
    public BaseResponse<Long> doQuestionSubmit(@RequestBody QuestionSubmitAddRequest questionSubmitAddRequest,
            HttpServletRequest request) {
        if (questionSubmitAddRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userFeignClient.getLoginUser(request);
        // 限流在写入之前：被限流的请求不应产生任何提交记录。
        // 校验顺序刻意放在入库之前，避免"先落库再拒绝"留下多余记录。
        rateLimitGuard.checkSubmitRate(request, loginUser.getId());
        Question submitQuestion = findPublishedQuestion(questionSubmitAddRequest.getQuestionId());
        questionSubmitAddRequest.setQuestionId(submitQuestion.getId());
        long submitId = questionSubmitService.doQuestionSubmit(questionSubmitAddRequest, loginUser);
        return ResultUtils.success(submitId);
    }

    /**
     * 分页获取提交列表
     *
     * @param questionSubmitQueryRequest
     * @param request
     * @return
     */
    @PostMapping("/question_submit/list/page")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<QuestionSubmitVO>> listQuestionSubmitByPage(
            @RequestBody QuestionSubmitQueryRequest questionSubmitQueryRequest,
            HttpServletRequest request) {
        resolveSubmissionProblemNumber(questionSubmitQueryRequest);
        questionSubmitQueryRequest.setProblemSource("PUBLIC");
        long current = questionSubmitQueryRequest.getCurrent();
        long size = questionSubmitQueryRequest.getPageSize();
        User loginUser = userFeignClient.getLoginUser(request);
        Page<QuestionSubmit> questionSubmitPage = questionSubmitService.page(new Page<>(current, size),
                questionSubmitService.getQueryWrapper(questionSubmitQueryRequest));
        return ResultUtils.success(questionSubmitService.getQuestionSubmitVOPage(questionSubmitPage, loginUser));
    }

    private Question findPublishedQuestion(Long key) {
        if (key == null) throw new BusinessException(ErrorCode.PARAMS_ERROR);
        return problemLifecycleService.resolveOnline(key);
    }

    @PostMapping("/question_submit/my/list/page")
    public BaseResponse<Page<QuestionSubmitVO>> listMySubmissions(
            @RequestBody QuestionSubmitQueryRequest query,HttpServletRequest request) {
        if(query==null) throw new BusinessException(ErrorCode.PARAMS_ERROR);
        resolveSubmissionProblemNumber(query);
        if (query.getQuestionId() != null || query.getProblemNo() != null) query.setProblemSource("PUBLIC");
        User loginUser=userFeignClient.getLoginUser(request);
        query.setUserId(loginUser.getId());
        long size=query.getPageSize(); ThrowUtils.throwIf(size>20,ErrorCode.PARAMS_ERROR);
        if(query.getSortField()==null) { query.setSortField("createTime"); query.setSortOrder("descend"); }
        Page<QuestionSubmit> page=questionSubmitService.page(new Page<>(query.getCurrent(),size),
                questionSubmitService.getQueryWrapper(query));
        return ResultUtils.success(questionSubmitService.getQuestionSubmitVOPage(page,loginUser));
    }

    private void resolveSubmissionProblemNumber(QuestionSubmitQueryRequest query) {
        if (query != null && query.getProblemNo() != null) {
            query.setQuestionId(findPublishedQuestion(query.getProblemNo()).getId());
        }
    }

    @GetMapping("/question_submit/get/vo")
    public BaseResponse<QuestionSubmitVO> getSubmission(@RequestParam("id") Long id,HttpServletRequest request) {
        QuestionSubmit submission=questionSubmitService.getById(id);
        ThrowUtils.throwIf(submission==null,ErrorCode.NOT_FOUND_ERROR);
        User loginUser=userFeignClient.getLoginUser(request);
        if("PRIVATE".equals(submission.getProblemSource()) && !submission.getUserId().equals(loginUser.getId()))
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        if(!submission.getUserId().equals(loginUser.getId()) && !userFeignClient.isAdmin(loginUser))
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        return ResultUtils.success(questionSubmitService.getQuestionSubmitVO(submission,loginUser));
    }

    @GetMapping(path = "/question_submit/events", produces = "text/event-stream")
    public SseEmitter submissionEvents(@RequestParam("id") Long id, HttpServletRequest request,
                                       HttpServletResponse response) throws IOException {
        QuestionSubmit submission = questionSubmitService.getById(id);
        // EventSource advertises only text/event-stream. Sending the normal JSON
        // error envelope through that media type has no converter and turns a
        // legitimate 403/404 into 500, so reject before async SSE starts.
        if (submission == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return null;
        }
        User loginUser = userFeignClient.getLoginUser(request);
        if (!submission.getUserId().equals(loginUser.getId()) && !userFeignClient.isAdmin(loginUser)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return null;
        }
        return submissionProgressHub.subscribe(submission);
    }

    @GetMapping("/question_submit/by_request")
    public BaseResponse<Map<String,Object>> getSubmissionByRequest(@RequestParam("clientRequestId") String requestId,
            HttpServletRequest request) {
        User loginUser=userFeignClient.getLoginUser(request);
        QuestionSubmit submission=questionSubmitService.getOne(new QueryWrapper<QuestionSubmit>()
                .eq("userId",loginUser.getId()).eq("clientRequestId",requestId).last("LIMIT 1"));
        if(submission==null) return ResultUtils.success(null);
        return ResultUtils.success(Map.of("id",submission.getId(),"status",submission.getStatus()));
    }

    // endregion

}
