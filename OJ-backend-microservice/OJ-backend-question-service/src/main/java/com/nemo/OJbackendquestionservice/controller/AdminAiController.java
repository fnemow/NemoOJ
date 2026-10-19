package com.nemo.OJbackendquestionservice.controller;

import com.nemo.OJbackendquestionservice.budget.AiDailyBudget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.nemo.OJbackendcommon.common.BaseResponse;
import com.nemo.OJbackendcommon.common.ErrorCode;
import com.nemo.OJbackendcommon.common.ResultUtils;
import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendmodel.model.entity.Question;
import com.nemo.OJbackendmodel.model.entity.User;
import com.nemo.OJbackendquestionservice.service.ProblemLifecycleService;
import com.nemo.OJbackendquestionservice.service.QuestionService;
import com.nemo.OJserviceclient.UserFeignClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** Human review APIs for safe, editorial-only AI maintenance proposals. */
@RestController
@RequestMapping("/admin/ai")
public class AdminAiController {
    private static final ZoneId BUSINESS_ZONE=ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final UserFeignClient users;
    private final ProblemLifecycleService lifecycle;
    private final QuestionService questions;
    @org.springframework.beans.factory.annotation.Autowired private com.nemo.OJbackendquestionservice.service.AiAgentAvailability agentAvailability;
    @Value("${ai.maintenance.enabled:false}") private boolean maintenanceEnabled;
    @Value("${ai.model.enabled:false}") private boolean modelEnabled;
    @Value("${ai.model.id:}") private String modelId;
    @Value("${ai.budget.daily-usd:0}") private BigDecimal dailyBudget;
    @Value("${ai.budget.max-task-reservation-usd:0}") private BigDecimal taskReservation;
    @Value("${ai.limits.tasks-per-minute:5}") private int tasksPerMinute;

    public AdminAiController(JdbcTemplate jdbc,ObjectMapper json,UserFeignClient users,
                             ProblemLifecycleService lifecycle,QuestionService questions) {
        this.jdbc=jdbc;this.json=json;this.users=users;this.lifecycle=lifecycle;this.questions=questions;
    }

    @GetMapping("/proposals")
    public BaseResponse<Map<String,Object>> proposals(@RequestParam(defaultValue="WAITING_REVIEW") String status,
            @RequestParam(defaultValue="1") long current,@RequestParam(defaultValue="20") long pageSize,
            @RequestParam(required=false) Long problemNo,HttpServletRequest request) {
        requireAdmin(request);
        if(current<1||pageSize<1||pageSize>20)throw new BusinessException(ErrorCode.PARAMS_ERROR);
        String normalized=status==null?"WAITING_REVIEW":status.trim().toUpperCase(Locale.ROOT);
        if(!List.of("WAITING_REVIEW","APPROVED","REJECTED","STALE","ALL").contains(normalized))throw new BusinessException(ErrorCode.PARAMS_ERROR);
        StringBuilder where=new StringBuilder(" WHERE 1=1 ");List<Object> filterArgs=new ArrayList<>();
        if(!"ALL".equals(normalized)){where.append(" AND p.status=?");filterArgs.add(normalized);}
        if(problemNo!=null){where.append(" AND p.problemNo=?");filterArgs.add(problemNo);}
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM ai_maintenance_proposal p"+where,Long.class,filterArgs.toArray());
        List<Map<String,Object>> rows=jdbc.query("SELECT p.id,p.taskId,p.problemNo,p.baseProblemVersion,p.baseLifecycleVersion,p.status,p.rowVersion,p.reviewedBy,p.reviewedAt,p.reviewNote,p.approvedDraftId,p.createTime,p.updateTime,q.title AS currentTitle "
                        +"FROM ai_maintenance_proposal p LEFT JOIN question q ON q.id=p.baseQuestionId"+where+" ORDER BY p.createTime DESC LIMIT ?,?",
                (rs,n)->{Map<String,Object> v=new LinkedHashMap<>();for(String key:List.of("id","taskId","problemNo","baseProblemVersion","baseLifecycleVersion","status","rowVersion","reviewedBy","reviewedAt","reviewNote","approvedDraftId","createTime","updateTime","currentTitle"))v.put(key,rs.getObject(key));return v;},
                append(filterArgs,(current-1)*pageSize,pageSize));
        return ResultUtils.success(Map.of("current",current,"pageSize",pageSize,"total",total==null?0:total,"records",rows));
    }

