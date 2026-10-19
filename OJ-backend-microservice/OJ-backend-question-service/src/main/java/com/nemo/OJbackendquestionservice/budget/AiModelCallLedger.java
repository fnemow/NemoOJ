package com.nemo.OJbackendquestionservice.budget;

import com.nemo.OJbackendcommon.common.ErrorCode;
import com.nemo.OJbackendcommon.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A call is reserved before contacting the provider. Missing receipts remain reserved. */
@Service
public class AiModelCallLedger {
    private final JdbcTemplate jdbc;
    @Value("${ai.budget.daily-usd:0}") private BigDecimal dailyLimit;
    @Value("${ai.budget.max-task-reservation-usd:0}") private BigDecimal reservation;
    @Value("${ai.model.max-calls-per-task:4}") private int maxCalls;
    public AiModelCallLedger(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(rollbackFor = Exception.class)
    public void start(String taskId, String callId, String workerId) {
        try { UUID.fromString(callId); }
        catch (IllegalArgumentException e) { throw new BusinessException(ErrorCode.PARAMS_ERROR, "调用标识非法"); }
        List<Map<String,Object>> tasks = jdbc.queryForList("SELECT modelId,attemptCount FROM ai_task "
                + "WHERE id=? AND status='RUNNING' AND leaseOwner=? AND leaseExpireAt>=NOW() AND (type<>'PRIVATE_PROBLEM' OR startedAt>DATE_SUB(NOW(),INTERVAL 900 SECOND)) FOR UPDATE", taskId, workerId);
        if (tasks.isEmpty()) throw new BusinessException(ErrorCode.OPERATION_ERROR, "调用不属于当前任务租约", "AI_LEASE_LOST");
        List<Map<String,Object>> previous = jdbc.queryForList("SELECT taskId,workerId,state FROM ai_model_call WHERE id=?", callId);
        if (!previous.isEmpty()) {
            if (!taskId.equals(previous.get(0).get("taskId")) || !workerId.equals(previous.get(0).get("workerId")))
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "调用标识冲突");
            return;
        }
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM ai_model_call WHERE taskId=?", Integer.class, taskId);
        if (count == null || count >= maxCalls)
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "模型调用次数达到上限", "BUDGET_EXHAUSTED");
        LocalDate day = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        if (!AiDailyBudget.canReserve(jdbc, day, reservation, dailyLimit, count == 0 ? taskId : null))
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "当日 AI 预算不足", "BUDGET_EXHAUSTED");
        jdbc.update("INSERT INTO ai_model_call(id,taskId,workerId,leaseAttempt,usageDate,modelId,state,reservedCost) "
                + "VALUES (?,?,?,?,?,?,'STARTED',?)", callId, taskId, workerId, tasks.get(0).get("attemptCount"),
                day, tasks.get(0).get("modelId"), reservation);
        aggregate(taskId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void finish(String taskId, String callId, String workerId, String state,
                       long input, long output, BigDecimal cost) {
        if (!List.of("KNOWN", "UNKNOWN").contains(state) || input < 0 || output < 0
                || input > 2_000_000 || output > 2_000_000 || cost == null || cost.signum() < 0
                || cost.compareTo(new BigDecimal("100")) > 0)
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        // Serialize aggregate updates for a task; late receipts can account for
        // their own calls without completing or altering a newer task lease.
        jdbc.queryForObject("SELECT id FROM ai_task WHERE id=? FOR UPDATE", String.class, taskId);
        List<Map<String,Object>> calls = jdbc.queryForList("SELECT state,workerId FROM ai_model_call WHERE id=? AND taskId=? FOR UPDATE", callId, taskId);
        if (calls.isEmpty() || !workerId.equals(calls.get(0).get("workerId")))
            throw new BusinessException(ErrorCode.FORBIDDEN_ERROR);
        String previous = String.valueOf(calls.get(0).get("state"));
        if ("KNOWN".equals(previous)) return;
        if ("KNOWN".equals(state)) {
            jdbc.update("UPDATE ai_model_call SET state='KNOWN',inputTokens=?,outputTokens=?,estimatedCost=?,reservedCost=0,completedAt=NOW() WHERE id=?",
                    input, output, cost, callId);
        } else {
            jdbc.update("UPDATE ai_model_call SET state='UNKNOWN',completedAt=NOW() WHERE id=? AND state='STARTED'", callId);
        }
        aggregate(taskId);
    }

    private void aggregate(String taskId) {
        jdbc.update("UPDATE ai_usage u JOIN (SELECT SUM(inputTokens) AS i,SUM(outputTokens) AS o,"
                + "SUM(estimatedCost) AS c,SUM(reservedCost) AS r FROM ai_model_call WHERE taskId=?) x "
                + "SET u.inputTokens=x.i,u.outputTokens=x.o,u.estimatedCost=x.c,u.reservedCost=x.r WHERE u.taskId=?", taskId, taskId);
    }

    public void settle(String taskId, String state, long input, long output, double cost) {
        Integer calls = jdbc.queryForObject("SELECT COUNT(*) FROM ai_model_call WHERE taskId=?", Integer.class, taskId);
        if (calls != null && calls > 0) {
            jdbc.update("UPDATE ai_usage SET state=? WHERE taskId=? AND state='RESERVED'", state, taskId);
        } else {
            // Compatibility for tasks produced before the agent upgrade.
            jdbc.update("UPDATE ai_usage SET state=?,inputTokens=?,outputTokens=?,estimatedCost=?,reservedCost=0 "
                    + "WHERE taskId=? AND state='RESERVED'", state, input, output, cost, taskId);
        }
    }
}
