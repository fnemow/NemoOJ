package com.nemo.OJbackendquestionservice.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Recovers tasks after a worker disappears, using the task lease as the authority. */
@Slf4j
@Component
public class AiTaskLeaseReaper {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    @Value("${ai.model.max-attempts:2}") private int maxAttempts;
    @Value("${ai.notification.email:}") private String escalationEmail;
    @Value("${ai.outbox.batch-size:20}") private int batchSize;

    public AiTaskLeaseReaper(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}

    @Scheduled(fixedDelayString="${ai.task-reaper.poll-interval-ms:15000}")
    @Transactional(rollbackFor=Exception.class)
    public void recoverExpiredLeases(){
        // A completed validation can be queued for resume during a broker outage.
        // It retains the original deadline even though it currently has no worker.
        jdbc.update("UPDATE ai_task SET status='RUNNING',leaseOwner=NULL,leaseExpireAt=DATE_ADD(startedAt,INTERVAL 900 SECOND) WHERE type='PRIVATE_PROBLEM' AND status='QUEUED' AND stage='VALIDATION_READY' AND startedAt<=DATE_SUB(NOW(),INTERVAL 900 SECOND)");
        List<Row> rows;
        try { rows=jdbc.query("SELECT id,type,ownerUserId,resourceId,requestJson,attemptCount,startedAt<=DATE_SUB(NOW(),INTERVAL 900 SECOND) AS deadlineExpired FROM ai_task WHERE status='RUNNING' AND leaseExpireAt<NOW() ORDER BY leaseExpireAt LIMIT ?",
                (rs,row)->new Row(rs.getString("id"),rs.getString("type"),rs.getObject("ownerUserId")==null?null:rs.getLong("ownerUserId"),
                        rs.getString("resourceId"),rs.getString("requestJson"),rs.getInt("attemptCount"),rs.getBoolean("deadlineExpired")),batchSize); }
        catch(Exception e){log.warn("AI task lease scan failed");return;}
        for(Row task:rows) {
            if(task.attempts()<maxAttempts&&!("PRIVATE_PROBLEM".equals(task.type())&&task.deadlineExpired())) {
                int requeued=jdbc.update("UPDATE ai_task SET status='QUEUED',stage='LEASE_RECOVERY',errorCode='MODEL_TIMEOUT',errorMessage='AI worker lease expired; retrying',leaseOwner=NULL,leaseExpireAt=NULL WHERE id=? AND status='RUNNING' AND leaseExpireAt<NOW()",task.id());
                if(requeued>0) jdbc.update("INSERT INTO ai_outbox(taskId,eventType,status,nextAttemptAt) VALUES (?,'AI_TASK','PENDING',DATE_ADD(NOW(),INTERVAL 10 SECOND)) "
                        + "ON DUPLICATE KEY UPDATE status='PENDING',retryCount=0,nextAttemptAt=DATE_ADD(NOW(),INTERVAL 10 SECOND),leaseExpireAt=NULL,lastError=NULL",task.id());
                continue;
            }
            int failed=jdbc.update("UPDATE ai_task SET status='FAILED',stage='LEASE_EXPIRED',errorCode='MODEL_TIMEOUT',errorMessage='AI worker stopped responding; please retry later',leaseOwner=NULL,leaseExpireAt=NULL,completedAt=NOW() WHERE id=? AND status='RUNNING' AND leaseExpireAt<NOW()",task.id());
            if(failed==0)continue;
            jdbc.update("UPDATE ai_usage SET state='RELEASED' WHERE taskId=? AND state='RESERVED'",task.id());
            switch(task.type()) {
                case "PLAN","PLAN_ADJUST" -> jdbc.update("UPDATE study_plan SET status='NEEDS_REVIEW' WHERE id=? AND status IN ('GENERATING','ADJUSTING')",task.resource());
                case "PRIVATE_PROBLEM" -> rejectPrivate(task);
                case "FEEDBACK" -> escalateFeedback(task);
                default -> { }
            }
        }
        // A confirmed RabbitMQ publish can still be consumed while the
        // question service is restarting. If the worker cannot claim it, a
        // queued task must eventually get another delivery instead of
        // remaining stuck behind a SENT outbox row forever.
        jdbc.update("UPDATE ai_outbox o JOIN ai_task t ON t.id=o.taskId SET "
                + "o.status='PENDING',o.retryCount=0,o.nextAttemptAt=NOW(),o.leaseExpireAt=NULL,o.lastError=NULL "
                + "WHERE t.status='QUEUED' AND o.status='SENT' "
                + "AND o.updateTime<DATE_SUB(NOW(),INTERVAL 5 MINUTE) "
                + "AND t.updateTime<DATE_SUB(NOW(),INTERVAL 5 MINUTE)");
    }

