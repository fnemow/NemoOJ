package com.nemo.OJbackendquestionservice.controller;

import com.nemo.OJbackendquestionservice.budget.AiDailyBudget;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nemo.OJbackendcommon.common.BaseResponse;
import com.nemo.OJbackendcommon.common.ErrorCode;
import com.nemo.OJbackendcommon.common.ResultUtils;
import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendmodel.model.entity.User;
import com.nemo.OJbackendquestionservice.service.AiAgentAvailability;
import com.nemo.OJserviceclient.UserFeignClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** VIP learning plan API. Java owns validation, revisions, and all public/private references. */
@RestController
@RequestMapping("/ai/plans")
public class StudyPlanController {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final UserFeignClient users;
    @org.springframework.beans.factory.annotation.Autowired private AiAgentAvailability agentAvailability;
    @org.springframework.beans.factory.annotation.Autowired private com.nemo.OJbackendquestionservice.service.StudyPlanCatalog catalog;
    @Value("${ai.training.catalog-plans.enabled:true}") private boolean catalogPlansEnabled;
    @Value("${ai.learning.enabled:false}") private boolean learningEnabled;
    @Value("${ai.private-problems.enabled:false}") private boolean privateProblemsEnabled;
    @Value("${ai.model.enabled:false}") private boolean modelEnabled;
    @Value("${ai.model.id:}") private String modelId;
    @Value("${ai.limits.tasks-per-minute:5}") private int tasksPerMinute;
    @Value("${ai.budget.daily-usd:0}") private BigDecimal dailyBudget;
    @Value("${ai.budget.max-task-reservation-usd:0}") private BigDecimal taskReservation;

    public StudyPlanController(JdbcTemplate jdbc, ObjectMapper json, UserFeignClient users) {
        this.jdbc = jdbc; this.json = json; this.users = users;
    }

