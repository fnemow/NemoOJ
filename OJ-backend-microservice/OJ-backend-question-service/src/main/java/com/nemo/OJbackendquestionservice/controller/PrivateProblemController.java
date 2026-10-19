package com.nemo.OJbackendquestionservice.controller;

import com.nemo.OJbackendquestionservice.budget.AiDailyBudget;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nemo.OJbackendcommon.common.BaseResponse;
import com.nemo.OJbackendcommon.common.ErrorCode;
import com.nemo.OJbackendcommon.common.ResultUtils;
import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendcommon.ratelimit.RateLimitGuard;
import com.nemo.OJbackendmodel.model.dto.question.ProblemSpec;
import com.nemo.OJbackendmodel.model.dto.questionSubmit.QuestionSubmitAddRequest;
import com.nemo.OJbackendmodel.model.entity.QuestionSubmit;
import com.nemo.OJbackendmodel.model.entity.User;
import com.nemo.OJbackendmodel.model.vo.QuestionSubmitVO;
import com.nemo.OJbackendquestionservice.service.LanguageCapabilityService;
import com.nemo.OJbackendquestionservice.service.AiAgentAvailability;
import com.nemo.OJbackendquestionservice.service.QuestionSubmitService;
import com.nemo.OJserviceclient.UserFeignClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Owner-scoped read and submit API for AI-generated private problems. */
@RestController
@RequestMapping("/ai/private-problems")
public class PrivateProblemController {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final UserFeignClient users;
    private final QuestionSubmitService submissions;
    private final LanguageCapabilityService capabilities;
    private final RateLimitGuard rateLimit;
    @org.springframework.beans.factory.annotation.Autowired private AiAgentAvailability agentAvailability;
    @org.springframework.beans.factory.annotation.Value("${ai.private-problems.enabled:false}") private boolean enabled;
    @org.springframework.beans.factory.annotation.Value("${ai.model.enabled:false}") private boolean modelEnabled;
    @org.springframework.beans.factory.annotation.Value("${ai.model.id:}") private String modelId;
    @org.springframework.beans.factory.annotation.Value("${ai.limits.tasks-per-minute:5}") private int tasksPerMinute;
    @org.springframework.beans.factory.annotation.Value("${ai.budget.daily-usd:0}") private BigDecimal dailyBudget;
    @org.springframework.beans.factory.annotation.Value("${ai.budget.max-task-reservation-usd:0}") private BigDecimal taskReservation;

    public PrivateProblemController(JdbcTemplate jdbc, ObjectMapper json, UserFeignClient users,
                                    QuestionSubmitService submissions, LanguageCapabilityService capabilities,
                                    RateLimitGuard rateLimit) {
        this.jdbc = jdbc; this.json = json; this.users = users; this.submissions = submissions;
        this.capabilities = capabilities; this.rateLimit = rateLimit;
    }

