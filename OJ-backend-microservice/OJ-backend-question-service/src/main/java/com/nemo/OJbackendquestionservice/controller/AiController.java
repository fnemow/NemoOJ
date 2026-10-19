package com.nemo.OJbackendquestionservice.controller;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nemo.OJbackendcommon.common.BaseResponse;
import com.nemo.OJbackendcommon.common.ErrorCode;
import com.nemo.OJbackendcommon.common.ResultUtils;
import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendquestionservice.budget.AiDailyBudget;
import com.nemo.OJbackendmodel.model.entity.User;
import com.nemo.OJbackendquestionservice.service.AiAgentAvailability;
import com.nemo.OJserviceclient.UserFeignClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.math.BigDecimal;

/** User facing AI analysis/task API. Business ownership and quotas stay in Java/MySQL. */
@RestController
@RequestMapping("/ai")
public class AiController {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final UserFeignClient users;
    private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired private AiAgentAvailability agentAvailability;

    @Value("${ai.analysis.enabled:false}") private boolean analysisEnabled;
    @Value("${ai.learning.enabled:false}") private boolean planEnabled;
    @Value("${ai.private-problems.enabled:false}") private boolean privateProblemsEnabled;
    @Value("${ai.feedback.enabled:false}") private boolean feedbackAgentEnabled;
    @Value("${ai.model.enabled:false}") private boolean modelEnabled;
    @Value("${ai.limits.analysis-per-day:5}") private int dailyAnalysisLimit;
    @Value("${ai.limits.tasks-per-minute:5}") private int tasksPerMinute;
    @Value("${ai.budget.daily-usd:0}") private BigDecimal dailyBudget;
    @Value("${ai.budget.max-task-reservation-usd:0}") private BigDecimal taskBudgetReservation;
    @Value("${ai.model.id:}") private String modelId;

    public AiController(JdbcTemplate jdbc, UserFeignClient users, ObjectMapper json) {
        this.jdbc = jdbc;
        this.users = users;
        this.json = json;
    }