    @GetMapping("/proposals/{id}")
    public BaseResponse<Map<String,Object>> proposal(@PathVariable String id,HttpServletRequest request) {
        requireAdmin(request);
        List<Map<String,Object>> rows=jdbc.query("SELECT p.*,q.title AS currentTitle,q.content AS currentContent,q.rowVersion AS currentRowVersion "
                        +"FROM ai_maintenance_proposal p LEFT JOIN question q ON q.id=p.baseQuestionId WHERE p.id=?",
                (rs,n)->{Map<String,Object> v=new LinkedHashMap<>();for(String key:List.of("id","taskId","feedbackId","problemNo","baseQuestionId","baseProblemVersion","baseLifecycleVersion","proposalJson","proposalHash","diffMarkdown","evidenceJson","validationJson","status","rowVersion","reviewedBy","reviewedAt","reviewNote","approvedDraftId","currentTitle","currentContent","currentRowVersion"))v.put(key,rs.getObject(key));return v;},id);
        if(rows.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        Map<String,Object> result=rows.get(0);
        for(String key:List.of("proposalJson","evidenceJson","validationJson"))try{result.put(key,json.readValue(String.valueOf(result.get(key)),Object.class));}catch(Exception e){throw new BusinessException(ErrorCode.SYSTEM_ERROR,"维护建议数据无法读取");}
        return ResultUtils.success(result);
    }

    @PostMapping("/inspections")
    @Transactional(rollbackFor=Exception.class)
    public BaseResponse<Map<String,Object>> inspect(@RequestBody Map<String,Object> body,HttpServletRequest request) {
        User admin=requireAdmin(request);
        if(!maintenanceEnabled||!modelEnabled||modelId.isBlank()||!agentAvailability.isAvailable()||dailyBudget==null||dailyBudget.signum()<=0||taskReservation==null||taskReservation.signum()<=0)
            throw new BusinessException(ErrorCode.OPERATION_ERROR,"AI 题库巡检尚未配置","MODEL_UNAVAILABLE");
        if(body==null)throw new BusinessException(ErrorCode.PARAMS_ERROR);
        long problemNo;
        try{problemNo=Long.parseLong(String.valueOf(body.get("problemNo")));}catch(Exception e){throw new BusinessException(ErrorCode.PARAMS_ERROR,"请输入自然题号");}
        if(problemNo<1)throw new BusinessException(ErrorCode.PARAMS_ERROR);
        String key;
        try{key=UUID.fromString(String.valueOf(body.get("idempotencyKey"))).toString();}catch(Exception e){throw new BusinessException(ErrorCode.PARAMS_ERROR,"缺少有效幂等键");}
        List<Map<String,Object>> existing=jdbc.query("SELECT id,status,resourceId FROM ai_task WHERE ownerUserId=? AND type='INSPECTION' AND idempotencyKey=?",
                (rs,n)->Map.of("taskId",rs.getString("id"),"status",rs.getString("status"),"problemNo",rs.getString("resourceId")),admin.getId(),key);
        if(!existing.isEmpty())return ResultUtils.success(existing.get(0));
        List<Map<String,Object>> base=jdbc.query("SELECT p.currentQuestionId,p.lifecycleVersion,p.status,q.problemVersion,q.rowVersion FROM question_public_number p "
                        +"JOIN question q ON q.id=p.currentQuestionId WHERE p.problemNo=? AND p.status='ONLINE' AND q.isPublished=1 AND q.isDelete=0",
                (rs,n)->Map.of("questionId",rs.getLong("currentQuestionId"),"lifecycleVersion",rs.getLong("lifecycleVersion"),"problemVersion",rs.getInt("problemVersion"),"rowVersion",rs.getLong("rowVersion")),problemNo);
        if(base.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND_ERROR,"该题目未上线或不存在");
        jdbc.queryForObject("SELECT id FROM `user` WHERE id=? AND userRole='admin' AND isDelete=0 FOR UPDATE",Long.class,admin.getId());
        Integer active=jdbc.queryForObject("SELECT COUNT(*) FROM ai_task WHERE ownerUserId=? AND type='INSPECTION' AND status IN ('QUEUED','RUNNING','WAITING_REVIEW')",Integer.class,admin.getId());
        Integer recent=jdbc.queryForObject("SELECT COUNT(*) FROM ai_task WHERE ownerUserId=? AND createTime>=DATE_SUB(NOW(),INTERVAL 1 MINUTE)",Integer.class,admin.getId());
        if(active!=null&&active>=3)throw new BusinessException(ErrorCode.OPERATION_ERROR,"最多同时运行 3 个 AI 巡检","TASK_CONFLICT");
        if(recent!=null&&recent>=tasksPerMinute)throw new BusinessException(ErrorCode.OPERATION_ERROR,"AI 请求过于频繁","RATE_LIMITED");
        if(!AiDailyBudget.canReserve(jdbc,LocalDate.now(BUSINESS_ZONE),taskReservation,dailyBudget))throw new BusinessException(ErrorCode.OPERATION_ERROR,"AI 平台当日预算已用完","BUDGET_EXHAUSTED");
        String taskId=UUID.randomUUID().toString();Map<String,Object> snapshot=base.get(0);Map<String,Object> taskRequest=new LinkedHashMap<>();
        taskRequest.put("problemNo",problemNo);taskRequest.putAll(snapshot);
        jdbc.update("INSERT INTO ai_task(id,type,ownerUserId,resourceId,idempotencyKey,status,modelId,promptVersion,requestJson) VALUES (?,'INSPECTION',?,?,?,'QUEUED',?,'maintenance-editorial-v1',?)",
                taskId,admin.getId(),Long.toString(problemNo),key,modelId,write(taskRequest));
        jdbc.update("INSERT INTO ai_usage(taskId,userId,usageDate,category,state,quotaExempt,reservedCost) VALUES (?,?,?,'INSPECTION','RESERVED',1,?)",taskId,admin.getId(),LocalDate.now(BUSINESS_ZONE),taskReservation);
        jdbc.update("INSERT INTO ai_outbox(taskId,eventType,status) VALUES (?,'AI_TASK','PENDING')",taskId);
        return ResultUtils.success(Map.of("taskId",taskId,"problemNo",problemNo,"status","QUEUED"));
    }

    @PostMapping("/proposals/{id}/decision")
    @Transactional(rollbackFor=Exception.class)
    public BaseResponse<Map<String,Object>> decide(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest request) {
        User admin=requireAdmin(request);String decision=String.valueOf(body.get("decision")).trim().toUpperCase(Locale.ROOT);
        long expectedRow=number(body.get("expectedProposalVersion"),"缺少建议版本号"),expectedLifecycle=number(body.get("expectedLifecycleVersion"),"缺少题目生命周期版本");
        String note=body.get("note")==null?"":String.valueOf(body.get("note")).trim();if(note.length()>1000)throw new BusinessException(ErrorCode.PARAMS_ERROR);
        if(!List.of("APPROVE","REJECT").contains(decision))throw new BusinessException(ErrorCode.PARAMS_ERROR);
        List<Map<String,Object>> rows=jdbc.query("SELECT id,taskId,problemNo,baseQuestionId,baseProblemVersion,baseLifecycleVersion,proposalJson,status,rowVersion FROM ai_maintenance_proposal WHERE id=? FOR UPDATE",
                (rs,n)->{Map<String,Object> v=new LinkedHashMap<>();for(String key:List.of("id","taskId","problemNo","baseQuestionId","baseProblemVersion","baseLifecycleVersion","proposalJson","status","rowVersion"))v.put(key,rs.getObject(key));return v;},id);
        if(rows.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);Map<String,Object> row=rows.get(0);
        if(!"WAITING_REVIEW".equals(row.get("status"))||((Number)row.get("rowVersion")).longValue()!=expectedRow)throw new BusinessException(ErrorCode.OPERATION_ERROR,"建议已被其他管理员处理","EDIT_CONFLICT");
        long problemNo=((Number)row.get("problemNo")).longValue(),sourceId=((Number)row.get("baseQuestionId")).longValue(),baseLifecycle=((Number)row.get("baseLifecycleVersion")).longValue();
        List<Map<String,Object>> current=jdbc.query("SELECT currentQuestionId,lifecycleVersion,status FROM question_public_number WHERE problemNo=? FOR UPDATE",
                (rs,n)->Map.of("questionId",rs.getObject("currentQuestionId"),"lifecycleVersion",rs.getLong("lifecycleVersion"),"status",rs.getString("status")),problemNo);
        JsonNode proposed;
        try{proposed=json.readTree(String.valueOf(row.get("proposalJson")));}catch(Exception e){throw new BusinessException(ErrorCode.SYSTEM_ERROR);}
        List<Map<String,Object>> sourceRows=jdbc.query("SELECT id,rowVersion,isPublished FROM question WHERE id=? AND isDelete=0 FOR UPDATE",
                (rs,n)->Map.of("id",rs.getLong("id"),"rowVersion",rs.getLong("rowVersion"),"published",rs.getBoolean("isPublished")),sourceId);
        boolean stale=current.isEmpty()||sourceRows.isEmpty()||!"ONLINE".equals(current.get(0).get("status"))
                ||((Number)current.get(0).get("questionId")).longValue()!=sourceId
                ||((Number)current.get(0).get("lifecycleVersion")).longValue()!=baseLifecycle
                ||baseLifecycle!=expectedLifecycle||((Number)sourceRows.get(0).get("rowVersion")).longValue()!=proposed.path("baseRowVersion").asLong(-1);
        if(stale){jdbc.update("UPDATE ai_maintenance_proposal SET status='STALE',rowVersion=rowVersion+1,reviewedBy=?,reviewedAt=NOW(),reviewNote=? WHERE id=? AND rowVersion=?",admin.getId(),"题目基准版本已变化，需重新巡检。 "+note,id,expectedRow);return ResultUtils.success(Map.of("status","STALE"));}
        Long draftId=null;
        if("APPROVE".equals(decision)) {
            if(!Boolean.TRUE.equals(sourceRows.get(0).get("published")))throw new BusinessException(ErrorCode.OPERATION_ERROR,"基准题目不再是已发布版本");
            long sourceRow=((Number)sourceRows.get(0).get("rowVersion")).longValue();draftId=lifecycle.createDraft(sourceId,sourceRow,admin.getId());
            Question draft=questions.getById(draftId);draft.setTitle(proposed.path("title").asText());draft.setContent(proposed.path("content").asText());
            questions.validQuestion(draft,false);
            boolean updated=questions.update(null,new UpdateWrapper<Question>().eq("id",draftId).eq("rowVersion",0)
                    .set("title",draft.getTitle()).set("content",draft.getContent()).set("rowVersion",1));
            if(!updated)throw new BusinessException(ErrorCode.OPERATION_ERROR,"新草稿已被修改","EDIT_CONFLICT");
        }
        String next="APPROVE".equals(decision)?"APPROVED":"REJECTED";
        jdbc.update("UPDATE ai_maintenance_proposal SET status=?,rowVersion=rowVersion+1,reviewedBy=?,reviewedAt=NOW(),reviewNote=?,approvedDraftId=? WHERE id=? AND rowVersion=?",
                next,admin.getId(),note.isBlank()?null:note,draftId,id,expectedRow);
        jdbc.update("UPDATE ai_task SET status='SUCCEEDED',stage='REVIEWED',completedAt=NOW(),resultJson=JSON_SET(COALESCE(resultJson,'{}'),'$.reviewStatus',?) WHERE id=? AND status='WAITING_REVIEW'",next,row.get("taskId"));
        jdbc.update("INSERT INTO ai_action_audit(taskId,actorType,actorId,action,resourceType,resourceId,inputHash,outcome,detail) VALUES (?,'ADMIN',?,?,'AI_PROPOSAL',?,SHA2(?,256),?,?)",
                row.get("taskId"),String.valueOf(admin.getId()),"APPROVE".equals(decision)?"APPROVE_MAINTENANCE_PROPOSAL":"REJECT_MAINTENANCE_PROPOSAL",id,String.valueOf(row.get("proposalJson")),next,note);
        Map<String,Object> result=new LinkedHashMap<>();result.put("status",next);if(draftId!=null)result.put("draftQuestionId",draftId);return ResultUtils.success(result);
    }

    private User requireAdmin(HttpServletRequest request){User user=users.getLoginUser(request);if(user==null||user.getId()==null||!"admin".equals(user.getUserRole()))throw new BusinessException(ErrorCode.NO_AUTH_ERROR);return user;}
    private long number(Object value,String message){try{return Long.parseLong(String.valueOf(value));}catch(Exception e){throw new BusinessException(ErrorCode.PARAMS_ERROR,message);}}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new BusinessException(ErrorCode.SYSTEM_ERROR);}}
    private Object[] append(List<Object> args,Object...tail){List<Object> all=new ArrayList<>(args);all.addAll(Arrays.asList(tail));return all.toArray();}
}