    @PostMapping
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public BaseResponse<Map<String,Object>> create(@RequestBody Map<String,Object> body,HttpServletRequest request) {
        User user=requireVip(request);
        if(!enabled || !modelEnabled || modelId.isBlank() || !agentAvailability.isAvailable() || dailyBudget==null || dailyBudget.signum()<=0
                || taskReservation==null || taskReservation.signum()<=0)
            throw new BusinessException(ErrorCode.OPERATION_ERROR,"私人题生成功能尚未配置","MODEL_UNAVAILABLE");
        String goal=text(body==null?null:body.get("goal")); String key=text(body==null?null:body.get("idempotencyKey"));
        if(goal==null || goal.isBlank() || goal.length()>1000) throw new BusinessException(ErrorCode.PARAMS_ERROR,"请填写不超过 1000 字的题目方向");
        try { key=UUID.fromString(key).toString(); } catch(Exception e) { throw new BusinessException(ErrorCode.PARAMS_ERROR,"idempotencyKey 必须为 UUID"); }
        List<Map<String,Object>> old=jdbc.query("SELECT id,resourceId,status FROM ai_task WHERE ownerUserId=? AND type='PRIVATE_PROBLEM' AND idempotencyKey=?",
                (rs,row)->Map.<String,Object>of("taskId",rs.getString("id"),"privateProblemId",rs.getString("resourceId"),"status",rs.getString("status")),user.getId(),key);
        if(!old.isEmpty()) return ResultUtils.success(old.get(0));
        jdbc.queryForObject("SELECT id FROM `user` WHERE id=? AND userRole='user' AND isDelete=0 FOR UPDATE",Long.class,user.getId());
        old=jdbc.query("SELECT id,resourceId,status FROM ai_task WHERE ownerUserId=? AND type='PRIVATE_PROBLEM' AND idempotencyKey=?",
                (rs,row)->Map.<String,Object>of("taskId",rs.getString(1),"privateProblemId",rs.getString(2),"status",rs.getString(3)),user.getId(),key);
        if(!old.isEmpty())return ResultUtils.success(old.get(0));
        Integer active=jdbc.queryForObject("SELECT COUNT(*) FROM ai_task WHERE ownerUserId=? AND status IN ('QUEUED','RUNNING','WAITING_REVIEW')",Integer.class,user.getId());
        Integer recent=jdbc.queryForObject("SELECT COUNT(*) FROM ai_task WHERE ownerUserId=? AND createTime>=DATE_SUB(NOW(),INTERVAL 1 MINUTE)",Integer.class,user.getId());
        if(active!=null && active>=5) throw new BusinessException(ErrorCode.OPERATION_ERROR,"最多允许 5 个 AI 任务同时处理","TASK_CONFLICT");
        if(recent!=null && recent>=tasksPerMinute) throw new BusinessException(ErrorCode.OPERATION_ERROR,"AI 请求过于频繁","RATE_LIMITED");
        if(!AiDailyBudget.canReserve(jdbc,LocalDate.now(BUSINESS_ZONE),taskReservation,dailyBudget)) throw new BusinessException(ErrorCode.OPERATION_ERROR,"AI 平台当日预算已用完","BUDGET_EXHAUSTED");
        String problemId=UUID.randomUUID().toString(),taskId=UUID.randomUUID().toString();
        String difficulty=body.get("difficulty")==null?"MEDIUM":String.valueOf(body.get("difficulty")).toUpperCase();
        if(!List.of("EASY","MEDIUM","HARD").contains(difficulty)) throw new BusinessException(ErrorCode.PARAMS_ERROR);
        List<String> tags=new ArrayList<>();
        if(body.get("tags") instanceof List<?> raw) { if(raw.size()>8) throw new BusinessException(ErrorCode.PARAMS_ERROR); for(Object tag:raw) { String value=String.valueOf(tag).trim(); if(value.isBlank()||value.length()>64) throw new BusinessException(ErrorCode.PARAMS_ERROR); if(!tags.contains(value)) tags.add(value); } }
        Map<String,Object> taskRequest=new LinkedHashMap<>(); taskRequest.put("goal",goal); taskRequest.put("difficulty",difficulty); taskRequest.put("tags",tags);
        try {
            jdbc.update("INSERT INTO private_problem(id,ownerUserId,title,tagsJson,difficulty,status,generationTaskId) VALUES (?,?,? ,? ,?,'GENERATING',?)",
                    problemId,user.getId(),"AI 题目生成中",json.writeValueAsString(tags),difficulty,taskId);
            jdbc.update("INSERT INTO ai_task(id,type,ownerUserId,resourceId,idempotencyKey,status,modelId,promptVersion,requestJson) VALUES (?,'PRIVATE_PROBLEM',?,?,?,'QUEUED',?,'private-problem-v1',?)",
                    taskId,user.getId(),problemId,key,modelId,json.writeValueAsString(taskRequest));
        } catch(Exception e) { throw new BusinessException(ErrorCode.SYSTEM_ERROR,"私人题任务创建失败"); }
        jdbc.update("INSERT INTO ai_usage(taskId,userId,usageDate,category,state,quotaExempt,reservedCost) VALUES (?,?,?,'PRIVATE_PROBLEM','RESERVED',1,?)",
                taskId,user.getId(),LocalDate.now(BUSINESS_ZONE),taskReservation);
        jdbc.update("INSERT INTO ai_outbox(taskId,eventType,status) VALUES (?,'AI_TASK','PENDING')",taskId);
        return ResultUtils.success(Map.of("taskId",taskId,"privateProblemId",problemId,"status","QUEUED"));
    }

