package com.nemo.OJbackendquestionservice.controller;

import com.nemo.OJbackendquestionservice.budget.AiDailyBudget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nemo.OJbackendcommon.common.BaseResponse;
import com.nemo.OJbackendcommon.common.ErrorCode;
import com.nemo.OJbackendcommon.common.ResultUtils;
import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendmodel.model.dto.question.JudgeCase;
import com.nemo.OJbackendmodel.model.dto.question.JudgeConfig;
import com.nemo.OJbackendmodel.model.dto.question.PrivateProblemValidationRequest;
import com.nemo.OJbackendmodel.model.dto.question.ProblemSpec;
import com.nemo.OJbackendmodel.model.entity.Question;
import com.nemo.OJbackendquestionservice.service.PrivateProblemValidationGateway;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;

/** Private, signed service-to-service endpoints. Never route these through the public gateway. */
@RestController
@RequestMapping("/internal/ai/tasks")
public class AgentInternalController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PrivateProblemValidationGateway privateProblemValidator;
    @Value("${ai.internal-secret:}") private String sharedSecret;
    @Value("${ai.model.id:}") private String currentModelId;
    @Value("${ai.private-problems.enabled:false}") private boolean privateProblemsEnabled;
    @Value("${ai.budget.daily-usd:0}") private BigDecimal dailyBudget;
    @Value("${ai.budget.max-task-reservation-usd:0}") private BigDecimal taskReservation;
    @Value("${ai.feedback.enabled:false}") private boolean feedbackAgentEnabled;
    @Value("${ai.maintenance.enabled:false}") private boolean maintenanceEnabled;
    @Value("${ai.notification.email:}") private String escalationEmail;
    @Value("${ai.model.max-attempts:2}") private int maxAttempts;
    @jakarta.annotation.Resource private com.nemo.OJbackendquestionservice.budget.AiModelCallLedger callLedger;
    @jakarta.annotation.Resource private com.nemo.OJbackendquestionservice.service.PrivateValidationJobs validationJobs;
    @jakarta.annotation.Resource private com.nemo.OJbackendquestionservice.service.SiteHelpPolicy siteHelpPolicy;

    public AgentInternalController(JdbcTemplate jdbc, ObjectMapper json, PrivateProblemValidationGateway privateProblemValidator) {
        this.jdbc = jdbc; this.json = json; this.privateProblemValidator = privateProblemValidator;
    }

    @PostMapping("/{id}/calls/{callId}/start")
    public BaseResponse<Boolean> startCall(@PathVariable String id, @PathVariable String callId,
                                           @RequestBody byte[] body, HttpServletRequest request) {
        verifySignature(request, body);
        callLedger.start(id, callId, parse(body).path("workerId").asText());
        return ResultUtils.success(true);
    }

    @PostMapping("/{id}/calls/{callId}/finish")
    public BaseResponse<Boolean> finishCall(@PathVariable String id, @PathVariable String callId,
                                            @RequestBody byte[] body, HttpServletRequest request) {
        verifySignature(request, body);
        JsonNode root = parse(body);
        BigDecimal cost;
        try { cost = new BigDecimal(root.path("estimatedCostUsd").asText("-1")); }
        catch (NumberFormatException e) { throw new BusinessException(ErrorCode.PARAMS_ERROR); }
        callLedger.finish(id, callId, root.path("workerId").asText(), root.path("state").asText(),
                root.path("inputTokens").asLong(-1), root.path("outputTokens").asLong(-1), cost);
        return ResultUtils.success(true);
    }

    @PostMapping("/{id}/claim")
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse<Map<String, Object>> claim(@PathVariable String id, @RequestBody(required = false) byte[] body,
                                                    HttpServletRequest request) {
        verifySignature(request, body);
        String worker = "agent";
        try { if (body != null && body.length > 0) worker = json.readTree(body).path("workerId").asText("agent"); }
        catch (Exception ignored) { throw new BusinessException(ErrorCode.PARAMS_ERROR); }
        if (worker.length() > 128) throw new BusinessException(ErrorCode.PARAMS_ERROR);
        int changed = jdbc.update("UPDATE ai_task SET attemptCount=attemptCount+(CASE WHEN type='PRIVATE_PROBLEM' AND stage='VALIDATION_READY' THEN 0 ELSE 1 END),status='RUNNING',stage='CLAIMED',leaseOwner=?, "
                        + "leaseExpireAt=(CASE WHEN type='PRIVATE_PROBLEM' AND startedAt IS NOT NULL THEN DATE_ADD(startedAt,INTERVAL 900 SECOND) ELSE DATE_ADD(NOW(),INTERVAL (CASE WHEN type='PRIVATE_PROBLEM' THEN 900 ELSE 300 END) SECOND) END), "
                        + "startedAt=COALESCE(startedAt,NOW()) WHERE id=? AND type IN ('ANALYSIS','PLAN','PLAN_ADJUST','PRIVATE_PROBLEM','FEEDBACK','INSPECTION','SITE_HELP') AND "
                        + "(status='QUEUED' OR (status='RUNNING' AND leaseExpireAt<NOW())) AND (type<>'PRIVATE_PROBLEM' OR startedAt IS NULL OR startedAt>DATE_SUB(NOW(),INTERVAL 900 SECOND))", worker, id);
        if (changed == 0) return ResultUtils.success(Map.of("claimed", false));
        return ResultUtils.success(Map.of("claimed", true));
    }

    @PostMapping("/{id}/heartbeat")
    public BaseResponse<Boolean> heartbeat(@PathVariable String id,@RequestBody byte[] body,HttpServletRequest request) {
        verifySignature(request,body);JsonNode root=parse(body);
        if("WAIT_VALIDATION".equals(root.path("stage").asText()))validationJobs.suspend(id,root.path("workerId").asText(),root.path("validationId").asText());
        else validationJobs.heartbeat(id,root.path("workerId").asText(),root.path("stage").asText(),root.path("repairCount").asInt());
        return ResultUtils.success(true);
    }
    @PostMapping("/{id}/validations")
    public BaseResponse<Map<String,Object>> validation(@PathVariable String id,@RequestBody byte[] body,HttpServletRequest request) {
        verifySignature(request,body);JsonNode root=parse(body);return ResultUtils.success(validationJobs.submit(id,root.path("workerId").asText(),root.path("revision").asInt(),root.path("privateProblem")));
    }
    @PostMapping("/{id}/validations/{jobId}")
    public BaseResponse<Map<String,Object>> validationStatus(@PathVariable String id,@PathVariable String jobId,@RequestBody byte[] body,HttpServletRequest request) {
        verifySignature(request,body);return ResultUtils.success(validationJobs.poll(id,parse(body).path("workerId").asText(),jobId));
    }

    @PostMapping("/{id}/context")
    public BaseResponse<Map<String, Object>> context(@PathVariable String id, @RequestBody(required = false) byte[] body,
                                                      HttpServletRequest request) {
        verifySignature(request, body);
        List<Map<String, Object>> tasks = jdbc.query("SELECT type,ownerUserId,resourceId,requestJson,status,startedAt FROM ai_task "
                        + "WHERE id=? AND type IN ('ANALYSIS','PLAN','PLAN_ADJUST','PRIVATE_PROBLEM','FEEDBACK','INSPECTION','SITE_HELP')", (rs, row) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("type", rs.getString("type"));
                    item.put("owner", rs.getLong("ownerUserId")); item.put("resource", rs.getString("resourceId"));
                    item.put("request", rs.getString("requestJson"));
                    item.put("status", rs.getString("status"));item.put("startedAt",rs.getTimestamp("startedAt")); return item;
                }, id);
        if (tasks.isEmpty() || !"RUNNING".equals(tasks.get(0).get("status"))) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        String taskType = String.valueOf(tasks.get(0).get("type"));
        long ownerId = ((Number) tasks.get(0).get("owner")).longValue();
        if ("PRIVATE_PROBLEM".equals(taskType)) {
            Map<String,Object> context = new LinkedHashMap<>();
            context.put("taskId", id); context.put("ownerUserId", ownerId); context.put("taskType", taskType);
            context.put("privateProblemId", tasks.get(0).get("resource"));
            context.put("deadlineEpoch",((java.sql.Timestamp)tasks.get(0).get("startedAt")).toInstant().plusSeconds(900).getEpochSecond());
            try { context.put("request", json.readTree(String.valueOf(tasks.get(0).get("request")))); }
            catch (Exception e) { throw new BusinessException(ErrorCode.PARAMS_ERROR, "私人题任务缺少有效请求"); }
            return ResultUtils.success(context);
        }
        if ("FEEDBACK".equals(taskType)) return ResultUtils.success(feedbackContext(id,ownerId,Long.parseLong(String.valueOf(tasks.get(0).get("resource")))));
        if ("INSPECTION".equals(taskType)) {
            if (!maintenanceEnabled) throw new BusinessException(ErrorCode.OPERATION_ERROR, "AI 题库巡检已关闭");
            return ResultUtils.success(inspectionContext(id, String.valueOf(tasks.get(0).get("request"))));
        }
        if ("PLAN".equals(taskType) || "PLAN_ADJUST".equals(taskType)) {
            return ResultUtils.success(planContext(taskType, ownerId,
                    String.valueOf(tasks.get(0).get("resource")), String.valueOf(tasks.get(0).get("request"))));
        }
        if("SITE_HELP".equals(taskType)) {
            JsonNode input=parse(String.valueOf(tasks.get(0).get("request")).getBytes(StandardCharsets.UTF_8));
            Map<String,Object> context=new LinkedHashMap<>();context.put("taskId",id);context.put("taskType","SITE_HELP");
            context.put("message",input.path("message").asText());context.put("history",input.path("history"));context.put("guideCatalog",siteHelpPolicy.choices());
            return ResultUtils.success(context);
        }
        long submissionId = Long.parseLong(String.valueOf(tasks.get(0).get("resource")));
        List<Map<String, Object>> submissions = jdbc.query("SELECT id,userId,questionId,problemSource,privateProblemVersionId, "
                        + "problemVersionId,language,executionMode,code,status,judgeInfo FROM question_submit "
                        + "WHERE id=? AND userId=? AND isDelete=0", (rs, row) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", rs.getLong("id")); item.put("userId", rs.getLong("userId"));
                    item.put("questionId", rs.getObject("questionId")); item.put("source", rs.getString("problemSource"));
                    item.put("privateVersionId", rs.getString("privateProblemVersionId"));
                    item.put("version", rs.getObject("problemVersionId")); item.put("language", rs.getString("language"));
                    item.put("mode", rs.getString("executionMode")); item.put("code", rs.getString("code"));
                    item.put("status", rs.getInt("status")); item.put("judgeInfo", rs.getString("judgeInfo")); return item;
                }, submissionId, ownerId);
        if (submissions.isEmpty() || ((Number) submissions.get(0).get("status")).intValue() < 2)
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        Map<String, Object> submit = submissions.get(0);
        Map<String, Object> taskContext = new LinkedHashMap<>();
        taskContext.put("taskId", id); taskContext.put("ownerUserId", ownerId);
        taskContext.put("submissionId", submissionId); taskContext.put("code", submit.get("code"));
        taskContext.put("taskType", taskType);
        taskContext.put("language", submit.get("language")); taskContext.put("executionMode", submit.get("mode"));
        taskContext.put("verdict", verdict(submit));
        taskContext.put("runtime", runtimeEvidence(submit.get("judgeInfo")));
        addPublicProblem(taskContext, submit);
        addPrivateProblem(taskContext, submit, ownerId);
        return ResultUtils.success(taskContext);
    }

    @PostMapping("/{id}/complete")
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse<Boolean> complete(@PathVariable String id, @RequestBody byte[] body, HttpServletRequest request) {
        verifySignature(request, body);
        JsonNode root = parse(body);
        String worker = root.path("workerId").asText("");
        if (!worker.isBlank()) {
            List<Map<String,Object>> taskRows = jdbc.queryForList("SELECT status,leaseOwner FROM ai_task WHERE id=? FOR UPDATE", id);
            if (!taskRows.isEmpty() && "SUCCEEDED".equals(taskRows.get(0).get("status"))) return ResultUtils.success(true);
            if (taskRows.isEmpty() || !worker.equals(taskRows.get(0).get("leaseOwner")))
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "AI 任务租约已变更", "AI_LEASE_LOST");
        }
        if(root.hasNonNull("siteHelp")) {
            completeSiteHelp(id,root);return ResultUtils.success(true);
        }
        if (root.hasNonNull("privateProblem")) {
            completePrivateProblem(id, root);
            return ResultUtils.success(true);
        }
        if (root.hasNonNull("feedback")) {
            completeFeedback(id,root);
            return ResultUtils.success(true);
        }
        if (root.hasNonNull("maintenanceProposal")) {
            completeMaintenanceProposal(id,root);
            return ResultUtils.success(true);
        }
        if (!root.hasNonNull("report")) {
            completePlanTask(id, root);
            return ResultUtils.success(true);
        }
        JsonNode report = root.path("report");
        validateReport(report);
        long inTokens = boundedLong(root.path("inputTokens").asLong(), 0, 2_000_000);
        long outTokens = boundedLong(root.path("outputTokens").asLong(), 0, 2_000_000);
        double cost = root.path("estimatedCostUsd").asDouble(-1);
        if (!Double.isFinite(cost) || cost < 0 || cost > 100) throw new BusinessException(ErrorCode.PARAMS_ERROR);

        List<Map<String, Object>> tasks = jdbc.query("SELECT ownerUserId,resourceId,modelId,promptVersion,status "
                        + "FROM ai_task WHERE id=? AND type='ANALYSIS' FOR UPDATE", (rs, row) -> {
                    Map<String, Object> item = new LinkedHashMap<>(); item.put("owner", rs.getLong("ownerUserId"));
                    item.put("resource", rs.getString("resourceId")); item.put("model", rs.getString("modelId"));
                    item.put("prompt", rs.getString("promptVersion")); item.put("status", rs.getString("status")); return item;
                }, id);
        if (tasks.isEmpty() || !"RUNNING".equals(tasks.get(0).get("status"))) throw new BusinessException(ErrorCode.OPERATION_ERROR, "AI 任务已完成或未运行");
        Map<String, Object> task = tasks.get(0);
        long owner = ((Number) task.get("owner")).longValue();
        long submitId = Long.parseLong(String.valueOf(task.get("resource")));
        String sourceHash = jdbc.queryForObject("SELECT SHA2(CONCAT(COALESCE(code,''),'|',COALESCE(problemVersionId,''),'|',COALESCE(privateProblemVersionId,'')),256) "
                + "FROM question_submit WHERE id=? AND userId=? AND isDelete=0", String.class, submitId, owner);
        String verdict = verdictForSubmission(submitId, owner);
        if("SYSTEM_ERROR".equals(verdict)) throw new BusinessException(ErrorCode.OPERATION_ERROR,"判题系统错误不生成代码分析报告","SYSTEM_ERROR");
        String reportJson = report.toString();
        jdbc.update("INSERT INTO ai_analysis (submissionId,userId,sourceHash,verdict,reportJson,modelId,promptVersion) "
                        + "VALUES (?,?,?,?,?,?,?)", submitId, owner, sourceHash, verdict, reportJson,
                currentModelId.isBlank() ? task.get("model") : currentModelId, task.get("prompt"));
        int finished = jdbc.update("UPDATE ai_task SET status='SUCCEEDED',stage='COMPLETE',inputTokens=?,outputTokens=?, "
                        + "estimatedCost=?,leaseOwner=NULL,leaseExpireAt=NULL,completedAt=NOW(),errorCode=NULL,errorMessage=NULL "
                        + "WHERE id=? AND status='RUNNING'", inTokens, outTokens, cost, id);
        if (finished != 1) throw new BusinessException(ErrorCode.OPERATION_ERROR, "AI 任务状态冲突");
        callLedger.settle(id, "COMMITTED", inTokens, outTokens, cost);
        jdbc.update("INSERT INTO ai_action_audit (taskId,actorType,actorId,action,resourceType,resourceId,inputHash,outcome) "
                + "VALUES (?,'AI','nemo-agent','ANALYSIS_COMPLETE','SUBMISSION',?,?,'SUCCEEDED')", id,
                Long.toString(submitId), sourceHash);
        return ResultUtils.success(true);
    }

    @PostMapping("/{id}/fail")
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse<Boolean> fail(@PathVariable String id, @RequestBody byte[] body, HttpServletRequest request) {
        verifySignature(request, body);
        JsonNode root = parse(body);
        String code = root.path("errorCode").asText("MODEL_ERROR");
        String worker = root.path("workerId").asText("");
        if (!worker.isBlank()) {
            String owner = jdbc.queryForObject("SELECT leaseOwner FROM ai_task WHERE id=? FOR UPDATE", String.class, id);
            if (!worker.equals(owner)) return ResultUtils.success(false);
        }
        if (!List.of("MODEL_ERROR", "MODEL_TIMEOUT", "INVALID_OUTPUT", "BUDGET_EXHAUSTED", "CONTEXT_UNAVAILABLE", "GOAL_UNSUPPORTED").contains(code))
            code = "MODEL_ERROR";
        List<Map<String,String>> taskTypes = jdbc.query("SELECT type,resourceId,requestJson,ownerUserId FROM ai_task WHERE id=? AND status='RUNNING'",
                (rs, row) -> {Map<String,String> m=new LinkedHashMap<>();m.put("type",rs.getString("type"));m.put("resource",rs.getString("resourceId"));m.put("request",rs.getString("requestJson"));m.put("owner",rs.getString("ownerUserId"));return m;}, id);
        if (taskTypes.isEmpty() || !List.of("ANALYSIS", "PLAN", "PLAN_ADJUST", "PRIVATE_PROBLEM", "FEEDBACK", "INSPECTION", "SITE_HELP").contains(taskTypes.get(0).get("type")))
            return ResultUtils.success(false);
        Integer attempts=jdbc.queryForObject("SELECT attemptCount FROM ai_task WHERE id=?",Integer.class,id);
        boolean exhaustedPrivate="PRIVATE_PROBLEM".equals(taskTypes.get(0).get("type")) && ("INVALID_OUTPUT".equals(code)
                || Boolean.TRUE.equals(jdbc.queryForObject("SELECT startedAt<=DATE_SUB(NOW(),INTERVAL 900 SECOND) FROM ai_task WHERE id=?",Boolean.class,id)));
        if(!exhaustedPrivate && List.of("MODEL_ERROR","MODEL_TIMEOUT","INVALID_OUTPUT").contains(code) && attempts!=null && attempts<maxAttempts) {
            jdbc.update("UPDATE ai_task SET status='QUEUED',stage='RETRY',errorCode=?,errorMessage='AI 任务将自动重试',leaseOwner=NULL,leaseExpireAt=NULL WHERE id=? AND status='RUNNING'",code,id);
            jdbc.update("INSERT INTO ai_outbox(taskId,eventType,status,nextAttemptAt) VALUES (?,'AI_TASK','PENDING',DATE_ADD(NOW(),INTERVAL 10 SECOND)) "
                    + "ON DUPLICATE KEY UPDATE status='PENDING',retryCount=0,nextAttemptAt=DATE_ADD(NOW(),INTERVAL 10 SECOND),leaseExpireAt=NULL,lastError=NULL",id);
            return ResultUtils.success(true);
        }
        if (taskTypes.get(0).get("type").startsWith("PLAN")) jdbc.update(
                "UPDATE study_plan SET status='NEEDS_REVIEW' WHERE id=? AND status IN ('GENERATING','ADJUSTING')",
                taskTypes.get(0).get("resource"));
        if("PRIVATE_PROBLEM".equals(taskTypes.get(0).get("type"))) {
            jdbc.update("UPDATE private_problem SET status='REJECTED',rejectReason='生成任务失败，请重新生成' WHERE id=?",taskTypes.get(0).get("resource"));
            try { JsonNode req=parse(String.valueOf(taskTypes.get(0).get("request")).getBytes(StandardCharsets.UTF_8));
                fallbackPrivatePlanItem(taskTypes.get(0).get("resource"),req.path("fallbackProblemNo").asLong(0)); } catch(Exception ignored) { }
        }
        if("FEEDBACK".equals(taskTypes.get(0).get("type"))) {
            long feedbackId=Long.parseLong(taskTypes.get(0).get("resource"));
            int escalated=jdbc.update("UPDATE feedback SET agentStatus='ESCALATED',rowVersion=rowVersion+1 WHERE id=? AND userId=? AND agentStatus<>'TAKEN_OVER'",
                    feedbackId,Long.parseLong(taskTypes.get(0).get("owner")));
            if(escalated>0) queueEscalation(id,feedbackId,"AI 服务未能处理该反馈");
        }
        int updated = jdbc.update("UPDATE ai_task SET status='FAILED',errorCode=?,errorMessage=?,leaseOwner=NULL, "
                        + "leaseExpireAt=NULL,completedAt=NOW() WHERE id=? AND type IN ('ANALYSIS','PLAN','PLAN_ADJUST','PRIVATE_PROBLEM','FEEDBACK','INSPECTION','SITE_HELP') AND status='RUNNING'",
                code, "GOAL_UNSUPPORTED".equals(code)?parse(body).path("message").asText("请补充训练范围及输入输出要求").substring(0,Math.min(500,parse(body).path("message").asText("请补充训练范围及输入输出要求").length())):"AI 任务暂时无法完成，请稍后重试。", id);
        if (updated > 0 && "PRIVATE_PROBLEM".equals(taskTypes.get(0).get("type")))
            jdbc.update("UPDATE private_problem SET status='REJECTED',rejectReason='生成任务失败，请重新生成' WHERE id=? AND generationTaskId=?",
                    taskTypes.get(0).get("resource"), id);
        // Restore user quota, but retain the platform allowance for possibly billed calls.
        if (updated > 0) jdbc.update("UPDATE ai_usage SET state='RELEASED',reservedCost=CASE WHEN "
                + "EXISTS(SELECT 1 FROM ai_model_call c WHERE c.taskId=ai_usage.taskId) "
                + "OR ? IN ('MODEL_ERROR','MODEL_TIMEOUT','INVALID_OUTPUT') THEN reservedCost ELSE 0 END "
                + "WHERE taskId=? AND state='RESERVED'", code, id);
        return ResultUtils.success(updated > 0);
    }

    private void completePrivateProblem(String id, JsonNode root) {
        long inputTokens = boundedLong(root.path("inputTokens").asLong(), 0, 2_000_000);
        long outputTokens = boundedLong(root.path("outputTokens").asLong(), 0, 2_000_000);
        double cost = root.path("estimatedCostUsd").asDouble(-1);
        if (!Double.isFinite(cost) || cost < 0 || cost > 100) throw new BusinessException(ErrorCode.PARAMS_ERROR);
        List<Map<String,Object>> rows = jdbc.query("SELECT ownerUserId,resourceId,requestJson,status,promptVersion,modelId FROM ai_task WHERE id=? AND type='PRIVATE_PROBLEM' FOR UPDATE",
                (rs,row)-> { Map<String,Object> m=new LinkedHashMap<>(); m.put("owner",rs.getLong("ownerUserId")); m.put("resource",rs.getString("resourceId"));
                    m.put("request",rs.getString("requestJson")); m.put("status",rs.getString("status")); m.put("prompt",rs.getString("promptVersion")); m.put("model",rs.getString("modelId")); return m; },id);
        if(rows.isEmpty() || !"RUNNING".equals(rows.get(0).get("status"))) throw new BusinessException(ErrorCode.OPERATION_ERROR,"私人题任务状态冲突");
        Map<String,Object> task=rows.get(0); String problemId=String.valueOf(task.get("resource")); long owner=((Number)task.get("owner")).longValue();
        validationJobs.heartbeat(id,root.path("workerId").asText(),"PUBLISHING");
        // Require durable proof before entering publication; stale/unfinished callbacks cannot reject a live candidate.
        Map<String,Object> validationResult=validationJobs.proof(id,root.path("privateProblem"));
        try {
            JsonNode draft=root.path("privateProblem");
            if(!draft.isObject()) throw new IllegalArgumentException("题目草稿无效");
            String title=requiredText(draft,"title",3,120), content=requiredText(draft,"contentMarkdown",100,20000);
            String difficulty=draft.path("difficulty").asText();
            if(!List.of("EASY","MEDIUM","HARD").contains(difficulty)) throw new IllegalArgumentException("难度无效");
            if(!difficulty.equals(parse(String.valueOf(task.get("request")).getBytes(StandardCharsets.UTF_8)).path("difficulty").asText("MEDIUM")))throw new IllegalArgumentException("生成难度与固定槽位不一致");
            JsonNode tagsNode=draft.path("tags"); if(!tagsNode.isArray() || tagsNode.isEmpty() || tagsNode.size()>8) throw new IllegalArgumentException("标签数量无效");
            List<String> tags=new ArrayList<>(); for(JsonNode tag:tagsNode) { String t=tag.asText().trim(); if(t.isBlank()||t.length()>64) throw new IllegalArgumentException("标签无效"); if(!tags.contains(t)) tags.add(t); }
            JsonNode caseNode=draft.path("judgeCases"); if(!caseNode.isArray() || caseNode.size()<20 || caseNode.size()>100) throw new IllegalArgumentException("至少需要 20 组有效用例");
            List<JudgeCase> cases=new ArrayList<>(), samples=new ArrayList<>(); int hidden=0;
            for(JsonNode value:caseNode) { String in=requiredText(value,"input",0,12000), out=requiredText(value,"output",0,12000); boolean isHidden=value.path("isHidden").asBoolean(true);
                JudgeCase c=new JudgeCase(); c.setInput(in); c.setOutput(out); c.setIsHidden(isHidden); cases.add(c); if(isHidden) hidden++; else if(samples.size()<5) samples.add(c); }
            if(hidden<1 || samples.isEmpty()) throw new IllegalArgumentException("至少需要一个公开样例和一个隐藏用例");
            JudgeConfig config=json.treeToValue(draft.path("judgeConfig"),JudgeConfig.class);
            if(config.getTimeLimit()==null || config.getTimeLimit()<100 || config.getTimeLimit()>10000 || config.getMemoryLimit()==null || config.getMemoryLimit()<16384 || config.getMemoryLimit()>1048576)
                throw new IllegalArgumentException("资源限制超出允许范围");
            if(config.getOutputLimitBytes()==null) config.setOutputLimitBytes(1048576L);
            ProblemSpec spec=draft.hasNonNull("problemSpec") ? json.treeToValue(draft.path("problemSpec"),ProblemSpec.class) : null;
            if(spec!=null) {
                if(!"Solution".equals(spec.getClassName()) || spec.getMethodName()==null || spec.getMethodName().isBlank() || spec.getHarnessVersion()==null)
                    throw new IllegalArgumentException("核心代码签名无效");
                List<JudgeCase> fn=spec.getJudgeCases(); if(fn==null || fn.size()<20 || fn.size()>100) throw new IllegalArgumentException("核心代码模式需要 20 组用例");
            }
            Map<String,String> references=json.convertValue(draft.path("referenceSolutions"),new com.fasterxml.jackson.core.type.TypeReference<>(){});
            java.util.Set<String> expected=new java.util.HashSet<>(); for(String lang:List.of("java","python","cpp")){ expected.add(lang+":ACM"); if(spec!=null) expected.add(lang+":FUNCTION"); }
            if(references==null || !references.keySet().equals(expected)) throw new IllegalArgumentException("语言或模式参考实现不完整");
            Question q=new Question(); q.setTitle(title); q.setContent(content); q.setTags(json.writeValueAsString(tags)); q.setJudgeCase(cases); q.setJudgeConfig(config); q.setProblemSpec(spec);
            if(validationResult==null || !Boolean.TRUE.equals(validationResult.get("passed"))) throw new IllegalStateException("参考实现未通过沙箱验证");
            String version=UUID.randomUUID().toString(); String casesJson=json.writeValueAsString(cases), sampleJson=json.writeValueAsString(samples);
            String specJson=spec==null?"null":json.writeValueAsString(spec); String configJson=json.writeValueAsString(config); String refsJson=json.writeValueAsString(references);
            String canonical=title+"\n"+content+"\n"+casesJson+"\n"+specJson+"\n"+configJson;
            String hash=hex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
            jdbc.update("INSERT INTO private_problem_version(id,privateProblemId,versionNumber,contentMarkdown,sampleJson,problemSpecJson,judgeCaseJson,judgeConfigJson,referenceJson,validationJson,contentHash,status) VALUES (?,?,1,?,?,?,?,?,?,?,?, 'READY')",
                    version,problemId,content,sampleJson,specJson,casesJson,configJson,refsJson,json.writeValueAsString(validationResult),hash);
            jdbc.update("UPDATE private_problem SET title=?,tagsJson=?,difficulty=?,status='READY',currentVersionId=?,rejectReason=NULL WHERE id=? AND ownerUserId=? AND generationTaskId=?",
                    title,json.writeValueAsString(tags),difficulty,version,problemId,owner,id);
            jdbc.update("UPDATE study_plan_item SET privateVersionId=?,titleSnapshot=? WHERE privateProblemId=? AND itemStatus='TODO'",
                    version,title,problemId);
            finishGeneratedTask(id,root,"SUCCEEDED","PRIVATE_PROBLEM_READY",problemId,inputTokens,outputTokens,cost,task);
        } catch(Exception failure) {
            // Publication is atomic: a DB failure must roll back every version/item/task write.
            if(failure instanceof RuntimeException runtime)throw runtime;
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"已验证候选无法发布，请重新校验","INVALID_OUTPUT");
        }
    }

    private void fallbackPrivatePlanItem(String privateProblemId,long fallbackNo) {
        if(fallbackNo<1) return;
        List<Map<String,Object>> problem=jdbc.query("SELECT p.currentQuestionId,q.problemVersion,q.title FROM question_public_number p JOIN question q ON q.id=p.currentQuestionId WHERE p.problemNo=? AND p.status='ONLINE' AND q.isPublished=1 AND q.isDelete=0",
                (rs,row)->Map.<String,Object>of("id",rs.getLong("currentQuestionId"),"version",rs.getInt("problemVersion"),"title",rs.getString("title")),fallbackNo);
        if(problem.isEmpty()) {
            jdbc.update("UPDATE study_plan_item SET itemStatus='UNAVAILABLE',titleSnapshot='私人题生成失败，回退题已下线',reason='私人题生成失败，预设公开回退题已下线，请重新调整计划' WHERE privateProblemId=? AND itemStatus='TODO'",privateProblemId);
            return;
        }
        Map<String,Object> q=problem.get(0);
        jdbc.update("UPDATE study_plan_item SET source='PUBLIC',problemNo=?,publicQuestionId=?,publicVersion=?,privateProblemId=NULL,privateVersionId=NULL,titleSnapshot=?,reason=?,itemStatus='TODO' WHERE privateProblemId=? AND itemStatus='TODO'",
                fallbackNo,q.get("id"),q.get("version"),q.get("title"),"私人题生成或校验失败，已改为公开题 #"+fallbackNo,privateProblemId);
    }

    private void finishGeneratedTask(String id,JsonNode root,String status,String action,String problemId,long in,long out,double cost,Map<String,Object> task) {
        int updated=jdbc.update("UPDATE ai_task SET status=?,stage=?,resultJson=?,inputTokens=?,outputTokens=?,estimatedCost=?,leaseOwner=NULL,leaseExpireAt=NULL,completedAt=NOW(),errorCode=?,errorMessage=? WHERE id=? AND status='RUNNING'",
                status,"COMPLETE", status.equals("SUCCEEDED")?"{\"validated\":true}":"{}",in,out,cost,status.equals("FAILED")?"INVALID_OUTPUT":null,
                status.equals("FAILED")?"私人题未通过格式或沙箱验证":null,id);
        if(updated!=1) throw new BusinessException(ErrorCode.OPERATION_ERROR,"任务状态冲突");
        callLedger.settle(id, status.equals("SUCCEEDED")?"COMMITTED":"RELEASED", in, out, cost);
        jdbc.update("INSERT INTO ai_action_audit(taskId,actorType,actorId,action,resourceType,resourceId,inputHash,outcome,detail) VALUES (?,'AI','nemo-agent',?,'PRIVATE_PROBLEM',?,SHA2(?,256),?,?)",
                id,action,problemId,String.valueOf(task.get("request")),status,status.equals("FAILED")?"validation rejected":"sandbox validated");
    }

    private String requiredText(JsonNode object,String key,int min,int max) {
        JsonNode node=object.path(key);
        if(!node.isTextual()) throw new IllegalArgumentException("字段无效："+key);
        String value=node.asText();
        if(value.length()<min||value.length()>max) throw new IllegalArgumentException("字段长度无效："+key);
        return value;
    }

    private Map<String, Object> planContext(String type, long owner, String planId, String requestJson) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("taskType", type); context.put("planId", planId);
        try { context.put("request", json.readTree(requestJson)); }
        catch (Exception e) { throw new BusinessException(ErrorCode.PARAMS_ERROR, "计划任务缺少有效请求"); }
        List<Map<String, Object>> catalog = jdbc.query("SELECT p.problemNo,cur.title,cur.tags,cur.problemVersion "
                        + "FROM question_public_number p JOIN question cur ON cur.id=p.currentQuestionId "
                        + "WHERE p.status='ONLINE' AND cur.isPublished=1 AND cur.isDelete=0 ORDER BY p.problemNo",
                (rs, row) -> {
                    Map<String, Object> item = new LinkedHashMap<>(); item.put("problemNo", rs.getLong("problemNo"));
                    item.put("title", rs.getString("title")); item.put("tags", parseList(rs.getString("tags")));
                    item.put("version", rs.getInt("problemVersion"));
                    String tags = String.valueOf(item.get("tags"));
                    item.put("difficulty", tags.contains("困难") || tags.contains("Hard") ? "HARD" :
                            tags.contains("中等") || tags.contains("Medium") ? "MEDIUM" : "EASY");
                    return item;
                });
        JsonNode input=(JsonNode)context.get("request");
        if("CATALOG_V2".equals(input.path("inputVersion").asText()))catalog=json.convertValue(input.path("candidateSnapshot"),new com.fasterxml.jackson.core.type.TypeReference<List<Map<String,Object>>>(){});
        context.put("catalog", catalog);
        context.put("completedProblemNos", jdbc.query("SELECT DISTINCT q.problemNo FROM question_submit s "
                        + "JOIN question q ON q.id=s.questionId WHERE s.userId=? AND s.problemSource='PUBLIC' "
                        + "AND s.status=2 AND s.isDelete=0 AND JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(s.judgeInfo),s.judgeInfo,'{}'),'$.message'))='accepted' AND q.problemNo IS NOT NULL",
                (rs, row) -> rs.getLong(1), owner));
        if ("PLAN_ADJUST".equals(type)) {
            List<Map<String, Object>> items = jdbc.query("SELECT i.dayNumber,i.orderNumber,i.source,i.problemNo,i.titleSnapshot,i.reason,i.itemStatus,q.tags "
                            + "FROM study_plan_item i LEFT JOIN question q ON q.id=i.publicQuestionId WHERE i.planId=? ORDER BY i.dayNumber,i.orderNumber", (rs, row) -> {
                        Map<String, Object> item = new LinkedHashMap<>(); item.put("dayNumber", rs.getInt("dayNumber"));
                        item.put("orderNumber", rs.getInt("orderNumber")); item.put("source", rs.getString("source"));
                        item.put("problemNo", rs.getObject("problemNo")); item.put("title", rs.getString("titleSnapshot"));
                        item.put("reason", rs.getString("reason")); item.put("status", rs.getString("itemStatus"));item.put("tags",parseList(rs.getString("tags"))); return item;
                    }, planId);
            context.put("currentItems", items);
            context.put("completedProblemNos", items.stream().filter(item -> "DONE".equals(item.get("status")))
                    .map(item -> item.get("problemNo")).filter(java.util.Objects::nonNull).toList());
        } else { context.put("currentItems", List.of());if(input.path("allowReview").asBoolean())context.put("completedProblemNos",List.of()); }
        context.put("privateProblemsEnabled", privateProblemsEnabled);
        return context;
    }

    @Transactional(rollbackFor=Exception.class)
    protected void completeSiteHelp(String id,JsonNode root) {
        var rows=jdbc.queryForList("SELECT status FROM ai_task WHERE id=? AND type='SITE_HELP' FOR UPDATE",id);
        if(rows.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        if("SUCCEEDED".equals(rows.get(0).get("status")))return;
        if(!"RUNNING".equals(rows.get(0).get("status")))throw new BusinessException(ErrorCode.OPERATION_ERROR);
        Map<String,Object> answer=siteHelpPolicy.reply(root.path("siteHelp"));
        long input=boundedLong(root.path("inputTokens").asLong(),0,2_000_000),output=boundedLong(root.path("outputTokens").asLong(),0,2_000_000);
        double cost=root.path("estimatedCostUsd").asDouble(-1);if(!Double.isFinite(cost)||cost<0||cost>100)throw new BusinessException(ErrorCode.PARAMS_ERROR);
        String encoded;try{encoded=json.writeValueAsString(answer);}catch(Exception e){throw new BusinessException(ErrorCode.SYSTEM_ERROR);}
        jdbc.update("UPDATE ai_task SET status='SUCCEEDED',stage='COMPLETE',resultJson=?,inputTokens=?,outputTokens=?,estimatedCost=?,leaseOwner=NULL,leaseExpireAt=NULL,completedAt=NOW(),errorCode=NULL,errorMessage=NULL WHERE id=? AND type='SITE_HELP' AND status='RUNNING'",encoded,input,output,cost,id);
        callLedger.settle(id,"COMMITTED",input,output,cost);
    }

    private Map<String,Object> feedbackContext(String taskId,long owner,long feedbackId) {
        if(!feedbackAgentEnabled) throw new BusinessException(ErrorCode.OPERATION_ERROR,"反馈 AI 助手已关闭");
        List<Map<String,Object>> rows=jdbc.query("SELECT id,type,title,content,problemNo,rowVersion,agentStatus,status FROM feedback WHERE id=? AND userId=? AND isDelete=0",
                (rs,row)->{Map<String,Object> m=new LinkedHashMap<>();m.put("id",rs.getLong("id"));m.put("type",rs.getString("type"));m.put("title",rs.getString("title"));m.put("content",rs.getString("content"));m.put("problemNo",rs.getObject("problemNo"));m.put("rowVersion",rs.getLong("rowVersion"));m.put("agentStatus",rs.getString("agentStatus"));m.put("status",rs.getString("status"));return m;},feedbackId,owner);
        if(rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        Map<String,Object> context=rows.get(0); context.put("taskId",taskId);context.put("ownerUserId",owner);context.put("taskType","FEEDBACK");
        context.put("faqCatalog",List.of(Map.of("key","submission_refresh","scope","如何刷新刚提交的判题结果"),
                Map.of("key","submission_history","scope","如何查看本人提交记录"),
                Map.of("key","editor_draft","scope","代码草稿自动保存与恢复方式"),
                Map.of("key","account_access","scope","账号登录与权限基础说明")));
        return context;
    }

    private Map<String,Object> inspectionContext(String taskId,String rawRequest) {
        JsonNode requestJson=parse(rawRequest.getBytes(StandardCharsets.UTF_8));
        long problemNo=boundedLong(requestJson.path("problemNo").asLong(),1,Long.MAX_VALUE);
        List<Map<String,Object>> rows=jdbc.query("SELECT p.problemNo,p.currentQuestionId,p.lifecycleVersion,p.status,q.problemVersion,q.rowVersion,q.title,q.content,q.tags "
                        + "FROM question_public_number p JOIN question q ON q.id=p.currentQuestionId "
                        + "WHERE p.problemNo=? AND p.status='ONLINE' AND q.isPublished=1 AND q.isDelete=0",
                (rs,row)->{Map<String,Object> m=new LinkedHashMap<>();m.put("problemNo",rs.getLong("problemNo"));m.put("questionId",rs.getLong("currentQuestionId"));
                    m.put("lifecycleVersion",rs.getLong("lifecycleVersion"));m.put("problemVersion",rs.getInt("problemVersion"));m.put("rowVersion",rs.getLong("rowVersion"));
                    m.put("title",rs.getString("title"));m.put("content",rs.getString("content"));m.put("tags",parseList(rs.getString("tags")));return m;},problemNo);
        if(rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        Map<String,Object> source=rows.get(0);
        for(String key:List.of("questionId","problemVersion","lifecycleVersion","rowVersion"))
            if(requestJson.path(key).asLong(-1)!=((Number)source.get(key)).longValue())
                throw new BusinessException(ErrorCode.OPERATION_ERROR,"巡检期间题目版本已变化","STALE_PROPOSAL");
        Map<String,Object> safe=new LinkedHashMap<>();
        for(String key:List.of("problemNo","title","content","tags")) safe.put(key,source.get(key));
        Map<String,Object> context=new LinkedHashMap<>();context.put("taskId",taskId);context.put("taskType","INSPECTION");
        context.put("inspectionProblem",safe);return context;
    }

    @Transactional(rollbackFor = Exception.class)
    protected void completeMaintenanceProposal(String id,JsonNode root) {
        JsonNode proposal=root.path("maintenanceProposal");
        if(!proposal.isObject()||!proposal.path("hasSuggestion").isBoolean()) throw new BusinessException(ErrorCode.PARAMS_ERROR,"维护建议结构无效");
        long input=boundedLong(root.path("inputTokens").asLong(),0,2_000_000),output=boundedLong(root.path("outputTokens").asLong(),0,2_000_000);
        double cost=root.path("estimatedCostUsd").asDouble(-1);if(!Double.isFinite(cost)||cost<0||cost>100)throw new BusinessException(ErrorCode.PARAMS_ERROR);
        List<Map<String,Object>> rows=jdbc.query("SELECT ownerUserId,resourceId,requestJson,status FROM ai_task WHERE id=? AND type='INSPECTION' FOR UPDATE",
                (rs,row)->{Map<String,Object> m=new LinkedHashMap<>();m.put("owner",rs.getLong("ownerUserId"));m.put("resource",rs.getString("resourceId"));m.put("request",rs.getString("requestJson"));m.put("status",rs.getString("status"));return m;},id);
        if(rows.isEmpty()||!"RUNNING".equals(rows.get(0).get("status")))throw new BusinessException(ErrorCode.OPERATION_ERROR,"巡检任务状态冲突");
        Map<String,Object> task=rows.get(0);JsonNode requestJson=parse(String.valueOf(task.get("request")).getBytes(StandardCharsets.UTF_8));
        if(!maintenanceEnabled)throw new BusinessException(ErrorCode.OPERATION_ERROR,"AI 题库巡检已关闭");
        boolean hasSuggestion=proposal.path("hasSuggestion").asBoolean();String taskStatus=hasSuggestion?"WAITING_REVIEW":"SUCCEEDED";
        String proposalId=null;
        if(hasSuggestion) {
            String title=requiredText(proposal,"proposedTitle",3,80),content=requiredText(proposal,"proposedContentMarkdown",20,8192);
            String summary=requiredText(proposal,"summary",8,1200);JsonNode evidence=proposal.path("evidence");
            if(!evidence.isArray()||evidence.size()>8)throw new BusinessException(ErrorCode.PARAMS_ERROR,"证据列表无效");
            String uncertainty=proposal.path("uncertainty").asText("");if(uncertainty.length()>1200)throw new BusinessException(ErrorCode.PARAMS_ERROR);
            Map<String,Object> proposed=new LinkedHashMap<>();proposed.put("title",title);proposed.put("content",content);proposed.put("summary",summary);
            proposed.put("uncertainty",uncertainty);proposed.put("scope","EDITORIAL_ONLY");proposed.put("baseRowVersion",requestJson.path("rowVersion").asLong());
            try {
                proposalId=UUID.randomUUID().toString();String canonical=json.writeValueAsString(proposed);
                String hash=hex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
                String diff="题目标题与题面措辞建议。该建议限定为文字校对，不包含算法、样例或判题数据修改。\n\n"+summary;
                Map<String,Object> validation=Map.of("schemaValid",true,"scope","EDITORIAL_ONLY","algorithmCorrectness","NOT_VERIFIED","judgeCases","NOT_READ_OR_CHANGED");
                jdbc.update("INSERT INTO ai_maintenance_proposal(id,taskId,problemNo,baseQuestionId,baseProblemVersion,baseLifecycleVersion,proposalJson,proposalHash,diffMarkdown,evidenceJson,validationJson,status) "
                                +"VALUES (?,?,?,?,?,?,?,?,?,?,?,'WAITING_REVIEW')",proposalId,id,requestJson.path("problemNo").asLong(),requestJson.path("questionId").asLong(),
                        requestJson.path("problemVersion").asInt(),requestJson.path("lifecycleVersion").asLong(),canonical,hash,diff,evidence.toString(),json.writeValueAsString(validation));
            }catch(Exception e){throw new BusinessException(ErrorCode.SYSTEM_ERROR,"维护建议保存失败");}
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("hasSuggestion",hasSuggestion);if(proposalId!=null)result.put("proposalId",proposalId);result.put("summary",proposal.path("summary").asText(""));
        jdbc.update("UPDATE ai_task SET status=?,stage='COMPLETE',resultJson=?,inputTokens=?,outputTokens=?,estimatedCost=?,leaseOwner=NULL,leaseExpireAt=NULL,completedAt=NOW() WHERE id=? AND status='RUNNING'",
                taskStatus,encodeJson(result),input,output,cost,id);
        callLedger.settle(id, "COMMITTED", input, output, cost);
        jdbc.update("INSERT INTO ai_action_audit(taskId,actorType,actorId,action,resourceType,resourceId,inputHash,outcome,detail) VALUES (?,'AI','nemo-agent',?,'PUBLIC_PROBLEM',?,SHA2(?,256),?,?)",
                id,hasSuggestion?"MAINTENANCE_PROPOSAL":"MAINTENANCE_NO_CHANGE",String.valueOf(task.get("resource")),String.valueOf(task.get("request")),taskStatus,hasSuggestion?"editorial proposal; correctness not verified":"no editorial change proposed");
    }

    @Transactional(rollbackFor = Exception.class)
    protected void completeFeedback(String id,JsonNode root) {
        JsonNode decision=root.path("feedback");
        if(!decision.isObject() || !decision.path("canResolve").isBoolean()) throw new BusinessException(ErrorCode.PARAMS_ERROR,"反馈决策结构无效");
        long input=boundedLong(root.path("inputTokens").asLong(),0,2_000_000),output=boundedLong(root.path("outputTokens").asLong(),0,2_000_000);
        double cost=root.path("estimatedCostUsd").asDouble(-1); if(!Double.isFinite(cost)||cost<0||cost>100) throw new BusinessException(ErrorCode.PARAMS_ERROR);
        List<Map<String,Object>> tasks=jdbc.query("SELECT ownerUserId,resourceId,requestJson,status FROM ai_task WHERE id=? AND type='FEEDBACK' FOR UPDATE",
                (rs,row)->{Map<String,Object> m=new LinkedHashMap<>();m.put("owner",rs.getLong("ownerUserId"));m.put("resource",rs.getLong("resourceId"));m.put("request",rs.getString("requestJson"));m.put("status",rs.getString("status"));return m;},id);
        if(tasks.isEmpty()||!"RUNNING".equals(tasks.get(0).get("status"))) throw new BusinessException(ErrorCode.OPERATION_ERROR,"反馈任务状态冲突");
        Map<String,Object> task=tasks.get(0); long feedbackId=((Number)task.get("resource")).longValue();
        JsonNode request=parse(String.valueOf(task.get("request")).getBytes(StandardCharsets.UTF_8)); long expected=request.path("rowVersion").asLong(-1);
        boolean resolve=decision.path("canResolve").asBoolean(); String key=decision.path("faqKey").asText(""); String reply=faqReply(key);
        if(resolve && reply==null) throw new BusinessException(ErrorCode.PARAMS_ERROR,"Agent 只能选择已审核的固定帮助条目");
        List<Map<String,Object>> current=jdbc.query("SELECT rowVersion,agentStatus,status,type,title,content FROM feedback WHERE id=? AND userId=? AND isDelete=0 FOR UPDATE",
                (rs,row)->{Map<String,Object> m=new LinkedHashMap<>();m.put("version",rs.getLong("rowVersion"));m.put("agent",rs.getString("agentStatus"));m.put("status",rs.getString("status"));m.put("type",rs.getString("type"));m.put("title",rs.getString("title"));m.put("content",rs.getString("content"));return m;},feedbackId,task.get("owner"));
        boolean stillOwned=!current.isEmpty() && ((Number)current.get(0).get("version")).longValue()==expected
                && !"TAKEN_OVER".equals(current.get(0).get("agent")) && "PENDING".equals(current.get(0).get("status"));
        if(resolve && stillOwned && !faqMatches(key,String.valueOf(current.get(0).get("type")),String.valueOf(current.get(0).get("title")),String.valueOf(current.get(0).get("content")))) {
            resolve=false; reply=null;
            if(decision instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
                object.put("canResolve",false);object.putNull("faqKey");object.put("escalationReason","反馈内容不匹配已审核的帮助条目");
            }
        }
        boolean applied=false;
        if(stillOwned && resolve) applied=jdbc.update("UPDATE feedback SET status='RESOLVED',adminReply=?,replySource='AI',agentStatus='AUTO_REPLIED',handledBy=NULL,handledAt=NOW(),rowVersion=rowVersion+1 WHERE id=? AND userId=? AND rowVersion=? AND agentStatus<>'TAKEN_OVER'",
                reply,feedbackId,task.get("owner"),expected)==1;
        else if(stillOwned) applied=jdbc.update("UPDATE feedback SET agentStatus='ESCALATED',rowVersion=rowVersion+1 WHERE id=? AND userId=? AND rowVersion=? AND agentStatus<>'TAKEN_OVER'",
                feedbackId,task.get("owner"),expected)==1;
        String taskStatus=applied||!stillOwned?"SUCCEEDED":"CANCELLED";
        jdbc.update("UPDATE ai_task SET status=?,stage=?,resultJson=?,inputTokens=?,outputTokens=?,estimatedCost=?,leaseOwner=NULL,leaseExpireAt=NULL,completedAt=NOW(),errorCode=NULL,errorMessage=NULL WHERE id=? AND status='RUNNING'",
                taskStatus,"COMPLETE",decision.toString(),input,output,cost,id);
        callLedger.settle(id, "COMMITTED", input, output, cost);
        if(stillOwned && !resolve && applied) {
            String reason=decision.path("escalationReason").asText("").trim();
            if(reason.isBlank()) reason="该反馈需要管理员判断";
            queueEscalation(id,feedbackId,reason);
        }
        jdbc.update("INSERT INTO ai_action_audit(taskId,actorType,actorId,action,resourceType,resourceId,inputHash,outcome,detail) VALUES (?,'AI','nemo-agent',?,'FEEDBACK',?,SHA2(?,256),?,?)",
                id,resolve?"FEEDBACK_AUTO_REPLY":"FEEDBACK_ESCALATE",Long.toString(feedbackId),decision.toString(),stillOwned?"SUCCEEDED":"CONFLICT",stillOwned?key:"admin takeover or feedback version changed");
    }

    private String faqReply(String key) {
        return switch(key) {
            case "submission_refresh" -> "提交后可在代码编辑区的“本次提交”面板点击“刷新结果”，也可在提交详情页查看判题状态；无需刷新整个浏览器页面。";
            case "submission_history" -> "登录后打开左侧“提交记录”，其中展示当前账号自己的提交记录。";
            case "editor_draft" -> "代码编辑器会自动保存草稿；重新打开对应题目、语言和模式后会尝试恢复。";
            case "account_access" -> "请通过登录页使用当前账号登录。若账号被限制或问题仍存在，请等待管理员处理这条反馈。";
            default -> null;
        };
    }

    private boolean faqMatches(String key,String type,String title,String content) {
        if(!"OTHER".equals(type)) return false;
        String text=(title+" "+content).toLowerCase(java.util.Locale.ROOT);
        return switch(key) {
            case "submission_refresh" -> containsAny(text,"刷新","refresh","结果不显示","判题结果");
            case "submission_history" -> containsAny(text,"提交记录","历史提交","submission history");
            case "editor_draft" -> containsAny(text,"草稿","自动保存","draft");
            case "account_access" -> containsAny(text,"登录流程","login page","如何登录");
            default -> false;
        };
    }

    private boolean containsAny(String value,String... candidates) {
        for(String candidate:candidates) if(value.contains(candidate.toLowerCase(java.util.Locale.ROOT))) return true;
        return false;
    }

    private void queueEscalation(String taskId,long feedbackId,String reason) {
        if(escalationEmail==null||escalationEmail.isBlank()) return;
        String subject="NemoOJ 反馈需要人工处理 #"+feedbackId;
        String body="反馈 #"+feedbackId+" 未能由固定帮助条目解决，需要管理员查看后台反馈处理页。\n原因摘要："+reason.substring(0,Math.min(reason.length(),700));
        jdbc.update("INSERT IGNORE INTO ai_notification_outbox(dedupeKey,recipient,subject,bodyText,status) VALUES (?,?,?,?,'PENDING')",
                "feedback:"+feedbackId+":escalation",escalationEmail,subject,body);
    }

    @Transactional(rollbackFor = Exception.class)
    protected void completePlanTask(String id, JsonNode root) {
        JsonNode result = root.path("result");
        JsonNode items = result.path("items");
        long inputTokens = boundedLong(root.path("inputTokens").asLong(), 0, 2_000_000);
        long outputTokens = boundedLong(root.path("outputTokens").asLong(), 0, 2_000_000);
        double cost = root.path("estimatedCostUsd").asDouble(-1);
        if (!Double.isFinite(cost) || cost < 0 || cost > 100 || !items.isArray() || items.isEmpty() || items.size() > 90)
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "学习计划输出格式无效");
        List<Map<String, Object>> tasks = jdbc.query("SELECT type,ownerUserId,resourceId,requestJson,promptVersion,status "
                        + "FROM ai_task WHERE id=? FOR UPDATE", (rs, row) -> {
                    Map<String, Object> task = new LinkedHashMap<>(); task.put("type", rs.getString("type"));
                    task.put("owner", rs.getLong("ownerUserId")); task.put("resource", rs.getString("resourceId"));
                    task.put("request", rs.getString("requestJson")); task.put("prompt", rs.getString("promptVersion"));
                    task.put("status", rs.getString("status")); return task;
                }, id);
        if (tasks.isEmpty() || !"RUNNING".equals(tasks.get(0).get("status"))
                || !List.of("PLAN", "PLAN_ADJUST").contains(tasks.get(0).get("type")))
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "计划任务状态冲突");
        Map<String, Object> task = tasks.get(0);
        String planId = String.valueOf(task.get("resource"));
        long owner = ((Number) task.get("owner")).longValue();
        String type = String.valueOf(task.get("type"));
        JsonNode requestJson = parse(String.valueOf(task.get("request")).getBytes(StandardCharsets.UTF_8));
        int days = requestJson.path("days").asInt(14);
        if ("PLAN".equals(type) && items.size() < days) throw new BusinessException(ErrorCode.PARAMS_ERROR, "计划必须覆盖全部日期");
        List<Map<String, Object>> plans = jdbc.query("SELECT status,planVersion,userId FROM study_plan WHERE id=? FOR UPDATE",
                (rs, row) -> Map.of("status", rs.getString("status"), "version", rs.getLong("planVersion"), "user", rs.getLong("userId")), planId);
        if (plans.isEmpty() || ((Number) plans.get(0).get("user")).longValue() != owner)
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        String expectedState = "PLAN".equals(type) ? "GENERATING" : "ADJUSTING";
        if (!expectedState.equals(plans.get(0).get("status"))) throw new BusinessException(ErrorCode.OPERATION_ERROR, "计划状态已变化");

        List<Map<String, Object>> completed = jdbc.query("SELECT dayNumber,orderNumber,source,problemNo,publicQuestionId,publicVersion,privateProblemId,privateVersionId,titleSnapshot,reason,completedBySubmitId,completedAt,itemStatus "
                        + "FROM study_plan_item WHERE planId=? AND itemStatus='DONE' ORDER BY dayNumber,orderNumber", (rs, row) -> {
                    Map<String,Object> item = new LinkedHashMap<>();
                    for (String key : List.of("dayNumber","orderNumber","source","problemNo","publicQuestionId","publicVersion","privateProblemId","privateVersionId","titleSnapshot","reason","completedBySubmitId","completedAt","itemStatus")) item.put(key, rs.getObject(key));
                    return item;
                }, planId);
        List<Map<String,Object>> previousPublicItems=List.of();
        if ("PLAN_ADJUST".equals(type)) {
            previousPublicItems=jdbc.query("SELECT dayNumber,problemNo FROM study_plan_item WHERE planId=? AND source='PUBLIC' AND itemStatus='TODO' ORDER BY dayNumber,orderNumber",
                    (rs,row)->Map.<String,Object>of("day",rs.getInt("dayNumber"),"number",rs.getLong("problemNo")),planId);
            long previousVersion = ((Number)plans.get(0).get("version")).longValue() - 1;
            List<Map<String,Object>> snapshot = jdbc.query("SELECT dayNumber,orderNumber,source,problemNo,titleSnapshot,reason,itemStatus,completedBySubmitId,completedAt "
                            + "FROM study_plan_item WHERE planId=? ORDER BY dayNumber,orderNumber", (rs,row)->{
                        Map<String,Object> item=new LinkedHashMap<>();
                        for(String key:List.of("dayNumber","orderNumber","source","problemNo","titleSnapshot","reason","itemStatus","completedBySubmitId","completedAt")) item.put(key,rs.getObject(key));
                        return item;
                    }, planId);
            jdbc.update("INSERT INTO study_plan_revision(planId,planVersion,itemsJson,changeRequest,taskId) VALUES (?,?,?,?,?)",
                    planId, previousVersion, encodeJson(snapshot), requestJson.path("changeRequest").asText(""), id);
            jdbc.update("DELETE FROM study_plan_item WHERE planId=? AND itemStatus<>'DONE'", planId);
        }

        java.util.Set<String> unique = new java.util.HashSet<>();
        java.util.Set<Long> usedNumbers = new java.util.HashSet<>();
        java.util.Set<Integer> scheduledDays=new java.util.HashSet<>();
        for (Map<String,Object> item : completed) {
            if (item.get("problemNo") != null) usedNumbers.add(((Number)item.get("problemNo")).longValue());
            unique.add(item.get("dayNumber")+":"+item.get("orderNumber"));
        }
        int privateCount=0;
        for (JsonNode item : items) {
            int day = item.path("dayNumber").asInt(0), order = item.path("orderNumber").asInt(0);
            long number = item.path("problemNo").asLong(0); String reason = item.path("reason").asText("").trim();
            boolean privateItem=item.path("privateGoal").isTextual();
            scheduledDays.add(day);
            if(privateItem && ++privateCount>5) throw new BusinessException(ErrorCode.PARAMS_ERROR,"混合题单最多生成 5 道私人题");
            if (day < 1 || day > days || order < 1 || order > 3 || reason.length() < 8 || reason.length() > 1000
                    || !unique.add(day + ":" + order) || (privateItem ? number != 0 : number < 1)
                    || (privateItem && (!"MIXED".equals(requestJson.path("sourceMode").asText()) || !privateProblemsEnabled)))
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "计划题目或排期不合法");
            if(privateItem) {
                long fallback=item.path("fallbackProblemNo").asLong(0);
                if(fallback<1 || !usedNumbers.add(fallback)) throw new BusinessException(ErrorCode.PARAMS_ERROR,"私人题必须绑定唯一的公开回退题");
                Map<String,Object> fallbackInfo=publicProblem(fallback);
                String privateGoal=item.path("privateGoal").asText().trim();
                if(privateGoal.length()<8 || privateGoal.length()>1000) throw new BusinessException(ErrorCode.PARAMS_ERROR);
                String privateId=schedulePrivateProblem(owner,planId,day,order,privateGoal,fallback,tagsFromRequest(requestJson));
                if(privateId==null) jdbc.update("INSERT INTO study_plan_item(planId,dayNumber,orderNumber,source,problemNo,publicQuestionId,publicVersion,titleSnapshot,reason,itemStatus) VALUES (?,?,?,'PUBLIC',?,?,?,?,?,'TODO')",
                        planId,day,order,fallback,fallbackInfo.get("questionId"),fallbackInfo.get("version"),fallbackInfo.get("title"),reason);
                else jdbc.update("INSERT INTO study_plan_item(planId,dayNumber,orderNumber,source,privateProblemId,titleSnapshot,reason,itemStatus) VALUES (?,?,?,'PRIVATE',?,?,?,'TODO')",
                        planId,day,order,privateId,"AI 题目生成中",reason);
                continue;
            }
            if(!usedNumbers.add(number)) throw new BusinessException(ErrorCode.PARAMS_ERROR,"计划题号重复");
            if("CATALOG_V2".equals(requestJson.path("inputVersion").asText())) {
                JsonNode candidate=null;for(JsonNode c:requestJson.path("candidateSnapshot"))if(c.path("problemNo").asLong()==number){candidate=c;break;}
                if(candidate==null)throw new BusinessException(ErrorCode.PARAMS_ERROR,"题目超出本次主题候选范围");
                Integer version=jdbc.queryForObject("SELECT q.problemVersion FROM question_public_number p JOIN question q ON q.id=p.currentQuestionId WHERE p.problemNo=? AND p.status='ONLINE'",Integer.class,number);
                if(version==null||version!=candidate.path("version").asInt())throw new BusinessException(ErrorCode.OPERATION_ERROR,"候选题已更新，请重新生成计划");
            }
            List<Map<String,Object>> problem = jdbc.query("SELECT p.currentQuestionId,q.problemVersion,q.title FROM question_public_number p "
                            + "JOIN question q ON q.id=p.currentQuestionId WHERE p.problemNo=? AND p.status='ONLINE' AND q.isPublished=1 AND q.isDelete=0",
                    (rs,row)->Map.<String,Object>of("questionId",rs.getLong("currentQuestionId"),"version",rs.getInt("problemVersion"),"title",rs.getString("title")), number);
            if (problem.isEmpty()) throw new BusinessException(ErrorCode.PARAMS_ERROR, "计划引用了当前不可用的题目");
            Map<String,Object> publicProblem = problem.get(0);
            jdbc.update("INSERT INTO study_plan_item(planId,dayNumber,orderNumber,source,problemNo,publicQuestionId,publicVersion,titleSnapshot,reason,itemStatus) "
                            + "VALUES (?,?,?,'PUBLIC',?,?,?,?,?,'TODO')", planId,day,order,number,publicProblem.get("questionId"),
                    publicProblem.get("version"),publicProblem.get("title"),reason);
        }
        if("PLAN".equals(type) && java.util.stream.IntStream.rangeClosed(1,days).anyMatch(day->!scheduledDays.contains(day)))
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"计划必须覆盖全部日期");
        if("PLAN_ADJUST".equals(type)) {
            List<Integer> covered=jdbc.query("SELECT DISTINCT dayNumber FROM study_plan_item WHERE planId=?",
                    (rs,row)->rs.getInt(1),planId);
            List<Map<String,Object>> online=jdbc.query("SELECT p.problemNo,p.currentQuestionId,q.problemVersion,q.title FROM question_public_number p "
                            +"JOIN question q ON q.id=p.currentQuestionId WHERE p.status='ONLINE' AND q.isPublished=1 AND q.isDelete=0 ORDER BY p.problemNo",
                    (rs,row)->Map.<String,Object>of("number",rs.getLong("problemNo"),"questionId",rs.getLong("currentQuestionId"),
                            "version",rs.getInt("problemVersion"),"title",rs.getString("title")));
            if("CATALOG_V2".equals(requestJson.path("inputVersion").asText())) {
                java.util.Map<Long,Integer> allowed=new java.util.HashMap<>();for(JsonNode c:requestJson.path("candidateSnapshot"))allowed.put(c.path("problemNo").asLong(),c.path("version").asInt());
                online=online.stream().filter(q->java.util.Objects.equals(allowed.get(((Number)q.get("number")).longValue()),((Number)q.get("version")).intValue())).toList();
            }
            final List<Map<String,Object>> eligible=online;
            for(int day=1;day<=days;day++) {
                if(covered.contains(day))continue;
                int missingDay=day;
                java.util.Set<Long> preferred=new java.util.HashSet<>();
                for(Map<String,Object> previous:previousPublicItems)
                    if(((Number)previous.get("day")).intValue()==missingDay)preferred.add(((Number)previous.get("number")).longValue());
                Map<String,Object> chosen=online.stream().filter(q->preferred.contains(((Number)q.get("number")).longValue())
                                && !usedNumbers.contains(((Number)q.get("number")).longValue())).findFirst()
                        .orElseGet(()->eligible.stream().filter(q->!usedNumbers.contains(((Number)q.get("number")).longValue())).findFirst().orElse(null));
                if(chosen==null)throw new BusinessException(ErrorCode.PARAMS_ERROR,"可用公开题不足，无法补全计划日期");
                long number=((Number)chosen.get("number")).longValue();usedNumbers.add(number);
                jdbc.update("INSERT INTO study_plan_item(planId,dayNumber,orderNumber,source,problemNo,publicQuestionId,publicVersion,titleSnapshot,reason,itemStatus) "
                                +"VALUES (?,?,1,'PUBLIC',?,?,?,?,?,'TODO')",planId,day,number,chosen.get("questionId"),
                        chosen.get("version"),chosen.get("title"),"AI 调整未覆盖本日，保留或补充公开题练习");
            }
        }
        if("CATALOG_V2".equals(requestJson.path("inputVersion").asText())) {
            java.util.Set<String> coveredTags=new java.util.HashSet<>();
            for(JsonNode c:requestJson.path("candidateSnapshot"))if(usedNumbers.contains(c.path("problemNo").asLong()))for(JsonNode tag:c.path("tags"))coveredTags.add(tag.asText());
            // Completed items may have been excluded from the fresh candidate snapshot during adjustment.
            for(var done:completed)if(done.get("problemNo")!=null) {var tagRows=jdbc.query("SELECT tags FROM question WHERE problemNo=? AND isPublished=1 ORDER BY problemVersion DESC LIMIT 1",(rs,row)->rs.getString(1),done.get("problemNo"));if(!tagRows.isEmpty()&&parseList(tagRows.get(0)) instanceof List<?> values)for(Object tag:values)coveredTags.add(String.valueOf(tag));}
            for(JsonNode tag:requestJson.path("tags"))if(!coveredTags.contains(tag.asText()))throw new BusinessException(ErrorCode.PARAMS_ERROR,"计划未覆盖所选主题："+tag.asText());
        }
        jdbc.update("UPDATE study_plan SET status='ACTIVE' WHERE id=? AND userId=?", planId, owner);
        int updated=jdbc.update("UPDATE ai_task SET status='SUCCEEDED',stage='COMPLETE',resultJson=?,inputTokens=?,outputTokens=?,estimatedCost=?,leaseOwner=NULL,leaseExpireAt=NULL,completedAt=NOW(),errorCode=NULL,errorMessage=NULL WHERE id=? AND status='RUNNING'",
                result.toString(),inputTokens,outputTokens,cost,id);
        if(updated!=1) throw new BusinessException(ErrorCode.OPERATION_ERROR,"AI 任务状态冲突");
        callLedger.settle(id, "COMMITTED", inputTokens, outputTokens, cost);
        jdbc.update("INSERT INTO ai_action_audit(taskId,actorType,actorId,action,resourceType,resourceId,inputHash,outcome) VALUES (?,'AI','nemo-agent',?,?,?,SHA2(?,256),'SUCCEEDED')",
                id,type,"STUDY_PLAN",planId,String.valueOf(task.get("request")));
    }

    private String schedulePrivateProblem(long owner,String planId,int day,int order,String goal,long fallback,List<String> tags) {
        Map<String,Object> onlineFallback=publicProblem(fallback);
        boolean canSpend=privateProblemsEnabled && taskReservation!=null && taskReservation.signum()>0
                && dailyBudget!=null && dailyBudget.signum()>0;
        if(canSpend) {
            canSpend=AiDailyBudget.canReserve(jdbc,LocalDate.now(ZoneId.of("Asia/Shanghai")),taskReservation,dailyBudget);
        }
        if(!canSpend) return null;
        String problemId=UUID.randomUUID().toString(),taskId=UUID.randomUUID().toString();
        Map<String,Object> request=new LinkedHashMap<>(); request.put("goal",goal); request.put("difficulty","MEDIUM"); request.put("tags",tags);
        request.put("studyPlanId",planId); request.put("fallbackProblemNo",fallback); request.put("fallbackQuestionId",onlineFallback.get("questionId"));
        String idempotency=hex(sha256((planId+":"+day+":"+order).getBytes(StandardCharsets.UTF_8)));
        jdbc.update("INSERT INTO private_problem(id,ownerUserId,title,tagsJson,difficulty,status,generationTaskId) VALUES (?,?,? ,? ,'MEDIUM','GENERATING',?)",
                problemId,owner,"AI 题目生成中",encodeJson(tags),taskId);
        jdbc.update("INSERT INTO ai_task(id,type,ownerUserId,resourceId,idempotencyKey,status,modelId,promptVersion,requestJson) VALUES (?,'PRIVATE_PROBLEM',?,?,?,'QUEUED',?,'private-problem-v1',?)",
                taskId,owner,problemId,idempotency,currentModelId,encodeJson(request));
        jdbc.update("INSERT INTO ai_usage(taskId,userId,usageDate,category,state,quotaExempt,reservedCost) VALUES (?,?,?,'PRIVATE_PROBLEM','RESERVED',1,?)",
                taskId,owner,LocalDate.now(ZoneId.of("Asia/Shanghai")),taskReservation);
        jdbc.update("INSERT INTO ai_outbox(taskId,eventType,status) VALUES (?,'AI_TASK','PENDING')",taskId);
        return problemId;
    }

    private Map<String,Object> publicProblem(long problemNo) {
        List<Map<String,Object>> rows=jdbc.query("SELECT p.currentQuestionId,q.problemVersion,q.title FROM question_public_number p JOIN question q ON q.id=p.currentQuestionId WHERE p.problemNo=? AND p.status='ONLINE' AND q.isPublished=1 AND q.isDelete=0",
                (rs,row)->Map.<String,Object>of("questionId",rs.getLong("currentQuestionId"),"version",rs.getInt("problemVersion"),"title",rs.getString("title")),problemNo);
        if(rows.isEmpty()) throw new BusinessException(ErrorCode.PARAMS_ERROR,"学习计划包含当前不可用的回退题目");
        return rows.get(0);
    }

    private List<String> tagsFromRequest(JsonNode request) {
        try { return json.convertValue(request.path("tags"),new com.fasterxml.jackson.core.type.TypeReference<>(){}); }
        catch(Exception ignored) { return List.of(); }
    }

    private byte[] sha256(byte[] value) { try { return MessageDigest.getInstance("SHA-256").digest(value); } catch(Exception e) { throw new IllegalStateException(e); } }
    private Object parseList(String raw) {
        try { return raw == null ? List.of() : json.readValue(raw, Object.class); }
        catch (Exception ignored) { return List.of(); }
    }

    private String encodeJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 结果无法保存"); }
    }

    private void addPublicProblem(Map<String, Object> context, Map<String, Object> submission) {
        if (!"PUBLIC".equals(submission.get("source"))) return;
        Long questionId = ((Number) submission.get("questionId")).longValue();
        Integer version = ((Number) submission.get("version")).intValue();
        List<Map<String, Object>> rows = jdbc.query("SELECT title,content,judgeCase FROM question_version WHERE questionId=? AND version=?",
                (rs, row) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("title", rs.getString("title")); item.put("content", rs.getString("content"));
                    item.put("cases", rs.getString("judgeCase") == null ? "[]" : rs.getString("judgeCase"));
                    return item;
                }, questionId, version);
        if (rows.isEmpty()) return;
        context.put("problemTitle", rows.get(0).get("title")); context.put("problemStatement", rows.get(0).get("content"));
        try {
            JsonNode cases = json.readTree(String.valueOf(rows.get(0).get("cases")));
            List<Map<String, String>> samples = new ArrayList<>();
            for (JsonNode item : cases) if (!item.path("isHidden").asBoolean(true)) {
                samples.add(Map.of("input", item.path("input").asText(""), "output", item.path("output").asText("")));
            }
            context.put("publicSamples", samples);
        } catch (Exception ignored) { context.put("publicSamples", List.of()); }
    }

    private void addPrivateProblem(Map<String, Object> context, Map<String, Object> submission, long owner) {
        if (!"PRIVATE".equals(submission.get("source"))) return;
        String versionId = String.valueOf(submission.get("privateVersionId"));
        List<Map<String, Object>> rows = jdbc.query("SELECT p.title,v.contentMarkdown,v.sampleJson FROM private_problem p "
                        + "JOIN private_problem_version v ON v.privateProblemId=p.id WHERE p.ownerUserId=? AND v.id=? AND v.status='READY'",
                (rs, row) -> {
                    Map<String, Object> item = new LinkedHashMap<>(); item.put("title", rs.getString("title"));
                    item.put("content", rs.getString("contentMarkdown")); item.put("samples", rs.getString("sampleJson"));
                    return item;
                }, owner, versionId);
        if (rows.isEmpty()) return;
        context.put("problemTitle", rows.get(0).get("title"));
        context.put("problemStatement", rows.get(0).get("content"));
        try { context.put("publicSamples", json.readTree(String.valueOf(rows.get(0).get("samples")))); }
        catch (Exception e) { context.put("publicSamples", List.of()); }
    }

    private String verdict(Map<String, Object> submission) {
        int status = ((Number) submission.get("status")).intValue();
        if (status == 2) return "ACCEPTED";
        try {
            JsonNode info = json.readTree(String.valueOf(submission.get("judgeInfo")));
            return info.path("message").asText("FAILED").toUpperCase();
        } catch (Exception ignored) { return "FAILED"; }
    }

    private String verdictForSubmission(long submitId, long owner) {
        List<Map<String, Object>> rows = jdbc.query("SELECT status,judgeInfo FROM question_submit WHERE id=? AND userId=?",
                (rs, row) -> Map.<String, Object>of("status", rs.getInt("status"),
                        "judgeInfo", rs.getString("judgeInfo") == null ? "{}" : rs.getString("judgeInfo")), submitId, owner);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        return verdict(rows.get(0));
    }

    private Object runtimeEvidence(Object raw) {
        try {
            JsonNode info = json.readTree(String.valueOf(raw));
            Map<String, Object> result = new LinkedHashMap<>();
            for (String field : List.of("message", "diagnosticPhase", "diagnosticMessage", "executedTime", "executedMemory"))
                if (info.hasNonNull(field)) result.put(field, info.get(field));
            return result;
        } catch (Exception ignored) { return Map.of(); }
    }

    private void validateReport(JsonNode report) {
        if (!report.isObject() || !report.path("verdictEvidence").isObject()
                || !report.path("likelyCause").isTextual() || !report.path("locations").isArray()
                || !report.path("suggestions").isArray() || !report.path("complexity").isObject()
                || !report.path("uncertainty").isTextual() || report.toString().length() > 60000
                || report.path("locations").size() > 20 || report.path("suggestions").size() > 12)
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "模型报告结构无效");
    }

    private long boundedLong(long value, long min, long max) {
        if (value < min || value > max) throw new BusinessException(ErrorCode.PARAMS_ERROR);
        return value;
    }

    private JsonNode parse(byte[] body) {
        // Private problem generation callbacks include independently validated cases and
        // reference solutions for all supported languages. Keep a finite cap while
        // allowing a complete candidate to reach the real sandbox validator.
        if (body == null || body.length > 4 * 1024 * 1024) throw new BusinessException(ErrorCode.PARAMS_ERROR);
        try { return json.readTree(body); } catch (Exception e) { throw new BusinessException(ErrorCode.PARAMS_ERROR); }
    }

    private void verifySignature(HttpServletRequest request, byte[] body) {
        if (sharedSecret == null || sharedSecret.length() < 32) throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        try {
            long timestamp = Long.parseLong(request.getHeader("X-Agent-Timestamp"));
            if (Math.abs(Instant.now().getEpochSecond() - timestamp) > 60) throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
            String rawBodyHash = hex(MessageDigest.getInstance("SHA-256").digest(body == null ? new byte[0] : body));
            String message = timestamp + "\n" + request.getMethod() + "\n" + request.getRequestURI()
                    + "\n" + rawBodyHash;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            byte[] actual = java.util.HexFormat.of().parseHex(request.getHeader("X-Agent-Signature"));
            if (!MessageDigest.isEqual(expected, actual)) throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        } catch (BusinessException e) { throw e; }
        catch (Exception e) { throw new BusinessException(ErrorCode.NO_AUTH_ERROR); }
    }

    private String hex(byte[] bytes) { return java.util.HexFormat.of().formatHex(bytes); }
}