    @PostMapping
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        User user = requireVip(request);
        requireEnabled();
        if(body==null)throw new BusinessException(ErrorCode.PARAMS_ERROR);
        boolean catalogV2="CATALOG_V2".equals(body.get("inputVersion"));
        if(catalogV2&&!catalogPlansEnabled)throw new BusinessException(ErrorCode.OPERATION_ERROR,"新版刷题计划暂未开放");
        String goal = clean(body.get("goal"), 1000, !catalogV2);
        String level = clean(body.get("level"), 32, true);
        int days = bounded(body.get("days"), 14, 7, 30);
        int minutes = bounded(body.get("minutesPerDay"), 45, 10, 240);
        String sourceMode = body.get("sourceMode") == null ? "CATALOG" : String.valueOf(body.get("sourceMode")).toUpperCase();
        if (!"CATALOG".equals(sourceMode) && !"MIXED".equals(sourceMode)) throw new BusinessException(ErrorCode.PARAMS_ERROR);
        if ("MIXED".equals(sourceMode) && !privateProblemsEnabled) throw new BusinessException(ErrorCode.OPERATION_ERROR,
                "私人题生成暂不可用", "MODEL_UNAVAILABLE");
        List<String> tags = stringList(body.get("tags"), 12, 64);
        if(catalogV2) { sourceMode="CATALOG"; goal=String.join("与",tags)+" · "+days+" 天刷题计划"; }
        String key = uuid(body.get("idempotencyKey"));
        var existing = jdbc.query("SELECT id,resourceId,status FROM ai_task WHERE ownerUserId=? AND type='PLAN' AND idempotencyKey=?",
                (rs, row) -> Map.<String,Object>of("taskId", rs.getString("id"), "planId", rs.getString("resourceId"),
                        "status", rs.getString("status")), user.getId(), key);
        if (!existing.isEmpty()) return ResultUtils.success(existing.get(0));
        lockMember(user.getId());
        // Recheck after the owner lock: concurrent retries must reuse the first task.
        existing = jdbc.query("SELECT id,resourceId,status FROM ai_task WHERE ownerUserId=? AND type='PLAN' AND idempotencyKey=?",
                (rs,row)->Map.<String,Object>of("taskId",rs.getString(1),"planId",rs.getString(2),"status",rs.getString(3)),user.getId(),key);
        if(!existing.isEmpty())return ResultUtils.success(existing.get(0));
        boolean allowReview=Boolean.TRUE.equals(body.get("allowReview"));
        List<Map<String,Object>> snapshot=catalogV2?catalog.candidates(user.getId(),tags,allowReview,days):List.of();
        Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM study_plan WHERE userId=? AND status IN ('GENERATING','ACTIVE','ADJUSTING','NEEDS_REVIEW')",
                Integer.class, user.getId());
        if (active != null && active > 0) throw new BusinessException(ErrorCode.OPERATION_ERROR, "请先完成或归档当前学习计划", "ACTIVE_PLAN_EXISTS");
        enforceTaskLimit(user.getId());
        reserveBudget();
        String planId = UUID.randomUUID().toString();
        String taskId = UUID.randomUUID().toString();
        String tagsJson = encode(tags);
        jdbc.update("INSERT INTO study_plan(id,userId,title,goal,level,tagsJson,days,minutesPerDay,sourceMode,status,planVersion) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,'GENERATING',1)", planId, user.getId(), catalogV2 ? goal : "个性化算法学习计划", goal,
                level, tagsJson, days, minutes, sourceMode);
        Map<String, Object> taskRequest = new LinkedHashMap<>(); taskRequest.put("planId", planId); taskRequest.put("goal", goal);
        taskRequest.put("level", level); taskRequest.put("days", days); taskRequest.put("minutesPerDay", minutes);
        taskRequest.put("sourceMode", sourceMode); taskRequest.put("tags", tags);
        if(catalogV2) { taskRequest.put("inputVersion","CATALOG_V2");taskRequest.put("allowReview",allowReview);taskRequest.put("candidateSnapshot",snapshot); }
        jdbc.update("INSERT INTO ai_task(id,type,ownerUserId,resourceId,idempotencyKey,status,modelId,promptVersion,requestJson) "
                        + "VALUES (?,'PLAN',?,?,?,'QUEUED',?,'plan-v1',?)", taskId, user.getId(), planId, key, modelId, encode(taskRequest));
        reserveUsage(taskId, user.getId(), "PLAN");
        jdbc.update("INSERT INTO ai_outbox(taskId,eventType,status) VALUES (?,'AI_TASK','PENDING')", taskId);
        return ResultUtils.success(Map.of("taskId", taskId, "planId", planId, "status", "QUEUED"));
    }

    @GetMapping
    public BaseResponse<List<Map<String, Object>>> list(HttpServletRequest request) {
        User user = requireMember(request);
        List<String> ids = jdbc.query("SELECT id FROM study_plan WHERE userId=? ORDER BY updateTime DESC", (rs, row) -> rs.getString(1), user.getId());
        List<Map<String, Object>> result = new ArrayList<>();
        for (String id : ids) result.add(readPlan(id, user.getId()));
        return ResultUtils.success(result);
    }

    @GetMapping("/topics")
    public BaseResponse<List<Map<String,Object>>> topics(@RequestParam(defaultValue="false") boolean allowReview,HttpServletRequest request) {
        return ResultUtils.success(catalog.topics(requireMember(request).getId(),allowReview));
    }

    @GetMapping("/{id}")
    public BaseResponse<Map<String, Object>> detail(@PathVariable String id, HttpServletRequest request) {
        return ResultUtils.success(readPlan(id, requireMember(request).getId()));
    }

    @PostMapping("/{id}/adjust")
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse<Map<String, Object>> adjust(@PathVariable String id, @RequestBody Map<String, Object> body,
                                                    HttpServletRequest request) {
        User user = requireVip(request); requireEnabled();
        String change = clean(body.get("changeRequest"), 1000, true);
        long expected = boundedLong(body.get("expectedVersion"), -1, 1, Long.MAX_VALUE);
        String key = uuid(body.get("idempotencyKey"));
        lockMember(user.getId());
        var duplicate=jdbc.queryForList("SELECT id,resourceId,status FROM ai_task WHERE ownerUserId=? AND type='PLAN_ADJUST' AND idempotencyKey=?",user.getId(),key);
        if(!duplicate.isEmpty()) {
            if(!id.equals(String.valueOf(duplicate.get(0).get("resourceId"))))throw new BusinessException(ErrorCode.PARAMS_ERROR,"幂等键已用于其他计划");
            return ResultUtils.success(Map.of("taskId",duplicate.get(0).get("id"),"planId",id,"status",duplicate.get(0).get("status")));
        }
        List<Map<String,Object>> plans = jdbc.query("SELECT planVersion,status,goal,level,tagsJson,days,minutesPerDay "
                        + "FROM study_plan WHERE id=? AND userId=? FOR UPDATE", (rs,row) -> {
                    Map<String,Object> p=new LinkedHashMap<>(); p.put("version",rs.getLong("planVersion")); p.put("status",rs.getString("status"));
                    p.put("goal",rs.getString("goal")); p.put("level",rs.getString("level")); p.put("tags",rs.getString("tagsJson"));
                    p.put("days",rs.getInt("days")); p.put("minutes",rs.getInt("minutesPerDay")); return p;
                }, id, user.getId());
        if (plans.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        Map<String,Object> plan = plans.get(0);
        if (((Number)plan.get("version")).longValue()!=expected) throw new BusinessException(ErrorCode.OPERATION_ERROR,"学习计划已更新","EDIT_CONFLICT");
        if (!"ACTIVE".equals(plan.get("status"))) throw new BusinessException(ErrorCode.OPERATION_ERROR,"当前计划状态不能调整");
        enforceTaskLimit(user.getId()); reserveBudget();
        String taskId=UUID.randomUUID().toString();
        Map<String,Object> taskRequest=new LinkedHashMap<>(); taskRequest.put("planId",id); taskRequest.put("changeRequest",change);
        taskRequest.put("expectedVersion",expected); taskRequest.put("goal",plan.get("goal")); taskRequest.put("level",plan.get("level"));
        taskRequest.put("tags",decode(plan.get("tags"))); taskRequest.put("days",plan.get("days")); taskRequest.put("minutesPerDay",plan.get("minutes"));
        List<String> initial=jdbc.query("SELECT requestJson FROM ai_task WHERE resourceId=? AND type='PLAN' ORDER BY createTime LIMIT 1",(rs,row)->rs.getString(1),id);
        if(!initial.isEmpty()) {
            try { var input=json.readTree(initial.get(0)); if("CATALOG_V2".equals(input.path("inputVersion").asText())) {
                taskRequest.put("inputVersion","CATALOG_V2");taskRequest.put("sourceMode","CATALOG");taskRequest.put("allowReview",input.path("allowReview").asBoolean());
                taskRequest.put("candidateSnapshot",catalog.candidates(user.getId(),stringList(decode(plan.get("tags")),6,64),input.path("allowReview").asBoolean(),0));
            }}catch(BusinessException e){throw e;}catch(Exception e){throw new BusinessException(ErrorCode.SYSTEM_ERROR,"计划主题无法读取");}
        }
        jdbc.update("INSERT INTO ai_task(id,type,ownerUserId,resourceId,idempotencyKey,status,modelId,promptVersion,requestJson) "
                + "VALUES (?,'PLAN_ADJUST',?,?,?,'QUEUED',?,'plan-adjust-v1',?)",taskId,user.getId(),id,key,modelId,encode(taskRequest));
        jdbc.update("UPDATE study_plan SET status='ADJUSTING',planVersion=planVersion+1 WHERE id=? AND userId=? AND planVersion=?",id,user.getId(),expected);
        reserveUsage(taskId,user.getId(),"PLAN_ADJUST");
        jdbc.update("INSERT INTO ai_outbox(taskId,eventType,status) VALUES (?,'AI_TASK','PENDING')",taskId);
        return ResultUtils.success(Map.of("taskId",taskId,"planId",id,"status","QUEUED","planVersion",expected+1));
    }

    @PostMapping("/{id}/archive")
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse<Boolean> archive(@PathVariable String id, @RequestBody Map<String,Object> body,HttpServletRequest request) {
        User user=requireVip(request); long expected=boundedLong(body.get("expectedVersion"),-1,1,Long.MAX_VALUE);
        int count=jdbc.update("UPDATE study_plan SET status='ARCHIVED',planVersion=planVersion+1 WHERE id=? AND userId=? AND planVersion=? AND status IN ('ACTIVE','NEEDS_REVIEW')",
                id,user.getId(),expected);
        if(count!=1) throw new BusinessException(ErrorCode.OPERATION_ERROR,"学习计划状态已变化","EDIT_CONFLICT");
        return ResultUtils.success(true);
    }

    private Map<String,Object> readPlan(String id,long userId) {
        List<Map<String,Object>> plans=jdbc.query("SELECT id,title,goal,level,tagsJson,days,minutesPerDay,sourceMode,status,planVersion,createTime,updateTime "
                        + "FROM study_plan WHERE id=? AND userId=?",(rs,row)->{
                    Map<String,Object> p=new LinkedHashMap<>(); p.put("id",rs.getString("id")); p.put("title",rs.getString("title"));
                    p.put("goal",rs.getString("goal")); p.put("level",rs.getString("level")); p.put("tags",decode(rs.getString("tagsJson")));
                    p.put("days",rs.getInt("days")); p.put("minutesPerDay",rs.getInt("minutesPerDay")); p.put("sourceMode",rs.getString("sourceMode"));
                    p.put("status",rs.getString("status")); p.put("planVersion",rs.getLong("planVersion"));
                    p.put("createTime",rs.getTimestamp("createTime")); p.put("updateTime",rs.getTimestamp("updateTime")); return p;
                },id,userId);
        if(plans.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        Map<String,Object> plan=plans.get(0);
        List<Map<String,Object>> items=jdbc.query("SELECT id,dayNumber,orderNumber,source,problemNo,privateProblemId,titleSnapshot,reason,itemStatus,completedBySubmitId,completedAt "
                        + "FROM study_plan_item WHERE planId=? ORDER BY dayNumber,orderNumber",(rs,row)->{
                    Map<String,Object> item=new LinkedHashMap<>(); item.put("id",rs.getLong("id")); item.put("dayNumber",rs.getInt("dayNumber"));
                    item.put("orderNumber",rs.getInt("orderNumber")); item.put("source",rs.getString("source")); item.put("problemNo",rs.getObject("problemNo"));
                    item.put("privateProblemId",rs.getString("privateProblemId")); item.put("title",rs.getString("titleSnapshot"));
                    item.put("reason",rs.getString("reason")); item.put("status",rs.getString("itemStatus"));
                    item.put("completedBySubmitId",rs.getObject("completedBySubmitId")); item.put("completedAt",rs.getTimestamp("completedAt")); return item;
                },id);
        plan.put("items",items); plan.put("completedCount",items.stream().filter(v->"DONE".equals(v.get("status"))).count());
        return plan;
    }

    private User requireMember(HttpServletRequest request) {
        User user=users.getLoginUser(request);
        if(user==null || user.getId()==null || !"user".equals(user.getUserRole())) throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        return user;
    }
    private User requireVip(HttpServletRequest request) {
        User user=requireMember(request);
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM user_membership WHERE userId=? AND tier='VIP' AND validFrom<=NOW() AND validUntil>NOW()",Integer.class,user.getId());
        if(count==null || count==0) throw new BusinessException(ErrorCode.OPERATION_ERROR,"此功能需要有效 VIP 会员","VIP_REQUIRED");
        return user;
    }
    private void requireEnabled() {
        if(!learningEnabled || !modelEnabled || modelId.isBlank() || !agentAvailability.isAvailable() || dailyBudget==null || dailyBudget.signum()<=0 || taskReservation==null || taskReservation.signum()<=0)
            throw new BusinessException(ErrorCode.OPERATION_ERROR,"AI 学习计划尚未配置","MODEL_UNAVAILABLE");
    }
    private void lockMember(long userId) {
        String role=jdbc.queryForObject("SELECT userRole FROM `user` WHERE id=? AND isDelete=0 FOR UPDATE",String.class,userId);
        if(!"user".equals(role)) throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
    }
    private void enforceTaskLimit(long userId) {
        Integer active=jdbc.queryForObject("SELECT COUNT(*) FROM ai_task WHERE ownerUserId=? AND status IN ('QUEUED','RUNNING','WAITING_REVIEW')",Integer.class,userId);
        Integer recent=jdbc.queryForObject("SELECT COUNT(*) FROM ai_task WHERE ownerUserId=? AND createTime>=DATE_SUB(NOW(),INTERVAL 1 MINUTE)",Integer.class,userId);
        if(active!=null && active>=5) throw new BusinessException(ErrorCode.OPERATION_ERROR,"已有过多 AI 任务正在处理","TASK_CONFLICT");
        if(recent!=null && recent>=tasksPerMinute) throw new BusinessException(ErrorCode.OPERATION_ERROR,"AI 请求过于频繁","RATE_LIMITED");
    }
    private void reserveBudget() {
        if(!AiDailyBudget.canReserve(jdbc,LocalDate.now(BUSINESS_ZONE),taskReservation,dailyBudget)) throw new BusinessException(ErrorCode.OPERATION_ERROR,"AI 平台当日预算已用完","BUDGET_EXHAUSTED");
    }
    private void reserveUsage(String taskId,long userId,String category) {
        jdbc.update("INSERT INTO ai_usage(taskId,userId,usageDate,category,state,quotaExempt,reservedCost) VALUES (?,?,?,?,'RESERVED',1,?)",
                taskId,userId,LocalDate.now(BUSINESS_ZONE),category,taskReservation);
    }
    private int bounded(Object raw,int defaultValue,int min,int max) {
        if(raw==null) return defaultValue; try { int value=Integer.parseInt(String.valueOf(raw)); if(value>=min&&value<=max) return value; } catch(Exception ignored) { }
        throw new BusinessException(ErrorCode.PARAMS_ERROR);
    }
    private long boundedLong(Object raw,long defaultValue,long min,long max) {
        if(raw==null) return defaultValue; try { long value=Long.parseLong(String.valueOf(raw)); if(value>=min&&value<=max) return value; } catch(Exception ignored) { }
        throw new BusinessException(ErrorCode.PARAMS_ERROR);
    }
    private String clean(Object raw,int max,boolean required) {
        String value=raw==null?"":String.valueOf(raw).trim();
        if((required&&value.isBlank())||value.length()>max) throw new BusinessException(ErrorCode.PARAMS_ERROR);
        return value;
    }
    private List<String> stringList(Object raw,int maxItems,int maxLength) {
        if(raw==null) return List.of(); if(!(raw instanceof List<?> values)||values.size()>maxItems) throw new BusinessException(ErrorCode.PARAMS_ERROR);
        List<String> result=new ArrayList<>(); for(Object item:values) { String value=clean(item,maxLength,true); if(!result.contains(value)) result.add(value); }
        return result;
    }
    private String uuid(Object raw) { try { return UUID.fromString(String.valueOf(raw)).toString(); } catch(Exception e) { throw new BusinessException(ErrorCode.PARAMS_ERROR,"idempotencyKey 必须为 UUID"); } }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch(JsonProcessingException e) { throw new BusinessException(ErrorCode.SYSTEM_ERROR); } }
    private Object decode(Object value) { try { return json.readValue(String.valueOf(value),Object.class); } catch(Exception e) { return List.of(); } }
}