    @GetMapping
    public BaseResponse<List<Map<String,Object>>> list(@RequestParam(defaultValue="false") boolean legacyOnly,HttpServletRequest request) {
        User user=requireUser(request);
        List<Map<String,Object>> rows=jdbc.query("SELECT id,title,tagsJson,difficulty,status,currentVersionId,rejectReason,createTime FROM private_problem p WHERE ownerUserId=? "+(legacyOnly?"AND NOT EXISTS (SELECT 1 FROM ai_task t WHERE t.id=p.generationTaskId AND JSON_EXTRACT(IF(JSON_VALID(t.requestJson),t.requestJson,'{}'),'$.batchId') IS NOT NULL) ":"")+"ORDER BY createTime DESC",
                (rs,row)->{Map<String,Object> v=new LinkedHashMap<>();v.put("id",rs.getString("id"));v.put("title",rs.getString("title"));
                    v.put("tags",parseJson(rs.getString("tagsJson")));v.put("difficulty",rs.getString("difficulty"));v.put("status",rs.getString("status"));
                    v.put("versionId",rs.getString("currentVersionId"));v.put("rejectReason",rs.getString("rejectReason"));v.put("createTime",rs.getTimestamp("createTime"));return v;},user.getId());
        return ResultUtils.success(rows);
    }