    @GetMapping("/entitlements")
    public BaseResponse<Map<String, Object>> entitlements(HttpServletRequest request) {
        User user = requireMember(request);
        boolean vip = activeVip(user.getId());
        LocalDate date = LocalDate.now(BUSINESS_ZONE);
        int used = jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage WHERE userId=? AND usageDate=? "
                + "AND quotaExempt=0 AND state IN ('RESERVED','COMMITTED')", Integer.class, user.getId(), date);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tier", vip ? "VIP" : "FREE");
        data.put("vipActive", vip);
        boolean enabled = modelConfigured();
        data.put("analysisEnabled", analysisEnabled && enabled);
        data.put("planEnabled", planEnabled && enabled && vip);
        data.put("privateProblemsEnabled", privateProblemsEnabled && enabled && vip);
        data.put("feedbackAgentEnabled", feedbackAgentEnabled && enabled);
        data.put("usedToday", vip ? 0 : used);
        data.put("dailyLimit", vip ? null : dailyAnalysisLimit);
        data.put("remainingToday", vip ? null : Math.max(0, dailyAnalysisLimit - used));
        data.put("businessDate", date.toString());
        return ResultUtils.success(data);
    }

    @PostMapping("/analyses")
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse<Map<String, Object>> createAnalysis(@RequestBody Map<String, Object> body,
                                                             HttpServletRequest request) {
        User user = requireMember(request);
        requireModelEnabled();
        long submissionId = positiveLong(body.get("submissionId"));
        String key = idempotencyKey(body.get("idempotencyKey"));

        var old = jdbc.query("SELECT id,status FROM ai_task WHERE ownerUserId=? AND type='ANALYSIS' "
                        + "AND idempotencyKey=? LIMIT 1", (rs, row) -> Map.<String, Object>of(
                        "taskId", rs.getString("id"), "status", rs.getString("status")), user.getId(), key);
        if (!old.isEmpty()) return ResultUtils.success(old.get(0));

        // Serialize per-user quota reservations. A failed login/ban check must not create a ledger row.
        String role = jdbc.queryForObject("SELECT userRole FROM `user` WHERE id=? AND isDelete=0 FOR UPDATE",
                String.class, user.getId());
        if (!"user".equals(role)) throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        var submission = jdbc.query("SELECT userId,status,judgeInfo FROM question_submit WHERE id=? AND isDelete=0",
                (rs, row) -> { Map<String,Object> item=new LinkedHashMap<>();item.put("user",rs.getLong("userId"));item.put("status",rs.getLong("status"));item.put("judgeInfo",rs.getString("judgeInfo"));return item; }, submissionId);
        if (submission.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        long status = ((Number)submission.get(0).get("status")).longValue();
        if (((Number)submission.get(0).get("user")).longValue() != user.getId()) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        if (status < 2) throw new BusinessException(ErrorCode.OPERATION_ERROR, "判题结束后才能生成分析");
        if (isSystemError(String.valueOf(submission.get(0).get("judgeInfo"))))
            throw new BusinessException(ErrorCode.OPERATION_ERROR,"判题系统错误不生成代码分析报告","SYSTEM_ERROR");

        var previous = jdbc.query("SELECT id FROM ai_analysis WHERE submissionId=? AND userId=? FOR UPDATE",
                (rs, row) -> rs.getLong(1), submissionId, user.getId());
        if (!previous.isEmpty()) {
            return ResultUtils.success(Map.of("analysisId", previous.get(0), "status", "SUCCEEDED", "reused", true));
        }
        var pending = jdbc.query("SELECT id,status FROM ai_task WHERE ownerUserId=? AND type='ANALYSIS' "
                        + "AND resourceId=? AND status IN ('QUEUED','RUNNING') ORDER BY createTime DESC LIMIT 1 FOR UPDATE",
                (rs, row) -> Map.<String, Object>of("taskId", rs.getString("id"),
                        "status", rs.getString("status"), "reused", true), user.getId(), Long.toString(submissionId));
        if (!pending.isEmpty()) return ResultUtils.success(pending.get(0));
        boolean vip = activeVip(user.getId());
        LocalDate date = LocalDate.now(BUSINESS_ZONE);
        if (!vip) {
            // A locking read bypasses an older REPEATABLE READ snapshot created
            // before the per-user row lock was acquired by concurrent requests.
            int used = jdbc.query("SELECT id FROM ai_usage WHERE userId=? AND usageDate=? "
                            + "AND quotaExempt=0 AND state IN ('RESERVED','COMMITTED') FOR UPDATE",
                    (rs, row) -> rs.getLong(1), user.getId(), date).size();
            if (used >= dailyAnalysisLimit) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "今日 AI 分析额度已用完", "QUOTA_EXCEEDED");
            }
        }
        enforceTaskCapacity(user.getId());
        reservePlatformBudget();
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ai_task (id,type,ownerUserId,resourceId,idempotencyKey,status,modelId,promptVersion) "
                        + "VALUES (?,'ANALYSIS',?,?,?,'QUEUED',?,?)", id, user.getId(), Long.toString(submissionId), key,
                modelId, "analysis-v1");
        jdbc.update("INSERT INTO ai_usage (taskId,userId,usageDate,category,state,quotaExempt,reservedCost) "
                        + "VALUES (?,?,?,'ANALYSIS','RESERVED',?,?)", id, user.getId(), date, vip, taskBudgetReservation);
        jdbc.update("INSERT INTO ai_outbox (taskId,eventType,status) VALUES (?,'AI_TASK','PENDING')", id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", id);
        result.put("status", "QUEUED");
        result.put("reused", false);
        return ResultUtils.success(result);
    }

    @GetMapping("/tasks/{id}")
    public BaseResponse<Map<String, Object>> getTask(@PathVariable String id, HttpServletRequest request) {
        User user = requireMember(request);
        var rows = jdbc.query("SELECT id,type,status,stage,errorCode,errorMessage,resourceId,createTime,completedAt "
                        + "FROM ai_task WHERE id=? AND ownerUserId=?", (rs, row) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("taskId", rs.getString("id")); item.put("type", rs.getString("type"));
                    item.put("status", rs.getString("status")); item.put("stage", rs.getString("stage"));
                    item.put("errorCode", rs.getString("errorCode")); item.put("message", rs.getString("errorMessage"));
                    item.put("resourceId", rs.getString("resourceId")); item.put("createTime", rs.getTimestamp("createTime"));
                    item.put("completedAt", rs.getTimestamp("completedAt")); return item;
                }, id, user.getId());
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        return ResultUtils.success(rows.get(0));
    }

    @GetMapping("/analyses/{submissionId}")
    public BaseResponse<Map<String, Object>> getAnalysis(@PathVariable long submissionId, HttpServletRequest request) {
        User user = requireMember(request);
        var rows = jdbc.query("SELECT id,verdict,reportJson,modelId,promptVersion,createTime FROM ai_analysis "
                        + "WHERE submissionId=? AND userId=?", (rs, row) -> {
                    Map<String, Object> item = new LinkedHashMap<>(); item.put("id", rs.getLong("id"));
                    item.put("verdict", rs.getString("verdict")); item.put("report", parseJson(rs.getString("reportJson")));
                    item.put("modelId", rs.getString("modelId")); item.put("promptVersion", rs.getString("promptVersion"));
                    item.put("createTime", rs.getTimestamp("createTime")); return item;
                }, submissionId, user.getId());
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        return ResultUtils.success(rows.get(0));
    }

    private User requireMember(HttpServletRequest request) {
        User user = users.getLoginUser(request);
        if (user == null || user.getId() == null || "admin".equals(user.getUserRole()) || "ban".equals(user.getUserRole())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        return user;
    }

    private boolean activeVip(long userId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM user_membership WHERE userId=? AND tier='VIP' "
                + "AND validFrom<=NOW() AND validUntil>NOW()", Integer.class, userId);
        return count != null && count > 0;
    }

    private void enforceTaskCapacity(long userId) {
        int active = jdbc.query("SELECT id FROM ai_task WHERE ownerUserId=? AND type<>'SITE_HELP' "
                + "AND status IN ('QUEUED','RUNNING','WAITING_REVIEW') FOR UPDATE",
                (rs, row) -> rs.getString(1), userId).size();
        if (active >= 5) throw new BusinessException(ErrorCode.OPERATION_ERROR, "已有过多 AI 任务正在处理", "TASK_CONFLICT");
        int recent = jdbc.query("SELECT id FROM ai_task WHERE ownerUserId=? AND type<>'SITE_HELP' AND createTime>=DATE_SUB(NOW(),INTERVAL 1 MINUTE) FOR UPDATE",
                (rs, row) -> rs.getString(1), userId).size();
        if (recent >= tasksPerMinute) throw new BusinessException(ErrorCode.OPERATION_ERROR, "AI 请求过于频繁", "RATE_LIMITED");
    }

    private void requireModelEnabled() {
        if (!analysisEnabled || !modelConfigured())
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "AI 分析尚未配置模型服务", "MODEL_UNAVAILABLE");
    }

    private boolean modelConfigured() {
        return modelEnabled && !modelId.isBlank() && agentAvailability.isAvailable()
                && dailyBudget != null && dailyBudget.signum() > 0
                && taskBudgetReservation != null && taskBudgetReservation.signum() > 0;
    }

    private void reservePlatformBudget() {
        if (!AiDailyBudget.canReserve(jdbc, LocalDate.now(BUSINESS_ZONE), taskBudgetReservation, dailyBudget)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "AI 平台当日预算已用完", "BUDGET_EXHAUSTED");
        }
    }

    private long positiveLong(Object raw) {
        try { long value = Long.parseLong(String.valueOf(raw)); if (value > 0) return value; }
        catch (RuntimeException ignored) { }
        throw new BusinessException(ErrorCode.PARAMS_ERROR);
    }

    private String idempotencyKey(Object raw) {
        try { return UUID.fromString(String.valueOf(raw)).toString(); }
        catch (RuntimeException ignored) { throw new BusinessException(ErrorCode.PARAMS_ERROR, "idempotencyKey 必须为 UUID"); }
    }

    private Object parseJson(String raw) {
        try { return json.readTree(raw); } catch (Exception e) { throw new BusinessException(ErrorCode.SYSTEM_ERROR); }
    }

    private boolean isSystemError(String raw) {
        try { JsonNode info=json.readTree(raw); return "SYSTEM_ERROR".equalsIgnoreCase(info.path("message").asText()); }
        catch(Exception ignored) { return false; }
    }
}
