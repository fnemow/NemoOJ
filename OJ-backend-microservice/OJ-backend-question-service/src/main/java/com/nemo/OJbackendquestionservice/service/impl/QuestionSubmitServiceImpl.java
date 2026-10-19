package com.nemo.OJbackendquestionservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.nemo.OJbackendcommon.common.ErrorCode;
import com.nemo.OJbackendcommon.constant.CommonConstant;
import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendcommon.utils.SqlUtils;
import com.nemo.OJbackendcommon.ratelimit.RateLimitGuard;
import com.nemo.OJbackendmodel.model.codesandbox.JudgeInfo;
import com.nemo.OJbackendmodel.model.dto.questionSubmit.QuestionSubmitAddRequest;
import com.nemo.OJbackendmodel.model.dto.questionSubmit.QuestionSubmitQueryRequest;
import com.nemo.OJbackendmodel.model.entity.Question;
import com.nemo.OJbackendmodel.model.entity.QuestionSubmit;
import com.nemo.OJbackendmodel.model.entity.User;
import com.nemo.OJbackendmodel.model.enums.QuestionSubmitLanguageEnum;
import com.nemo.OJbackendmodel.model.enums.QuestionSubmitStatusEnum;
import com.nemo.OJbackendmodel.model.vo.QuestionSubmitVO;
import com.nemo.OJbackendmodel.model.vo.SubmissionDiagnosticVO;
import com.nemo.OJbackendquestionservice.mapper.QuestionSubmitMapper;
import com.nemo.OJbackendquestionservice.outbox.OutboxService;
import com.nemo.OJbackendquestionservice.service.QuestionService;

import com.nemo.OJbackendquestionservice.service.QuestionSubmitService;
import com.nemo.OJserviceclient.UserFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import cn.hutool.json.JSONUtil;

import jakarta.annotation.Resource;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HexFormat;
import java.util.UUID;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.stream.Collectors;