    private void rejectPrivate(Row task){
        jdbc.update("UPDATE private_problem SET status='REJECTED',rejectReason='AI 服务超时，请重新生成' WHERE id=? AND generationTaskId=?",task.resource(),task.id());
        try {
            JsonNode request=json.readTree(task.requestJson()==null?"{}":task.requestJson());long no=request.path("fallbackProblemNo").asLong(0);
            if(no<1)return;
            List<Map<String,Object>> fallback=jdbc.query("SELECT p.currentQuestionId,q.problemVersion,q.title FROM question_public_number p JOIN question q ON q.id=p.currentQuestionId WHERE p.problemNo=? AND p.status='ONLINE' AND q.isPublished=1 AND q.isDelete=0",
                    (rs,row)->{Map<String,Object> m=new LinkedHashMap<>();m.put("id",rs.getLong("currentQuestionId"));m.put("version",rs.getInt("problemVersion"));m.put("title",rs.getString("title"));return m;},no);
            if(fallback.isEmpty()) jdbc.update("UPDATE study_plan_item SET itemStatus='UNAVAILABLE',titleSnapshot='私人题生成失败，回退题已下线',reason='私人题生成超时，预设公开回退题已下线，请重新调整计划' WHERE privateProblemId=? AND itemStatus='TODO'",task.resource());
            else {Map<String,Object> q=fallback.get(0);jdbc.update("UPDATE study_plan_item SET source='PUBLIC',problemNo=?,publicQuestionId=?,publicVersion=?,privateProblemId=NULL,privateVersionId=NULL,titleSnapshot=?,reason=? WHERE privateProblemId=? AND itemStatus='TODO'",no,q.get("id"),q.get("version"),q.get("title"),"私人题生成超时，已改为公开题 #"+no,task.resource());}
        }catch(Exception e){log.warn("Could not apply private problem fallback for task {}",task.id());}
    }

    private void escalateFeedback(Row task){
        if(task.owner()==null)return;
        long feedbackId;
        try{feedbackId=Long.parseLong(task.resource());}catch(Exception e){return;}
        int updated=jdbc.update("UPDATE feedback SET agentStatus='ESCALATED',rowVersion=rowVersion+1 WHERE id=? AND userId=? AND agentStatus<>'TAKEN_OVER' AND status='PENDING'",feedbackId,task.owner());
        if(updated==0||escalationEmail==null||escalationEmail.isBlank())return;
        jdbc.update("INSERT IGNORE INTO ai_notification_outbox(dedupeKey,recipient,subject,bodyText,status) VALUES (?,?,?,?,'PENDING')",
                "feedback:"+feedbackId+":escalation",escalationEmail,"NemoOJ 反馈需要人工处理 #"+feedbackId,
                "反馈 #"+feedbackId+" 的 AI 处理任务超时，请在反馈处理页面查看并回复。\n后台地址：/admin/feedback");
    }

    private record Row(String id,String type,Long owner,String resource,String requestJson,int attempts,boolean deadlineExpired){}
}
