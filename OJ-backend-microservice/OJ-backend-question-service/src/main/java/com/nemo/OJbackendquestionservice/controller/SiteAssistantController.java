package com.nemo.OJbackendquestionservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nemo.OJbackendcommon.common.*;
import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendmodel.model.entity.User;
import com.nemo.OJbackendquestionservice.budget.AiDailyBudget;
import com.nemo.OJbackendquestionservice.service.*;
import com.nemo.OJserviceclient.UserFeignClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Site usage only: this controller never queries submissions, editor drafts or analysis reports. */
@RestController
@RequestMapping("/assistant")
public class SiteAssistantController {
    private final JdbcTemplate jdbc;private final UserFeignClient users;private final ObjectMapper json;
    private final SiteHelpPolicy policy;private final AiAgentAvailability availability;
    @Value("${ai.site-help.enabled:true}") private boolean enabled;
    @Value("${ai.model.enabled:false}") private boolean modelEnabled;
    @Value("${ai.model.id:}") private String modelId;
    @Value("${ai.budget.daily-usd:0}") private BigDecimal dailyBudget;
    @Value("${ai.budget.max-task-reservation-usd:0}") private BigDecimal reservation;
    public SiteAssistantController(JdbcTemplate jdbc,UserFeignClient users,ObjectMapper json,SiteHelpPolicy policy,AiAgentAvailability availability){this.jdbc=jdbc;this.users=users;this.json=json;this.policy=policy;this.availability=availability;}
    @GetMapping("/guides") public BaseResponse<?> guides(){return ResultUtils.success(policy.catalog());}
    @PostMapping("/ask") @Transactional(rollbackFor=Exception.class)
    public BaseResponse<Map<String,Object>> ask(@RequestBody Map<String,Object> body,HttpServletRequest request){
        User user=member(request);
        if(body==null||!Set.of("message","history","idempotencyKey").containsAll(body.keySet()))throw new BusinessException(ErrorCode.PARAMS_ERROR,"只接受网站问答文字，不接受代码或提交数据");
        if(!(body.get("message") instanceof String message)||message.isBlank()||message.length()>800)throw new BusinessException(ErrorCode.PARAMS_ERROR,"问题长度应为1至800字");
        Object rawKey=body.get("idempotencyKey");if(!(rawKey instanceof String key)||!key.matches("[A-Za-z0-9_-]{8,64}"))throw new BusinessException(ErrorCode.PARAMS_ERROR);
        List<Map<String,String>> history=history(body.get("history"));
        if(policy.isCodeQuestion(message))return ResultUtils.success(Map.of("status","SUCCEEDED","answer",policy.redirectCode()));
        history=history.stream().filter(turn->!policy.isCodeQuestion(turn.get("text"))).toList();
        String role=jdbc.queryForObject("SELECT userRole FROM user WHERE id=? AND isDelete=0 FOR UPDATE",String.class,user.getId());if(!"user".equals(role))throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        var old=jdbc.queryForList("SELECT id,status FROM ai_task WHERE type='SITE_HELP' AND ownerUserId=? AND idempotencyKey=?",user.getId(),key);
        if(!old.isEmpty())return ResultUtils.success(Map.of("taskId",old.get(0).get("id"),"status",old.get(0).get("status")));
        Integer recent=jdbc.queryForObject("SELECT COUNT(*) FROM ai_task WHERE ownerUserId=? AND type='SITE_HELP' AND createTime>=DATE_SUB(NOW(),INTERVAL 1 MINUTE)",Integer.class,user.getId());
        Integer active=jdbc.queryForObject("SELECT COUNT(*) FROM ai_task WHERE ownerUserId=? AND type='SITE_HELP' AND status IN ('QUEUED','RUNNING')",Integer.class,user.getId());
        if(recent!=null&&recent>=10||active!=null&&active>=1)throw new BusinessException(ErrorCode.OPERATION_ERROR,"网站助手请求过于频繁，请稍后重试","SITE_HELP_RATE_LIMITED");
        if(!enabled||!modelEnabled||modelId.isBlank()||!availability.isAvailable()||dailyBudget.signum()<=0||reservation.signum()<=0)return ResultUtils.success(Map.of("status","SUCCEEDED","answer",policy.unavailable()));
        LocalDate date=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        if(!AiDailyBudget.canReserve(jdbc,date,reservation,dailyBudget))return ResultUtils.success(Map.of("status","SUCCEEDED","answer",policy.unavailable()));
        String id=UUID.randomUUID().toString();String payload;
        try{payload=json.writeValueAsString(Map.of("message",message.trim(),"history",history));}catch(Exception e){throw new BusinessException(ErrorCode.PARAMS_ERROR);}
        jdbc.update("INSERT INTO ai_task(id,type,ownerUserId,idempotencyKey,status,modelId,promptVersion,requestJson) VALUES (?,'SITE_HELP',?,?,'QUEUED',?,'site-help-v1',?)",id,user.getId(),key,modelId,payload);
        jdbc.update("INSERT INTO ai_usage(taskId,userId,usageDate,category,state,quotaExempt,reservedCost) VALUES (?,?,?,'SITE_HELP','RESERVED',1,?)",id,user.getId(),date,reservation);
        jdbc.update("INSERT INTO ai_outbox(taskId,eventType,status) VALUES (?,'AI_TASK','PENDING')",id);
        return ResultUtils.success(Map.of("taskId",id,"status","QUEUED"));
    }
    @GetMapping("/answers/{id}") public BaseResponse<Map<String,Object>> answer(@PathVariable String id,HttpServletRequest request){
        User user=member(request);
        var rows=jdbc.query("SELECT status,resultJson,errorCode FROM ai_task WHERE id=? AND ownerUserId=? AND type='SITE_HELP'",(rs,n)->{Map<String,Object> row=new LinkedHashMap<>();row.put("status",rs.getString(1));row.put("answer",parse(rs.getString(2)));row.put("errorCode",rs.getString(3));return row;},id,user.getId());
        if(rows.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);return ResultUtils.success(rows.get(0));
    }
    private Object parse(String raw){if(raw==null)return null;try{return json.readValue(raw,Map.class);}catch(Exception e){throw new BusinessException(ErrorCode.SYSTEM_ERROR);}}
    private User member(HttpServletRequest request){User user=users.getLoginUser(request);if(!"user".equals(user.getUserRole()))throw new BusinessException(ErrorCode.NO_AUTH_ERROR);return user;}
    private List<Map<String,String>> history(Object raw){
        if(raw==null)return List.of();if(!(raw instanceof List<?> list)||list.size()>6)throw new BusinessException(ErrorCode.PARAMS_ERROR);
        List<Map<String,String>> result=new ArrayList<>();
        for(Object entry:list){if(!(entry instanceof Map<?,?> item)||!Set.of("role","text").containsAll(item.keySet())||!(item.get("role") instanceof String role)||!Set.of("user","assistant").contains(role)||!(item.get("text") instanceof String text)||text.length()>1800)throw new BusinessException(ErrorCode.PARAMS_ERROR);result.add(Map.of("role",role,"text",text));}
        return result;
    }
}