/**
* @author nemo
* @description 针对表【question_submit(题目提交)】的数据库操作Service实现
* @createDate 2026-04-21 19:33:50
*/
@Slf4j
@Service
public class QuestionSubmitServiceImpl extends ServiceImpl<QuestionSubmitMapper, QuestionSubmit>
    implements QuestionSubmitService {

    @Resource
    private QuestionService questionService;

    @Resource
    private UserFeignClient userService;

    @Resource
    private OutboxService outboxService;

    @Resource
    private com.nemo.OJbackendquestionservice.progress.SubmissionProgressPublisher submissionProgressPublisher;

    @Resource
    private com.nemo.OJbackendquestionservice.version.QuestionVersionService questionVersionService;

    @Resource
    private org.springframework.core.env.Environment languageEnvironment;
    @jakarta.annotation.PostConstruct
    void validateLanguageConfiguration() { com.nemo.OJbackendmodel.model.execution.LanguageCompatibility.validateConfiguration(languageEnvironment); }
    @Resource
    private com.nemo.OJbackendquestionservice.service.LanguageCapabilityService languageCapabilities;

    @Resource
    private RateLimitGuard rateLimitGuard;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private com.nemo.OJbackendquestionservice.admission.JudgeAdmission judgeAdmission;

    @Value("${judge.language.enabled:java}")
    private String enabledLanguagesStr;

    /**
     * 单次提交代码的字节上限。
     * 与沙箱侧单文件上限（512KB）保持一致：沙箱最终要按文件落地编译，
     * 这里放宽会让"入库成功但沙箱拒绝"成为可能，用户体验和排查成本都更差。
     */
    @Value("${submit.max-code-bytes:524288}")
    private int maxCodeBytes;

    /**
     * 提交题目。
     * <p>
     * {@code @Transactional} 是本方法的关键：提交记录与待投递事件必须同一次提交。
     * 提交记录写入成功而事件没写（或反之）都会导致任务静默丢失。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public long doQuestionSubmit(QuestionSubmitAddRequest questionSubmitAddRequest, User loginUser) {
        if ("admin".equals(loginUser.getUserRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN_ERROR, "管理员账号不能提交判题");
        }
        if ("ban".equals(loginUser.getUserRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN_ERROR, "账号已被限制提交");
        }
        // 1. 校验编程语言是否合法
        String language = questionSubmitAddRequest.getLanguage();
        QuestionSubmitLanguageEnum languageEnum = QuestionSubmitLanguageEnum.getEnumByValue(language);
        if (languageEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "编程语言错误");
        }

        // 1b. 校验语言是否已启用（Layer 1：提交时主校验）
        if (!getEnabledLanguages().contains(language)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该语言暂未开放: " + language);
        }

        // 1c. 代码长度在**入库前**校验：避免超长代码先落库再被沙箱拒绝
        String code = questionSubmitAddRequest.getCode();
        if (code == null || code.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "代码不能为空");
        }
        int codeBytes = code.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (codeBytes > maxCodeBytes) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "代码过长: " + codeBytes + " 字节，上限 " + maxCodeBytes + " 字节");
        }

        // 2. 校验题目存在且可提交
        long questionId = questionSubmitAddRequest.getQuestionId();
        Question question = questionService.getById(questionId);
        if (question == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        // 草稿不可提交：普通用户不能对未发布题目提交。
        // 一律按"题目不存在"处理，避免通过错误信息的差异探测草稿是否存在。
        if (!Boolean.TRUE.equals(question.getIsPublished())) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        // 未生成版本快照的题目不能提交：否则提交会绑定到不存在的版本，
        // 判题时无法取到确定的判题依据。
        if (question.getProblemVersion() == null || question.getProblemVersion() <= 0) {
            log.error("题目 {} 已发布但没有版本号，数据异常，拒绝提交", questionId);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "题目数据异常，暂不可提交");
        }

        String executionMode = normalizeExecutionMode(questionSubmitAddRequest.getExecutionMode(), question);
        boolean functionMode = "FUNCTION".equals(executionMode);
        if (functionMode && question.getProblemSpec() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该题目未配置核心代码签名");
        }
        var executionSpec = functionMode ? question.getProblemSpec() : null;
        if (!com.nemo.OJbackendmodel.model.execution.LanguageCompatibility.modeEnabled(languageEnvironment, "judge", language, functionMode)) throw new BusinessException(ErrorCode.PARAMS_ERROR, "Language mode is disabled");
        String compatibility = com.nemo.OJbackendmodel.model.execution.LanguageCompatibility.reason(language, executionSpec);
        if (compatibility != null) throw new BusinessException(ErrorCode.PARAMS_ERROR, compatibility);
        var runtimeCapability = languageCapabilities.require(language, question.getProblemSpec(), executionMode);
        runtimeCapability.put("executionMode", executionMode);
        if (question.getJudgeConfig() != null) {
            var config=question.getJudgeConfig();
            double factor=((Number)runtimeCapability.getOrDefault("runtimeFactor",1.0)).doubleValue();
            runtimeCapability.put("effectiveTimeLimitMs",(long)Math.ceil((config.getTimeLimit()==null?10000:config.getTimeLimit())*factor));
            long effectiveTime=((Number)runtimeCapability.get("effectiveTimeLimitMs")).longValue();
            long compileSeconds=((Number)runtimeCapability.getOrDefault("compileTimeoutSeconds",30)).longValue();
            long maxTask=((Number)runtimeCapability.getOrDefault("maxTaskTimeMs",600000)).longValue();
            long count=question.getJudgeCase()==null ? 0 : question.getJudgeCase().size();
            if(compileSeconds*1000+count*(effectiveTime+10000)>maxTask) throw new BusinessException(ErrorCode.PARAMS_ERROR,"题目总执行预算超过平台上限");
            runtimeCapability.put("memoryLimitKB",config.getMemoryLimit());
            runtimeCapability.put("harnessVersion",functionMode?question.getProblemSpec().getHarnessVersion():null);
        }
        if (questionSubmitAddRequest.getProblemVersion() != null
                && !questionSubmitAddRequest.getProblemVersion().equals(question.getProblemVersion())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "题目版本已更新", "PROBLEM_VERSION_CHANGED");
        }

        String clientRequestId = questionSubmitAddRequest.getClientRequestId();
        String requestHash = null;
        if (clientRequestId != null && !clientRequestId.isBlank()) {
            try { UUID.fromString(clientRequestId); }
            catch (IllegalArgumentException e) { throw new BusinessException(ErrorCode.PARAMS_ERROR, "clientRequestId 必须是 UUID"); }
            requestHash = snapshotHash(questionId, question.getProblemVersion(), language, executionMode, code);
            QuestionSubmit existing = this.getOne(new QueryWrapper<QuestionSubmit>()
                    .eq("userId", loginUser.getId()).eq("clientRequestId", clientRequestId).last("LIMIT 1"));
            if (existing != null) {
                boolean legacyMatch = existing.getExecutionMode() == null
                        && legacySnapshotHash(questionId, question.getProblemVersion(), language, code)
                        .equals(existing.getRequestHash());
                if (!requestHash.equals(existing.getRequestHash()) && !legacyMatch) {
                    throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求标识已用于另一份代码", "REQUEST_ID_CONFLICT");
                }
                return existing.getId();
            }
        }
        // 只有通过全部校验且不是幂等重放的请求才占用未完成配额。
        judgeAdmission.check();
        Long replay = replayAfterAdmission(loginUser.getId(), clientRequestId, requestHash,
                legacySnapshotHash(questionId, question.getProblemVersion(), language, code));
        if (replay != null) return replay;
        occupyPending(loginUser.getId());

        // 3. 封装提交记录（含版本快照）
        QuestionSubmit questionSubmit = new QuestionSubmit();
        questionSubmit.setUserId(loginUser.getId());
        questionSubmit.setQuestionId(questionId);
        questionSubmit.setCode(questionSubmitAddRequest.getCode());
        questionSubmit.setLanguage(language);
        questionSubmit.setExecutionMode(executionMode);
        questionSubmit.setRuntimeMetadata(cn.hutool.json.JSONUtil.toJsonStr(runtimeCapability));
        questionSubmit.setClientRequestId(clientRequestId);
        questionSubmit.setRequestHash(requestHash);
        questionSubmit.setStatus(QuestionSubmitStatusEnum.WAITING_FOR_JUDGING.getValue());
        questionSubmit.setJudgeInfo(new JudgeInfo());
        // 版本快照：记录提交时刻的题目版本和 harness 版本
        questionSubmit.setProblemVersionId(question.getProblemVersion());
        if (functionMode) {
            questionSubmit.setHarnessVersion(question.getProblemSpec().getHarnessVersion());
        }
        // 如实标记该提交是否可精确复现：只有绑定的版本快照确实存在时才算。
        // 不可复现的提交（如 V5 之前的历史数据）不能被伪造成"有快照"。
        boolean versionExists =
                questionVersionService.getVersion(questionId, question.getProblemVersion()) != null;
        questionSubmit.setVersionReproducible(versionExists ? 1 : 0);
        if (!versionExists) {
            log.warn("题目 {} 的版本 {} 无对应快照，本次提交将标记为不可精确复现",
                    questionId, question.getProblemVersion());
        }

        // 4. 写入数据库，并在**同一个事务内**写入待投递事件
        try {
            boolean result = this.save(questionSubmit);
            if (!result) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "提交失败");
            }
            long submitId = questionSubmit.getId();

            // 关键：事件与提交记录同事务提交。
            outboxService.enqueue(OutboxService.EVENT_TYPE_JUDGE_SUBMIT, submitId, String.valueOf(submitId));
            submissionProgressPublisher.changed(submitId);
            log.info("提交已入库并生成待投递事件, submitId={}", submitId);
            return submitId;
        } catch (RuntimeException e) {
            throw e;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long doPrivateQuestionSubmit(String privateProblemId, String privateVersionId,
                                        QuestionSubmitAddRequest request, User loginUser) {
        if (request == null || loginUser == null || loginUser.getId() == null
                || !"user".equals(loginUser.getUserRole())) throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        String language = request.getLanguage() == null ? "" : request.getLanguage().trim().toLowerCase();
        if (!getEnabledLanguages().contains(language)) throw new BusinessException(ErrorCode.PARAMS_ERROR, "该语言暂未开放");
        String code = request.getCode();
        if (code == null || code.trim().isEmpty()
                || code.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxCodeBytes)
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "代码为空或超过长度限制");

        var versions = jdbcTemplate.query("SELECT p.title,p.ownerUserId,p.status AS problemStatus,v.versionNumber, "
                        + "v.contentMarkdown,v.problemSpecJson,v.judgeCaseJson,v.judgeConfigJson,v.status AS versionStatus "
                        + "FROM private_problem p JOIN private_problem_version v ON v.privateProblemId=p.id "
                        + "WHERE p.id=? AND p.ownerUserId=? AND p.status='READY' AND v.id=? AND v.status='READY'",
                (rs, row) -> {
                    Map<String, Object> value = new java.util.LinkedHashMap<>();
                    value.put("title", rs.getString("title")); value.put("version", rs.getInt("versionNumber"));
                    value.put("content", rs.getString("contentMarkdown")); value.put("spec", rs.getString("problemSpecJson"));
                    value.put("cases", rs.getString("judgeCaseJson")); value.put("config", rs.getString("judgeConfigJson"));
                    return value;
                }, privateProblemId, loginUser.getId(), privateVersionId);
        if (versions.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        Map<String, Object> version = versions.get(0);
        Question privateQuestion = new Question();
        privateQuestion.setTitle(String.valueOf(version.get("title")));
        privateQuestion.setContent(String.valueOf(version.get("content")));
        privateQuestion.setProblemVersion((Integer) version.get("version"));
        privateQuestion.setIsPublished(true);
        String rawPrivateSpec=String.valueOf(version.get("spec"));
        if(!"null".equalsIgnoreCase(rawPrivateSpec.trim()) && !"{}".equals(rawPrivateSpec.trim()))
            privateQuestion.setProblemSpec(JSONUtil.toBean(rawPrivateSpec,com.nemo.OJbackendmodel.model.dto.question.ProblemSpec.class));
        privateQuestion.setJudgeCase(JSONUtil.toList(String.valueOf(version.get("cases")),
                com.nemo.OJbackendmodel.model.dto.question.JudgeCase.class));
        privateQuestion.setJudgeConfig(JSONUtil.toBean(String.valueOf(version.get("config")),
                com.nemo.OJbackendmodel.model.dto.question.JudgeConfig.class));
        String mode = normalizeExecutionMode(request.getExecutionMode(), privateQuestion);
        boolean functionMode = "FUNCTION".equals(mode);
        if (functionMode && privateQuestion.getProblemSpec() == null)
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该私人题没有核心代码签名");
        if (!com.nemo.OJbackendmodel.model.execution.LanguageCompatibility.modeEnabled(
                languageEnvironment, "judge", language, functionMode))
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该语言或执行模式暂未开放");
        String compatibility = com.nemo.OJbackendmodel.model.execution.LanguageCompatibility.reason(
                language, functionMode ? privateQuestion.getProblemSpec() : null);
        if (compatibility != null) throw new BusinessException(ErrorCode.PARAMS_ERROR, compatibility);
        Map<String, Object> runtime = languageCapabilities.require(language, privateQuestion.getProblemSpec(), mode);
        runtime.put("executionMode", mode);
        int caseCount = privateQuestion.getJudgeCase() == null ? 0 : privateQuestion.getJudgeCase().size();
        if (privateQuestion.getJudgeConfig() != null) {
            long limit = privateQuestion.getJudgeConfig().getTimeLimit() == null ? 10000 : privateQuestion.getJudgeConfig().getTimeLimit();
            runtime.put("effectiveTimeLimitMs", limit);
            runtime.put("memoryLimitKB", privateQuestion.getJudgeConfig().getMemoryLimit());
            runtime.put("harnessVersion", functionMode ? privateQuestion.getProblemSpec().getHarnessVersion() : null);
            long maxTask = ((Number) runtime.getOrDefault("maxTaskTimeMs", 600000)).longValue();
            long compileSeconds = ((Number) runtime.getOrDefault("compileTimeoutSeconds", 30)).longValue();
            if (compileSeconds * 1000 + caseCount * (limit + 10000) > maxTask)
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "私人题执行预算超过平台上限");
        }

        String clientRequestId = request.getClientRequestId();
        if (clientRequestId == null || clientRequestId.isBlank()) clientRequestId = UUID.randomUUID().toString();
        try { clientRequestId = UUID.fromString(clientRequestId).toString(); }
        catch (IllegalArgumentException e) { throw new BusinessException(ErrorCode.PARAMS_ERROR, "clientRequestId 必须为 UUID"); }
        String hash = sha256("PRIVATE\n" + privateVersionId + "\n" + language + "\n" + mode + "\n" + code);
        QuestionSubmit existing = getOne(new QueryWrapper<QuestionSubmit>().eq("userId", loginUser.getId())
                .eq("clientRequestId", clientRequestId).last("LIMIT 1"));
        if (existing != null) {
            if (!hash.equals(existing.getRequestHash())) throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "请求标识已用于另一份代码", "REQUEST_ID_CONFLICT");
            return existing.getId();
        }

        judgeAdmission.check();
        Long replay = replayAfterAdmission(loginUser.getId(), clientRequestId, hash, null);
        if (replay != null) return replay;
        occupyPending(loginUser.getId());
        try {
            QuestionSubmit submission = new QuestionSubmit();
            submission.setUserId(loginUser.getId()); submission.setQuestionId(null);
            submission.setProblemSource("PRIVATE"); submission.setPrivateProblemVersionId(privateVersionId);
            submission.setProblemVersionId(privateQuestion.getProblemVersion());
            submission.setVersionReproducible(1); submission.setExecutionMode(mode);
            submission.setHarnessVersion(functionMode ? privateQuestion.getProblemSpec().getHarnessVersion() : null);
            submission.setLanguage(language); submission.setCode(code); submission.setRuntimeMetadata(JSONUtil.toJsonStr(runtime));
            submission.setClientRequestId(clientRequestId); submission.setRequestHash(hash);
            submission.setStatus(QuestionSubmitStatusEnum.WAITING_FOR_JUDGING.getValue());
            submission.setJudgeInfo(new JudgeInfo());
            if (!save(submission)) throw new BusinessException(ErrorCode.SYSTEM_ERROR, "提交失败");
            outboxService.enqueue(OutboxService.EVENT_TYPE_JUDGE_SUBMIT, submission.getId(), String.valueOf(submission.getId()));
            submissionProgressPublisher.changed(submission.getId());
            return submission.getId();
        } catch (RuntimeException e) {
            throw e;
        }
    }

    private Long replayAfterAdmission(long userId, String clientRequestId, String hash, String legacyHash) {
        if (clientRequestId == null || clientRequestId.isBlank()) return null;
        QuestionSubmit existing = getOne(new QueryWrapper<QuestionSubmit>().eq("userId", userId)
                .eq("clientRequestId", clientRequestId).last("LIMIT 1 FOR UPDATE"));
        if (existing == null) return null;
        if (!hash.equals(existing.getRequestHash()) && !(existing.getExecutionMode() == null
                && legacyHash != null && legacyHash.equals(existing.getRequestHash())))
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求标识已用于另一份代码", "REQUEST_ID_CONFLICT");
        return existing.getId();
    }

    private void occupyPending(long userId) {
        rateLimitGuard.occupySubmitPending(userId);
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) rateLimitGuard.releasePending(userId);
                    }
                });
    }

    /**
     * 获取查询包装类（用户根据哪些字段查询，根据前端传来的请求对象）
     *
     * @param questionSubmitQueryRequest
     * @return
     */
    @Override
    public QueryWrapper<QuestionSubmit> getQueryWrapper(QuestionSubmitQueryRequest questionSubmitQueryRequest) {
        QueryWrapper<QuestionSubmit> queryWrapper = new QueryWrapper<>();
        if (questionSubmitQueryRequest == null) {
            return queryWrapper;
        }

        Long questionId = questionSubmitQueryRequest.getQuestionId();
        String problemSource = questionSubmitQueryRequest.getProblemSource();
        String language = questionSubmitQueryRequest.getLanguage();
        Integer status = questionSubmitQueryRequest.getStatus();
        Long userId = questionSubmitQueryRequest.getUserId();
        String sortField = questionSubmitQueryRequest.getSortField();
        String sortOrder = questionSubmitQueryRequest.getSortOrder();

        // 拼接查询条件
        queryWrapper.eq(ObjectUtils.isNotEmpty(language), "language", language);
        queryWrapper.eq(ObjectUtils.isNotEmpty(userId), "userId", userId);
        queryWrapper.eq(ObjectUtils.isNotEmpty(questionId), "questionId", questionId);
        queryWrapper.eq(ObjectUtils.isNotEmpty(problemSource), "problemSource", problemSource);
        // 防止 status 为 null 时自动拆箱 NPE
        queryWrapper.eq(status != null && QuestionSubmitStatusEnum.getEnumByValue(status) != null, "status", status);
        queryWrapper.eq("isDelete", false);

        // 排序字段白名单校验，非法字段不排序
        if (SqlUtils.validSortField(sortField)) {
            boolean isAsc = CommonConstant.SORT_ORDER_ASC.equals(sortOrder);
            queryWrapper.orderBy(true, isAsc, sortField);
        }
        return queryWrapper;
    }

    /**
     * 获取查询封装类（单个）
     *
     * @param questionSubmit
     * @param loginUser
     * @return
     */
    @Override
    public QuestionSubmitVO getQuestionSubmitVO(QuestionSubmit questionSubmit, User loginUser) {
        QuestionSubmitVO questionSubmitVO = QuestionSubmitVO.objToVo(questionSubmit);
        questionSubmitVO.setProblemVersion(questionSubmit.getProblemVersionId());
        if ("PRIVATE".equalsIgnoreCase(questionSubmit.getProblemSource())) {
            var privateRows = jdbcTemplate.query("SELECT p.id,p.title FROM private_problem p JOIN private_problem_version v "
                            + "ON v.privateProblemId=p.id WHERE p.ownerUserId=? AND v.id=?",
                    (rs, row) -> Map.of("id", rs.getString("id"), "title", rs.getString("title")),
                    questionSubmit.getUserId(), questionSubmit.getPrivateProblemVersionId());
            if (!privateRows.isEmpty()) {
                questionSubmitVO.setPrivateProblemId(String.valueOf(privateRows.get(0).get("id")));
                questionSubmitVO.setQuestionTitle(String.valueOf(privateRows.get(0).get("title")));
            }
        } else {
            Question question = questionService.getById(questionSubmit.getQuestionId());
            if (question != null) {
                questionSubmitVO.setProblemNo(question.getProblemNo());
                questionSubmitVO.setQuestionTitle(question.getTitle());
            }
        }
        JudgeInfo judgeInfo = questionSubmit.getJudgeInfo();
        if (judgeInfo != null) {
            questionSubmitVO.setVerdict(judgeInfo.getMessage());
            questionSubmitVO.setEffectiveLimits(judgeInfo.getEffectiveLimits());
            if (judgeInfo.getRuntimeId() != null) {
                questionSubmitVO.setRuntime(new com.nemo.OJbackendmodel.model.vo.RuntimeSummaryVO(
                        judgeInfo.getRuntimeId(), displayName(questionSubmit.getLanguage())));
            }
            if (judgeInfo.getDiagnosticMessage() != null && !judgeInfo.getDiagnosticMessage().isBlank()) {
                questionSubmitVO.setDiagnostics(new SubmissionDiagnosticVO(
                        judgeInfo.getDiagnosticPhase(), judgeInfo.getDiagnosticMessage(),
                        Boolean.TRUE.equals(judgeInfo.getDiagnosticTruncated())));
            }
        }
        // 脱敏：仅本人和管理员能看见自己（提交 userId 和登录用户 id 不同）提交的代码
        long userId = loginUser.getId();
        // 处理脱敏
        if (userId != questionSubmit.getUserId() && !userService.isAdmin(loginUser)) {
            questionSubmitVO.setCode(null);
        }
        return questionSubmitVO;
    }

    /**
     * 获取查询脱敏信息
     * @param questionSubmitPage 题目提交分页
     * @param loginUser 直接获取到用户信息，减少查询数据库
     * @return
     */
    @Override
    public Page<QuestionSubmitVO> getQuestionSubmitVOPage(Page<QuestionSubmit> questionSubmitPage, User loginUser) {
        List<QuestionSubmit> questionSubmitList = questionSubmitPage.getRecords();
        Page<QuestionSubmitVO> questionSubmitVOPage = new Page<>(questionSubmitPage.getCurrent(), questionSubmitPage.getSize(), questionSubmitPage.getTotal());
        if (CollectionUtils.isEmpty(questionSubmitList)) {
            return questionSubmitVOPage;
        }
        List<QuestionSubmitVO> questionSubmitVOList = questionSubmitList.stream()
                .map(questionSubmit -> getQuestionSubmitVO(questionSubmit, loginUser))
                .collect(Collectors.toList());
        questionSubmitVOPage.setRecords(questionSubmitVOList);
        return questionSubmitVOPage;
    }

    private Set<String> getEnabledLanguages() {
        return Arrays.stream(enabledLanguagesStr.split(","))
                .map(String::trim)
                .collect(Collectors.toSet());
    }

    private String displayName(String language) {
        return switch (language) { case "python" -> "Python 3.12"; case "cpp" -> "C++17"; default -> "Java 8"; };
    }

    private String snapshotHash(long questionId, int version, String language, String executionMode, String code) {
        return sha256(questionId + "\n" + version + "\n" + language + "\n" + executionMode + "\n" + code);
    }

    private String legacySnapshotHash(long questionId, int version, String language, String code) {
        return sha256(questionId + "\n" + version + "\n" + language + "\n" + code);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String normalizeExecutionMode(String requestedMode, Question question) {
        String fallback = question.getProblemSpec() == null ? "ACM" : "FUNCTION";
        String mode = requestedMode == null || requestedMode.isBlank()
                ? fallback : requestedMode.trim().toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("ACM", "FUNCTION").contains(mode)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "执行模式错误");
        }
        return mode;
    }
}