    @GetMapping("/{id}")
    public BaseResponse<Map<String, Object>> detail(@PathVariable String id, HttpServletRequest request) {
        User user = requireUser(request);
        List<Map<String, Object>> problems = jdbc.query("SELECT id,title,tagsJson,difficulty,status,currentVersionId,rejectReason,createTime "
                        + "FROM private_problem WHERE id=? AND ownerUserId=?", (rs, row) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", rs.getString("id")); item.put("title", rs.getString("title"));
                    item.put("tags", parseJson(rs.getString("tagsJson"))); item.put("difficulty", rs.getString("difficulty"));
                    item.put("status", rs.getString("status")); item.put("versionId", rs.getString("currentVersionId"));
                    item.put("rejectReason", rs.getString("rejectReason")); item.put("createTime", rs.getTimestamp("createTime"));
                    return item;
                }, id, user.getId());
        if (problems.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        Map<String, Object> result = problems.get(0);
        String versionId = (String) result.get("versionId");
        if (versionId == null || !"READY".equals(result.get("status"))) return ResultUtils.success(result);
        List<Map<String, Object>> versions = jdbc.query("SELECT id,versionNumber,contentMarkdown,sampleJson,problemSpecJson,contentHash "
                        + "FROM private_problem_version WHERE id=? AND privateProblemId=? AND status='READY'",
                (rs, row) -> {
                    Map<String, Object> item = new LinkedHashMap<>(); item.put("id", rs.getString("id"));
                    item.put("version", rs.getInt("versionNumber")); item.put("content", rs.getString("contentMarkdown"));
                    item.put("samples", parseJson(rs.getString("sampleJson"))); item.put("specRaw", rs.getString("problemSpecJson"));
                    item.put("contentHash", rs.getString("contentHash")); return item;
                }, versionId, id);
        if (versions.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        Map<String, Object> version = versions.get(0);
        ProblemSpec spec = null;
        try { if (version.get("specRaw") != null && !"null".equalsIgnoreCase(String.valueOf(version.get("specRaw")).trim())
                    && !"{}".equals(String.valueOf(version.get("specRaw")).trim())) spec = json.readValue(String.valueOf(version.get("specRaw")), ProblemSpec.class); }
        catch (Exception e) { throw new BusinessException(ErrorCode.SYSTEM_ERROR, "私人题规格无法读取"); }
        version.remove("specRaw");
        List<String> availableModes=new ArrayList<>(); availableModes.add("ACM");
        if(spec!=null) availableModes.add("FUNCTION");
        version.put("availableModes", availableModes);
        if(spec!=null) {
            ProblemSpec visible=new ProblemSpec();org.springframework.beans.BeanUtils.copyProperties(spec,visible);
            visible.setJudgeCases(null);version.put("problemSpec",visible);
            version.put("functionSamples",spec.getJudgeCases()==null?List.of():spec.getJudgeCases().stream().filter(c->!Boolean.TRUE.equals(c.getIsHidden())).limit(2).toList());
        }
        List<Map<String,Object>> templates=new ArrayList<>();
        for(String mode:availableModes) for(Map<String,Object> template:capabilities.previewTemplates("FUNCTION".equals(mode)?spec:null)) {
            Map<String,Object> entry=new LinkedHashMap<>(template);entry.put("mode",mode);templates.add(entry);
        }
        version.put("templates", templates);
        result.put("currentVersion", version);
        return ResultUtils.success(result);
    }

    @PostMapping("/{id}/submissions")
    public BaseResponse<Long> submit(@PathVariable String id, @RequestBody Map<String, Object> body,
                                     HttpServletRequest request) {
        User user = requireUser(request);
        if (body == null || body.get("problemVersion") == null) throw new BusinessException(ErrorCode.PARAMS_ERROR);
        String versionId = String.valueOf(body.get("problemVersion"));
        QuestionSubmitAddRequest add = new QuestionSubmitAddRequest();
        add.setLanguage(text(body.get("language"))); add.setExecutionMode(text(body.get("executionMode")));
        add.setCode(text(body.get("code"))); add.setClientRequestId(text(body.get("clientRequestId")));
        rateLimit.checkSubmitRate(request, user.getId());
        return ResultUtils.success(submissions.doPrivateQuestionSubmit(id, versionId, add, user));
    }

    @GetMapping("/{id}/submissions")
    public BaseResponse<Page<QuestionSubmitVO>> mySubmissions(@PathVariable String id,
            @RequestParam(defaultValue = "1") long current, @RequestParam(defaultValue = "20") long pageSize,
            HttpServletRequest request) {
        User user = requireUser(request);
        if (current < 1 || pageSize < 1 || pageSize > 20) throw new BusinessException(ErrorCode.PARAMS_ERROR);
        List<String> versionIds = jdbc.query("SELECT v.id FROM private_problem_version v JOIN private_problem p "
                        + "ON p.id=v.privateProblemId WHERE p.id=? AND p.ownerUserId=?",
                (rs, row) -> rs.getString(1), id, user.getId());
        if (versionIds.isEmpty()) {
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM private_problem WHERE id=? AND ownerUserId=?", Integer.class, id, user.getId());
            if (exists == null || exists == 0) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
            Page<QuestionSubmitVO> empty = new Page<>(current, pageSize, 0);
            empty.setRecords(List.of());
            return ResultUtils.success(empty);
        }
        QueryWrapper<QuestionSubmit> wrapper = new QueryWrapper<QuestionSubmit>().eq("userId", user.getId())
                .eq("problemSource", "PRIVATE").in("privateProblemVersionId", versionIds)
                .orderByDesc("createTime");
        Page<QuestionSubmit> page = submissions.page(new Page<>(current, pageSize), wrapper);
        Page<QuestionSubmitVO> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(submissions.getQuestionSubmitVOPage(page, user).getRecords());
        return ResultUtils.success(result);
    }

    private User requireUser(HttpServletRequest request) {
        User user = users.getLoginUser(request);
        if (user == null || user.getId() == null || !"user".equals(user.getUserRole()))
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        return user;
    }

    private User requireVip(HttpServletRequest request) {
        User user=requireUser(request);
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM user_membership WHERE userId=? AND tier='VIP' AND validFrom<=NOW() AND validUntil>NOW()",Integer.class,user.getId());
        if(count==null || count==0) throw new BusinessException(ErrorCode.OPERATION_ERROR,"此功能需要有效 VIP 会员","VIP_REQUIRED");
        return user;
    }

    private String text(Object value) { return value == null ? null : String.valueOf(value); }
    private Object parseJson(String value) {
        try { return value == null ? List.of() : json.readValue(value, Object.class); }
        catch (Exception e) { return new ArrayList<>(); }
    }
}
