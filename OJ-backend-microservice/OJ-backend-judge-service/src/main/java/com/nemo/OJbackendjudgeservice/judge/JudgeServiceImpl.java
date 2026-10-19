package com.nemo.OJbackendjudgeservice.judge;

import com.nemo.OJbackendcommon.common.ErrorCode;
import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendjudgeservice.judge.codesandbox.CodeSandBox;
import com.nemo.OJbackendjudgeservice.judge.codesandbox.CodeSandBoxProxy;
import com.nemo.OJbackendjudgeservice.judge.codesandbox.CodeSandboxFactory;
import com.nemo.OJbackendjudgeservice.judge.mode.AcmJudgeModeAdapter;
import com.nemo.OJbackendjudgeservice.judge.mode.FunctionJudgeModeAdapter;
import com.nemo.OJbackendjudgeservice.judge.mode.JudgeModeAdapter;
import com.nemo.OJbackendjudgeservice.judge.strategy.JudgeContext;
import com.nemo.OJbackendmodel.model.codesandbox.ExecuteCodeRequest;
import com.nemo.OJbackendmodel.model.codesandbox.ExecuteCodeRequestV2;
import com.nemo.OJbackendmodel.model.codesandbox.ExecuteCodeResponse;
import com.nemo.OJbackendmodel.model.codesandbox.ExecuteCodeResponseV2;
import com.nemo.OJbackendmodel.model.codesandbox.JudgeInfo;
import com.nemo.OJbackendmodel.model.dto.question.ComparatorSpec;
import com.nemo.OJbackendmodel.model.dto.question.JudgeCase;
import com.nemo.OJbackendmodel.model.dto.question.JudgeConfig;
import com.nemo.OJbackendmodel.model.dto.question.ProblemSpec;
import com.nemo.OJbackendmodel.model.entity.Question;
import com.nemo.OJbackendmodel.model.entity.QuestionSubmit;
import com.nemo.OJbackendmodel.model.enums.ExecutionModeEnum;
import com.nemo.OJbackendmodel.model.enums.JudgeInfoMessageEnum;
import com.nemo.OJbackendmodel.model.enums.QuestionSubmitStatusEnum;
import com.nemo.OJbackendmodel.model.enums.SandboxExecutionStatus;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nemo.OJbackendmodel.model.execution.CaseExecutionResult;
import com.nemo.OJbackendmodel.model.execution.ExecutionPlan;
import com.nemo.OJserviceclient.QuestionFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class JudgeServiceImpl implements JudgeService {

    private static final int MAX_DIAGNOSTIC_CHARS = 16_384;

    @Resource
    private QuestionFeignClient questionFeignClient;

    @Resource
    private JudgeManager judgeManager;

    @Resource
    private LeaseHeartbeat leaseHeartbeat;

    @Resource
    private com.nemo.OJbackendjudgeservice.observability.JudgeMetrics judgeMetrics;

    /** 单次判题的执行租约时长；超过则被回收器判为失联 */
    @Value("${judge.lease.seconds:120}")
    private int leaseSeconds;

    @Resource
    private CodeSandboxFactory codeSandboxFactory;

    @Value("${judge.codesandbox.type}")
    private String judgeType;

    @Resource
    private org.springframework.core.env.Environment languageEnvironment;
    @jakarta.annotation.PostConstruct
    void validateLanguageConfiguration() { com.nemo.OJbackendmodel.model.execution.LanguageCompatibility.validateConfiguration(languageEnvironment); }

    @Value("${judge.language.enabled:java}")
    private String enabledLanguagesStr;

    @Resource
    private AcmJudgeModeAdapter acmAdapter;

    @Resource
    private FunctionJudgeModeAdapter functionAdapter;

    private static final Gson gson = new Gson();

    @Override
    public QuestionSubmit doJudge(long questionSubmitId) {
        // 1. 获取提交
        QuestionSubmit questionSubmit = questionFeignClient.getQuestionSubmitById(questionSubmitId);
        if (questionSubmit == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "提交记录不存在");
        }

        Integer currentStatus = questionSubmit.getStatus();
        if (currentStatus == null) {
            log.warn("提交 {} 状态为空", questionSubmitId);
            return questionSubmit;
        }
        int statusVal = currentStatus.intValue();

        // 已终态 → 幂等返回。重复消息、重复死信走到这里都不会改变结果。
        if (statusVal == QuestionSubmitStatusEnum.SUCCEED.getValue()
                || statusVal == QuestionSubmitStatusEnum.FAILED.getValue()) {
            log.info("提交 {} 已完成({})，幂等返回", questionSubmitId, statusVal);
            return questionSubmit;
        }

        // 判题中 → 可能仍有有效租约（重复消息），也可能是失联任务的残留。
        // 不再"看到判题中就返回"：过期租约由题目服务的回收器接管，
        // 这里只结束本次消费，不干扰正在进行的执行。
        if (statusVal == QuestionSubmitStatusEnum.UNDER_JUDGING.getValue()) {
            log.info("提交 {} 已有执行者（leaseExpireAt={}），本次结束消费", questionSubmitId,
                    questionSubmit.getLeaseExpireAt());
            return questionSubmit;
        }

        if (statusVal != QuestionSubmitStatusEnum.WAITING_FOR_JUDGING.getValue()) {
            log.warn("提交 {} 未知状态({})，跳过", questionSubmitId, statusVal);
            return questionSubmit;
        }

        // 2. 原子抢占并取得本次执行的令牌。抢占失败说明已被他人抢先或已终态。
        String token = questionFeignClient.claimForJudging(questionSubmitId, leaseSeconds);
        if (token == null) {
            log.info("提交 {} 抢占失败（已被接管或已终态），结束本次消费", questionSubmitId);
            return questionFeignClient.getQuestionSubmitById(questionSubmitId);
        }

        // 3. 执行期间持续续租，避免长任务被回收器误判为失联
        LeaseHeartbeat.Session lease = leaseHeartbeat.start(questionSubmitId, token);
        try {
            return doJudgeAfterLock(questionSubmit, token, lease);
        } catch (RuntimeException e) {
            // 系统异常：带令牌回退到 WAITING，让重新投递可以再次抢占。
            // 若令牌已失效（本次执行已被接管），回退会被拒绝——这是期望行为。
            log.error("判题异常，尝试回退 WAITING, submitId={}", questionSubmitId, e);
            revertToWaiting(questionSubmitId, token, rootMessage(e));
            throw e;
        } finally {
            lease.close();
        }
    }

    /**
     * 抢占成功后的判题逻辑。任何 RuntimeException 都会被上层恢复 WAITING 状态。
     */
    private QuestionSubmit doJudgeAfterLock(QuestionSubmit questionSubmit,
                                          String token, LeaseHeartbeat.Session lease) {
        long loadStarted = System.nanoTime();
        long questionSubmitId = questionSubmit.getId();

        // 获取**提交时绑定的题目版本**，而不是当前题目。
        // 用当前题目会让"提交后改题"改变历史提交的判题结果，且无法复现。
        Long questionId = questionSubmit.getQuestionId();
        Integer boundVersion = questionSubmit.getProblemVersionId();

        Question question = null;
        boolean privateProblem = "PRIVATE".equalsIgnoreCase(questionSubmit.getProblemSource());
        if (privateProblem) {
            if (questionSubmit.getPrivateProblemVersionId() != null) {
                question = questionFeignClient.getPrivateProblemVersion(
                        questionSubmit.getPrivateProblemVersionId(), questionSubmit.getUserId());
            }
            // A private exercise must never fall back to a mutable or public question.
            if (question == null) {
                return finish(questionSubmitId, token, QuestionSubmitStatusEnum.FAILED,
                        judgeInfo(JudgeInfoMessageEnum.SYSTEM_ERROR));
            }
        } else if (boundVersion != null && boundVersion > 0 && questionId != null) {
            question = questionFeignClient.getQuestionVersion(questionId, boundVersion);
        }

        if (!privateProblem && question == null) {
            // 版本快照缺失：V5 之前产生的历史提交没有版本行，原始判题依据已不可还原。
            // 如实标记为"不可精确复现"，并退回当前题目继续判题——但不能假装可复现。
            log.warn("提交 {} 绑定的题目版本({})不存在，无法精确复现，将使用当前题目判题",
                    questionSubmitId, boundVersion);
            question = questionFeignClient.getQuestionById(questionId);
            if (question == null) {
                return finish(questionSubmitId, token, QuestionSubmitStatusEnum.FAILED,
                        judgeInfo(JudgeInfoMessageEnum.SYSTEM_ERROR));
            }
        } else if (!boundVersion.equals(question.getProblemVersion())) {
            // 防御性检查：按版本取回的题目，其版本号必须与请求一致
            log.error("按版本取回的题目版本号不符, submitId={}, 期望={}, 实际={}",
                    questionSubmitId, boundVersion, question.getProblemVersion());
            return finish(questionSubmitId, token, QuestionSubmitStatusEnum.FAILED,
                    judgeInfo(JudgeInfoMessageEnum.SYSTEM_ERROR));
        }

        // === 统一使用 V2 协议 ===
        // 新提交按不可变 executionMode 选择适配器；历史提交没有该字段时仍按题目规格推断。
        ProblemSpec problemSpec = question.getProblemSpec();
        String executionMode = questionSubmit.getExecutionMode();
        if (executionMode != null && !executionMode.isBlank()
                && !Set.of("ACM", "FUNCTION").contains(executionMode.toUpperCase(java.util.Locale.ROOT))) {
            log.warn("提交执行模式非法: {}, submitId={}", executionMode, questionSubmitId);
            return finish(questionSubmitId, token, QuestionSubmitStatusEnum.FAILED,
                    judgeInfo(JudgeInfoMessageEnum.LANGUAGE_NOT_SUPPORTED));
        }
        judgeMetrics.recordStage("load", questionSubmit, System.nanoTime() - loadStarted);
        boolean functionMode = executionMode == null || executionMode.isBlank()
                ? problemSpec != null : "FUNCTION".equalsIgnoreCase(executionMode);
        if (functionMode && problemSpec == null) {
            log.warn("核心代码提交缺少题目签名, submitId={}", questionSubmitId);
            return finish(questionSubmitId, token, QuestionSubmitStatusEnum.FAILED,
                    judgeInfo(JudgeInfoMessageEnum.LANGUAGE_NOT_SUPPORTED));
        }
        ProblemSpec executionSpec = functionMode ? problemSpec : null;

        try {
            return doJudgeV2(questionSubmit, question, executionSpec, token);
        } catch (UnsupportedOperationException e) {
            if (!"java".equals(questionSubmit.getLanguage()) || executionSpec != null) {
                throw new IllegalStateException("This submission requires sandbox V2", e);
            }
            log.warn("V2 协议不支持，回退到 V1, submitId={}", questionSubmitId);
        }

        // === V1 回退流程（仅当沙箱不支持 V2 时） ===
        List<JudgeCase> judgeCaseList = question.getJudgeCase();
        if (judgeCaseList == null || judgeCaseList.isEmpty()) {
            return finish(questionSubmitId, token, QuestionSubmitStatusEnum.FAILED,
                    judgeInfo(JudgeInfoMessageEnum.SYSTEM_ERROR));
        }

        // 构建沙箱请求（传递题目资源限制）
        List<String> inputList = judgeCaseList.stream()
                .map(JudgeCase::getInput).collect(Collectors.toList());

        JudgeConfig judgeConfig = question.getJudgeConfig();
        ExecuteCodeRequest req = ExecuteCodeRequest.builder()
                .code(questionSubmit.getCode())
                .language(questionSubmit.getLanguage())
                .inputList(inputList)
                .timeLimitMs(judgeConfig != null ? judgeConfig.getTimeLimit() : null)
                .memoryLimitKB(judgeConfig != null ? judgeConfig.getMemoryLimit() : null)
                .build();

        // 调用沙箱
        CodeSandBox sandbox = codeSandboxFactory.newInstance(judgeType);
        sandbox = new CodeSandBoxProxy(sandbox);

        ExecuteCodeResponse sandboxResp;
        try {
            sandboxResp = sandbox.executeCode(req);
        } catch (Exception e) {
            log.error("沙箱调用异常, submitId={}", questionSubmitId, e);
            throw new RuntimeException("沙箱调用异常", e);
        }

        int sandboxStatus = sandboxResp.getStatus() != null ? sandboxResp.getStatus() : 0;

        // 系统错误 → 抛异常触发消费者内部重试
        if (SandboxExecutionStatus.isSystemError(sandboxStatus)) {
            log.error("沙箱系统错误, submitId={}, status={}", questionSubmitId, sandboxStatus);
            throw new RuntimeException("沙箱系统错误, status=" + sandboxStatus);
        }

        // === 用户侧结果 → 直接写回 ===
        if (sandboxStatus == SandboxExecutionStatus.COMPILE_ERROR) {
            return finish(questionSubmitId, token, QuestionSubmitStatusEnum.FAILED,
                    diagnosticJudgeInfo(JudgeInfoMessageEnum.COMPILE_ERROR, "compile",
                            sandboxResp.getMessage(), sandboxResp.getJudgeInfo()));
        }
        if (sandboxStatus == SandboxExecutionStatus.TIME_LIMIT_EXCEEDED) {
            return finish(questionSubmitId, token, QuestionSubmitStatusEnum.FAILED,
                    judgeInfo(JudgeInfoMessageEnum.TIME_LIMIT_EXCEEDED));
        }
        if (sandboxStatus == SandboxExecutionStatus.MEMORY_LIMIT_EXCEEDED) {
            return finish(questionSubmitId, token, QuestionSubmitStatusEnum.FAILED,
                    judgeInfo(JudgeInfoMessageEnum.MEMORY_LIMIT_EXCEEDED));
        }
        if (sandboxStatus == SandboxExecutionStatus.OUTPUT_LIMIT_EXCEEDED) {
            return finish(questionSubmitId, token, QuestionSubmitStatusEnum.FAILED,
                    judgeInfo(JudgeInfoMessageEnum.OUTPUT_LIMIT_EXCEEDED));
        }
        if (sandboxStatus == SandboxExecutionStatus.RUNTIME_ERROR) {
            return finish(questionSubmitId, token, QuestionSubmitStatusEnum.FAILED,
                    diagnosticJudgeInfo(JudgeInfoMessageEnum.RUNTIME_ERROR, "run",
                            sandboxResp.getMessage(), sandboxResp.getJudgeInfo()));
        }

        // 正常完成 → 判题比较
        List<String> outputList = sandboxResp.getOutputList();
        if (outputList == null) {
            return finish(questionSubmitId, token, QuestionSubmitStatusEnum.FAILED,
                    judgeInfo(JudgeInfoMessageEnum.SYSTEM_ERROR));
        }

        JudgeContext ctx = new JudgeContext();
        ctx.setJudgeInfo(sandboxResp.getJudgeInfo());
        ctx.setInputList(inputList);
        ctx.setOutputList(outputList);
        ctx.setJudgeCaseList(judgeCaseList);
        ctx.setQuestion(question);
        ctx.setQuestionSubmit(questionSubmit);

        JudgeInfo result = judgeManager.doJudge(ctx);
        boolean accepted = JudgeInfoMessageEnum.ACCEPTED.getValue().equals(result.getMessage());
        return finish(questionSubmitId, token,
                accepted ? QuestionSubmitStatusEnum.SUCCEED : QuestionSubmitStatusEnum.FAILED, result);
    }

    // ============ V2 判题流程 ============

    /**
     * V2 判题流程：使用 JudgeModeAdapter 构建 ExecutionPlan，通过 V2 沙箱协议执行。
     * 1) 沙箱状态检查（CE/RE/TLE/MLE/OLE/SYSTEM_ERROR）
     * 2) 用例数校验
     * 3) 使用 JudgeManager + OutputChecker 逐用例比较
     */
    private QuestionSubmit doJudgeV2(QuestionSubmit questionSubmit, Question question,
                                   ProblemSpec spec, String token) {
        long questionSubmitId = questionSubmit.getId();

        // Layer 2: 防御性语言校验（Layer 1 在 Question Service 提交时已校验，此处处理历史消息/配置变更）
        if (!com.nemo.OJbackendmodel.model.execution.LanguageCompatibility.modeEnabled(languageEnvironment, "judge", questionSubmit.getLanguage(), spec != null) || !getEnabledLanguages().contains(questionSubmit.getLanguage()) || com.nemo.OJbackendmodel.model.execution.LanguageCompatibility.reason(questionSubmit.getLanguage(), spec) != null) {
            log.warn("语言未启用: {}, submitId={}", questionSubmit.getLanguage(), questionSubmitId);
            return finish(questionSubmitId, token, QuestionSubmitStatusEnum.FAILED,
                    judgeInfo(JudgeInfoMessageEnum.LANGUAGE_NOT_SUPPORTED));
        }

        // 根据是否配置 ProblemSpec 选择适配器
        JudgeModeAdapter adapter = (spec != null) ? functionAdapter : acmAdapter;

        // 构建执行计划
        ExecutionPlan plan = adapter.buildExecutionPlan(question, questionSubmit);

        if (questionSubmit.getRuntimeMetadata() != null) {
            JsonObject metadata = gson.fromJson(questionSubmit.getRuntimeMetadata(), JsonObject.class);
            plan.setRuntimeId(metadata.get("runtimeId").getAsString());
        } else if (!"java".equals(questionSubmit.getLanguage())) {
            throw new IllegalStateException("Submission has no bound runtime");
        }
        // 调用沙箱 V2
        CodeSandBox sandbox = codeSandboxFactory.newInstance(judgeType);
        sandbox = new CodeSandBoxProxy(sandbox);

        ExecuteCodeRequestV2 v2Req = ExecuteCodeRequestV2.builder()
                .submissionId(questionSubmitId)
                .executionPlan(plan)
                .build();

        ExecuteCodeResponseV2 sandboxResp;
        long sandboxStarted = System.nanoTime();
        try {
            sandboxResp = sandbox.executeCodeV2(v2Req);
        } catch (UnsupportedOperationException e) {
            log.error("沙箱不支持 V2 协议, submitId={}", questionSubmitId);
            return finish(questionSubmitId, token, QuestionSubmitStatusEnum.FAILED,
                    judgeInfo(JudgeInfoMessageEnum.SYSTEM_ERROR));
        } catch (Exception e) {
            log.error("V2 沙箱调用异常, submitId={}", questionSubmitId, e);
            throw new RuntimeException("V2 沙箱调用异常", e);
        } finally {
            judgeMetrics.recordStage("sandbox", questionSubmit, System.nanoTime() - sandboxStarted);
        }

        if (sandboxResp == null) {
            throw new RuntimeException("V2 沙箱返回空响应");
        }

        if (sandboxResp.getOverallStatus() == null || sandboxResp.getOverallStatus() < 0 || sandboxResp.getOverallStatus() > 6) throw new IllegalStateException("Invalid sandbox overall status");
        // === 沙箱状态路由 ===
        // 语言不支持 → 不重试，直接返回
        if (sandboxResp.getOverallStatus() != null
                && sandboxResp.getOverallStatus() == SandboxExecutionStatus.UNSUPPORTED_LANGUAGE) {
            return finish(questionSubmitId, token, QuestionSubmitStatusEnum.FAILED,
                    judgeInfo(JudgeInfoMessageEnum.LANGUAGE_NOT_SUPPORTED));
        }

        // 系统错误 → 重试
        if (sandboxResp.getSystemMessage() != null) {
            log.error("V2 沙箱系统错误, submitId={}, message={}", questionSubmitId,
                    sandboxResp.getSystemMessage());
            throw new RuntimeException("V2 沙箱系统错误: " + sandboxResp.getSystemMessage());
        }

        // 编译错误
        if (sandboxResp.getCompileMessage() != null) {
            return finish(questionSubmitId, token, QuestionSubmitStatusEnum.FAILED,
                    diagnosticJudgeInfo(JudgeInfoMessageEnum.COMPILE_ERROR, "compile",
                            (spec != null ? UserDiagnosticSanitizer.functionCompile(sandboxResp.getCompileMessage())
                                    : sandboxResp.getCompileMessage()), (JudgeInfo) null));
        }

        // 逐用例检查沙箱状态（CE/RE/TLE/MLE/OLE/SYSTEM_ERROR）
        List<CaseExecutionResult> caseResults = sandboxResp.getCases();
        if (caseResults != null) {
            for (CaseExecutionResult cr : caseResults) {
                if (cr == null || cr.getStatus() == null || cr.getStatus() < 0 || cr.getStatus() > 5) throw new IllegalStateException("Invalid case status");
                int st = cr.getStatus();
                if (st == SandboxExecutionStatus.COMPILE_ERROR)
                    return finish(questionSubmitId, token, QuestionSubmitStatusEnum.FAILED,
                            diagnosticJudgeInfo(JudgeInfoMessageEnum.COMPILE_ERROR, "compile",
                                    (spec != null ? UserDiagnosticSanitizer.functionCompile(caseDiagnostic(cr))
                                            : caseDiagnostic(cr)), cr));
                if (st == SandboxExecutionStatus.RUNTIME_ERROR)
                    return finish(questionSubmitId, token, QuestionSubmitStatusEnum.FAILED,
                            diagnosticJudgeInfo(JudgeInfoMessageEnum.RUNTIME_ERROR, "run",
                                    caseDiagnostic(cr), cr));
                if (st == SandboxExecutionStatus.TIME_LIMIT_EXCEEDED)
                    return finish(questionSubmitId, token, QuestionSubmitStatusEnum.FAILED,
                            judgeInfo(JudgeInfoMessageEnum.TIME_LIMIT_EXCEEDED));
                if (st == SandboxExecutionStatus.MEMORY_LIMIT_EXCEEDED)
                    return finish(questionSubmitId, token, QuestionSubmitStatusEnum.FAILED,
                            judgeInfo(JudgeInfoMessageEnum.MEMORY_LIMIT_EXCEEDED));
                if (st == SandboxExecutionStatus.OUTPUT_LIMIT_EXCEEDED)
                    return finish(questionSubmitId, token, QuestionSubmitStatusEnum.FAILED,
                            judgeInfo(JudgeInfoMessageEnum.OUTPUT_LIMIT_EXCEEDED));
                if (SandboxExecutionStatus.isSystemError(st))
                    throw new RuntimeException("沙箱系统错误, status=" + st);
            }
        }

        if (sandboxResp.getOverallStatus() != 0) throw new IllegalStateException("Inconsistent sandbox overall status");
        // === 用例数校验 ===
        // FUNCTION 的输入和期望值属于不可变 ProblemSpec 快照；ACM 才使用顶层 judgeCase。
        List<JudgeCase> judgeCaseList = spec != null
                && spec.getJudgeCases() != null
                && !spec.getJudgeCases().isEmpty()
                ? spec.getJudgeCases()
                : question.getJudgeCase();
        if (judgeCaseList == null || judgeCaseList.isEmpty()) {
            return finish(questionSubmitId, token, QuestionSubmitStatusEnum.FAILED,
                    judgeInfo(JudgeInfoMessageEnum.SYSTEM_ERROR));
        }
        if (caseResults == null || caseResults.size() != judgeCaseList.size()) {
            log.error("V2 用例数不匹配, submitId={}, expected={}, actual={}",
                    questionSubmitId, judgeCaseList.size(),
                    caseResults != null ? caseResults.size() : 0);
            return finish(questionSubmitId, token, QuestionSubmitStatusEnum.FAILED,
                    judgeInfo(JudgeInfoMessageEnum.SYSTEM_ERROR));
        }

        java.util.Set<Integer> indexes = new java.util.HashSet<>();
        for (CaseExecutionResult cr : caseResults) {
            if (cr.getCaseIndex() == null || cr.getCaseIndex() < 0 || cr.getCaseIndex() >= caseResults.size() || !indexes.add(cr.getCaseIndex())) throw new IllegalStateException("Invalid case indexes");
        }
        caseResults.sort(java.util.Comparator.comparing(CaseExecutionResult::getCaseIndex));
        // === 提取输出和指标 ===
        // FUNCTION 模式：需要从 Runner 协议 JSON 中提取 actual 字段
        // ACM 模式：直接使用原始 stdout
        List<String> outputList = new ArrayList<>();
        long maxTime = 0, maxMem = 0;
        for (int i = 0; i < caseResults.size(); i++) {
            CaseExecutionResult cr = caseResults.get(i);
            String stdout = cr.getStdout();
            if (spec != null) {
                // FUNCTION 模式：解析 Runner 协议 JSON，提取 actual 字段
                if (stdout == null || stdout.trim().isEmpty()) {
                    log.error("FUNCTION 模式用例 {} stdout 为空，协议异常", i);
                    return finish(questionSubmitId, token, QuestionSubmitStatusEnum.FAILED,
                            diagnosticJudgeInfo(JudgeInfoMessageEnum.RUNTIME_ERROR, "run",
                                    "核心代码运行器没有返回结果", cr));
                }
                String extracted = extractActualFromRunnerProtocol(stdout);
                if (extracted == null || !com.nemo.OJbackendjudgeservice.judge.harness.FunctionResultValidator.valid(spec, extracted)) {
                    return finish(questionSubmitId, token, QuestionSubmitStatusEnum.FAILED,
                            diagnosticJudgeInfo(JudgeInfoMessageEnum.RUNTIME_ERROR, "run",
                                    runnerDiagnostic(stdout, cr), cr));
                }
                outputList.add(extracted);
            } else {
                // ACM 模式：原始 stdout
                outputList.add(stdout != null ? stdout : "");
            }
            if (cr.getTime() != null) maxTime = Math.max(maxTime, cr.getTime());
            if (cr.getMemory() != null) maxMem = Math.max(maxMem, cr.getMemory());
        }

        // === 使用比较器进行输出比较 ===
        // 选择 ComparatorSpec：FUNCTION → ProblemSpec 中的配置，ACM → 默认 TEXT
        ComparatorSpec comparator = (spec != null && spec.getComparator() != null)
                ? spec.getComparator()
                : new ComparatorSpec("TEXT", null);

        JudgeInfo sandboxJudgeInfo = new JudgeInfo();
        sandboxJudgeInfo.setExecutedTime(maxTime);
        sandboxJudgeInfo.setExecutedMemory(maxMem);

        JudgeContext ctx = new JudgeContext();
        if (sandboxResp.getEffectiveLimits() != null && (sandboxResp.getEffectiveLimits().getTimeLimitMs() == null || sandboxResp.getEffectiveLimits().getTimeLimitMs() <= 0 || sandboxResp.getEffectiveLimits().getMemoryLimitKB() == null || sandboxResp.getEffectiveLimits().getMemoryLimitKB() <= 0)) throw new IllegalStateException("Invalid effective limits");
        ctx.setEffectiveLimits(sandboxResp.getEffectiveLimits());
        if (!"java".equals(questionSubmit.getLanguage()) && sandboxResp.getEffectiveLimits() == null) throw new IllegalStateException("Missing effective resource limits");
        ctx.setJudgeInfo(sandboxJudgeInfo);
        ctx.setInputList(plan.getCases() != null
                ? plan.getCases().stream().map(c -> c.getStdin()).collect(Collectors.toList())
                : new ArrayList<>());
        ctx.setOutputList(outputList);
        ctx.setJudgeCaseList(judgeCaseList);
        ctx.setQuestion(question);
        ctx.setQuestionSubmit(questionSubmit);

        long decisionStarted = System.nanoTime();
        JudgeInfo result = judgeManager.doJudgeV2(ctx, comparator);
        judgeMetrics.recordStage("decision", questionSubmit, System.nanoTime() - decisionStarted);
        result.setEffectiveLimits(sandboxResp.getEffectiveLimits());
        result.setPolicyVersion(sandboxResp.getPolicyVersion());
        result.setRuntimeId(plan.getRuntimeId());
        result.setHarnessVersion(plan.getHarnessVersion());
        boolean accepted = JudgeInfoMessageEnum.ACCEPTED.getValue().equals(result.getMessage());
        return finish(questionSubmitId, token,
                accepted ? QuestionSubmitStatusEnum.SUCCEED : QuestionSubmitStatusEnum.FAILED,
                result);
    }

    // ============ helpers ============

    /**
     * CAS 恢复：仅当状态仍为 UNDER_JUDGING 时才更新为 WAITING。
     * 如果 finish() 已成功写为 SUCCEED/FAILED，CAS 会失败，不应该覆盖。
     */
    private void revertToWaiting(long submitId, String token, String reason) {
        try {
            boolean updated = questionFeignClient.revertToWaitingWithToken(submitId, token, reason);
            if (updated) {
                log.info("CAS 恢复 WAITING 成功, submitId={}", submitId);
            } else {
                log.warn("CAS 恢复失败（状态已变更，可能 finish() 已成功）, submitId={}", submitId);
            }
        } catch (Exception e) {
            log.error("CAS 恢复调用异常, submitId={}", submitId, e);
        }
    }

    /**
     * 写回终态结果。必须携带本次执行的令牌：
     * 令牌不匹配说明本次执行已被接管或已完成，写入会被拒绝，
     * 从而避免旧执行的迟到结果覆盖新一轮执行或已完成的正确结果。
     */
    private QuestionSubmit finish(long submitId, String token,
                                  QuestionSubmitStatusEnum status, JudgeInfo judgeInfo) {
        // 结果分布指标在唯一的终态出口记录，避免各分支漏记或重复记
        judgeMetrics.recordResult(mapResult(status, judgeInfo));
        QuestionSubmit update = new QuestionSubmit();
        update.setId(submitId);
        update.setStatus(status.getValue());
        update.setJudgeInfo(judgeInfo);
        long persistStarted = System.nanoTime();
        boolean ok = questionFeignClient.finishWithToken(update, token);
        if (!ok) {
            log.warn("结果写回被拒绝（租约已转移或已终态）, submitId={}", submitId);
        }
        QuestionSubmit finished = questionFeignClient.getQuestionSubmitById(submitId);
        judgeMetrics.recordStage("persist", finished, System.nanoTime() - persistStarted);
        judgeMetrics.recordEndToEnd(finished);
        return finished;
    }

    /**
     * 把终态映射为有限的指标标签取值。
     * 只映射到固定的几个类别，不把 message 原文当标签（会引入无界基数）。
     */
    private String mapResult(QuestionSubmitStatusEnum status, JudgeInfo judgeInfo) {
        String message = judgeInfo == null ? null : judgeInfo.getMessage();
        if (JudgeInfoMessageEnum.ACCEPTED.getValue().equals(message)) {
            return com.nemo.OJbackendjudgeservice.observability.JudgeMetrics.ACCEPTED;
        }
        if (JudgeInfoMessageEnum.WRONG_ANSWER.getValue().equals(message)) {
            return com.nemo.OJbackendjudgeservice.observability.JudgeMetrics.WRONG_ANSWER;
        }
        if (JudgeInfoMessageEnum.COMPILE_ERROR.getValue().equals(message)) {
            return com.nemo.OJbackendjudgeservice.observability.JudgeMetrics.COMPILE_ERROR;
        }
        if (JudgeInfoMessageEnum.TIME_LIMIT_EXCEEDED.getValue().equals(message)) {
            return com.nemo.OJbackendjudgeservice.observability.JudgeMetrics.TIME_LIMIT;
        }
        if (JudgeInfoMessageEnum.MEMORY_LIMIT_EXCEEDED.getValue().equals(message)) {
            return com.nemo.OJbackendjudgeservice.observability.JudgeMetrics.MEMORY_LIMIT;
        }
        if (JudgeInfoMessageEnum.OUTPUT_LIMIT_EXCEEDED.getValue().equals(message)) {
            return com.nemo.OJbackendjudgeservice.observability.JudgeMetrics.OUTPUT_LIMIT;
        }
        if (JudgeInfoMessageEnum.RUNTIME_ERROR.getValue().equals(message)) {
            return com.nemo.OJbackendjudgeservice.observability.JudgeMetrics.RUNTIME_ERROR;
        }
        return com.nemo.OJbackendjudgeservice.observability.JudgeMetrics.SYSTEM_ERROR;
    }

    private JudgeInfo judgeInfo(JudgeInfoMessageEnum msg) {
        JudgeInfo info = new JudgeInfo();
        info.setMessage(msg.getValue());
        return info;
    }

    private JudgeInfo diagnosticJudgeInfo(JudgeInfoMessageEnum msg, String phase,
                                           String detail, JudgeInfo metrics) {
        JudgeInfo info = metrics == null ? new JudgeInfo() : metrics;
        info.setMessage(msg.getValue());
        info.setDiagnosticPhase(phase);
        String sanitized = UserDiagnosticSanitizer.sanitize(detail);
        String normalized = sanitized == null || sanitized.isBlank()
                ? "程序异常退出，但没有产生错误输出" : sanitized;
        boolean truncated = normalized.length() > MAX_DIAGNOSTIC_CHARS;
        info.setDiagnosticMessage(truncated ? normalized.substring(0, MAX_DIAGNOSTIC_CHARS) : normalized);
        info.setDiagnosticTruncated(truncated);
        return info;
    }

    private JudgeInfo diagnosticJudgeInfo(JudgeInfoMessageEnum msg, String phase,
                                           String detail, CaseExecutionResult result) {
        JudgeInfo metrics = new JudgeInfo();
        if (result != null) {
            metrics.setExecutedTime(result.getTime());
            metrics.setExecutedMemory(result.getMemory());
        }
        return diagnosticJudgeInfo(msg, phase, detail, metrics);
    }

    private String caseDiagnostic(CaseExecutionResult result) {
        if (result == null) return null;
        String detail = result.getStderr();
        if (detail == null || detail.isBlank()) {
            detail = result.getExitCode() == null ? null : "Process exited with code " + result.getExitCode();
        }
        return "测试用例 " + (result.getCaseIndex() == null ? "未知" : result.getCaseIndex() + 1) + ":\n" +
                (detail == null ? "程序异常退出，但没有产生错误输出" : detail);
    }

    private String runnerDiagnostic(String stdout, CaseExecutionResult result) {
        try {
            JsonObject obj = gson.fromJson(stdout.trim(), JsonObject.class);
            if (obj != null && obj.has("error") && !obj.get("error").isJsonNull()) {
                return "测试用例 " + (result.getCaseIndex() == null ? "未知" : result.getCaseIndex() + 1)
                        + ":\n" + obj.get("error").getAsString();
            }
        } catch (Exception ignored) {
            // 下面返回原始协议输出，便于定位格式错误。
        }
        return "测试用例 " + (result.getCaseIndex() == null ? "未知" : result.getCaseIndex() + 1)
                + ": 核心代码运行失败\n" + stdout;
    }

    /** 取最内层异常信息，作为回退时记录的原因 */
    private String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getClass().getSimpleName()
                + (cur.getMessage() == null ? "" : ": " + cur.getMessage());
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }

    private Set<String> getEnabledLanguages() {
        return Arrays.stream(enabledLanguagesStr.split(","))
                .map(String::trim)
                .collect(Collectors.toSet());
    }

    /**
     * 从 Runner 协议输出中提取 actual 字段，用于比较器。
     * Runner 输出格式：{"status":"OK","actual":...,"timeMs":N}
     * 调用方负责保证 stdout 非空。
     *
     * @param stdout Runner 输出的单行 JSON 字符串（非空）
     * @return actual 的 JSON 字符串，null 表示需要由调用方根据 stdout 内容区分 RE / SYSTEM_ERROR
     */
    private String extractActualFromRunnerProtocol(String stdout) {
        try {
            JsonObject obj = gson.fromJson(stdout.trim(), JsonObject.class);
            if (obj == null) return null;
            String status = obj.has("status") ? obj.get("status").getAsString() : "";
            if (!"OK".equals(status)) return null;
            JsonElement actual = obj.get("actual");
            if (actual == null) return null;
            if (actual.isJsonNull()) return "null";
            return gson.toJson(actual);
        } catch (Exception e) {
            log.warn("解析 Runner 协议输出失败, stdout={}", stdout, e);
            return null;
        }
    }
}
