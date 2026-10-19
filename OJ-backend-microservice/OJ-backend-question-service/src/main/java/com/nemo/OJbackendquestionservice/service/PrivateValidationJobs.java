package com.nemo.OJbackendquestionservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nemo.OJbackendcommon.common.ErrorCode;
import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendmodel.model.dto.question.*;
import com.nemo.OJbackendmodel.model.entity.Question;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Durable validation jobs. Sandbox calls run on one dedicated thread with no DB transaction. */
@Service @Slf4j
public class PrivateValidationJobs {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private io.micrometer.core.instrument.MeterRegistry metrics;
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final PrivateProblemValidationGateway gateway;
    private final TransactionTemplate transaction;
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"private-validation");t.setDaemon(true);return t;});
    private final AtomicBoolean running=new AtomicBoolean();
    private final ScheduledExecutorService renewer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"private-validation-lease");t.setDaemon(true);return t;});
    public PrivateValidationJobs(JdbcTemplate jdbc,ObjectMapper json,PrivateProblemValidationGateway gateway,PlatformTransactionManager manager){this.jdbc=jdbc;this.json=json;this.gateway=gateway;this.transaction=new TransactionTemplate(manager);}
    public Map<String,Object> submit(String task,String worker,int revision,JsonNode draft) {
        if(revision<1||revision>3||!draft.isObject()||draft.toString().length()>600000)throw new BusinessException(ErrorCode.PARAMS_ERROR);
        return transaction.execute(s->{
            owned(task,worker);
            String hash=hash(draft);var old=jdbc.queryForList("SELECT id,draftHash FROM private_validation_job WHERE taskId=? AND revision=?",task,revision);
            if(!old.isEmpty()){if(!hash.equals(old.get(0).get("draftHash")))throw new BusinessException(ErrorCode.OPERATION_ERROR,"同一修订不能覆盖候选","EDIT_CONFLICT");return read(String.valueOf(old.get(0).get("id")),task);}
            Integer max=jdbc.queryForObject("SELECT COALESCE(MAX(revision),0) FROM private_validation_job WHERE taskId=?",Integer.class,task);
            if(revision!=max+1)throw new BusinessException(ErrorCode.OPERATION_ERROR,"验证修订号不连续","EDIT_CONFLICT");
            if(max>0&&!"FAILED".equals(jdbc.queryForObject("SELECT status FROM private_validation_job WHERE taskId=? AND revision=?",String.class,task,max))){
                var prior=jdbc.queryForMap("SELECT status,resultJson FROM private_validation_job WHERE taskId=? AND revision=?",task,max);
                try{
                    var proof=json.readTree(String.valueOf(prior.get("resultJson")));
                    if(!"SUCCEEDED".equals(prior.get("status"))||!proof.path("pythonPreflightValidated").asBoolean()
                        ||!"CANONICAL_LINES_V1".equals(draft.path("generation").path("protocol").asText())
                        ||!pythonBinding(draft).equals(proof.path("preflightBinding").asText()))throw new IllegalArgumentException();
                }catch(Exception invalid){throw new BusinessException(ErrorCode.OPERATION_ERROR,"上一修订尚未失败或预检证据不匹配","EDIT_CONFLICT");}
            }
            String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO private_validation_job(id,taskId,revision,draftHash,draftJson) VALUES (?,?,?,?,?)",id,task,revision,hash,draft.toString());
            jdbc.update("UPDATE ai_task SET stage='VALIDATION_QUEUED' WHERE id=?",task);return read(id,task);
        });
    }
    public Map<String,Object> poll(String task,String worker,String id) {
        owned(task,worker);return read(id,task);
    }
    /** Called only after Agent persisted its LangGraph interrupt. No new task attempt. */
    public void suspend(String task,String worker,String validation) {
        transaction.executeWithoutResult(s->{
            // Lock the task before the job, matching submit's lock order.
            var rows=jdbc.queryForList("SELECT status,leaseOwner,stage FROM ai_task WHERE id=? AND type='PRIVATE_PROBLEM' FOR UPDATE",task);
            Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM private_validation_job WHERE id=? AND taskId=? AND revision=(SELECT MAX(revision) FROM private_validation_job WHERE taskId=?) AND status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')",Integer.class,validation,task,task);
            if(n==null||n!=1)throw new BusinessException(ErrorCode.OPERATION_ERROR,"验证任务不匹配","EDIT_CONFLICT");
            if(rows.size()==1 && "WAIT_VALIDATION".equals(rows.get(0).get("stage")) && rows.get(0).get("leaseOwner")==null)return;
            // Retried response after atomic wake: the durable checkpoint/message already exists.
            if(rows.size()==1 && "QUEUED".equals(rows.get(0).get("status")) && "VALIDATION_READY".equals(rows.get(0).get("stage")))return;
            owned(task,worker);
            int changed=jdbc.update("UPDATE ai_task SET stage='WAIT_VALIDATION',leaseOwner=NULL,leaseExpireAt=DATE_ADD(startedAt,INTERVAL 900 SECOND) WHERE id=? AND status='RUNNING' AND leaseOwner=?",task,worker);
            if(changed!=1)throw new BusinessException(ErrorCode.OPERATION_ERROR,"任务租约已变更","AI_LEASE_LOST");
            wakeOne(task);
        });
    }
    private void wakeOne(String task) {
        // Result is authoritative in MySQL; the message contains no generated code.
        int changed=jdbc.update("UPDATE ai_task t SET t.status='QUEUED',t.stage='VALIDATION_READY',t.leaseExpireAt=NULL WHERE t.id=? AND t.type='PRIVATE_PROBLEM' AND t.status='RUNNING' AND t.stage='WAIT_VALIDATION' AND t.leaseOwner IS NULL AND t.startedAt>DATE_SUB(NOW(),INTERVAL 900 SECOND) AND EXISTS (SELECT 1 FROM private_validation_job v WHERE v.taskId=t.id AND v.revision=(SELECT MAX(x.revision) FROM private_validation_job x WHERE x.taskId=t.id) AND v.status IN ('SUCCEEDED','FAILED'))",task);
        if(changed==1){
            Integer revision=jdbc.queryForObject("SELECT MAX(revision) FROM private_validation_job WHERE taskId=?",Integer.class,task);
            jdbc.update("INSERT INTO ai_outbox(taskId,eventType,status,nextAttemptAt) VALUES (?,?,'PENDING',NOW()) ON DUPLICATE KEY UPDATE status='PENDING',retryCount=0,nextAttemptAt=NOW(),leaseExpireAt=NULL,lastError=NULL",task,"VALIDATION_"+revision);
        }
    }
    @Scheduled(fixedDelayString="${ai.training.validation.wake-poll-ms:5000}")
    public void wakeCompleted() {
        // Transactional rescan closes result-before-suspend and lost-notification races.
        var tasks=jdbc.query("SELECT id FROM ai_task WHERE type='PRIVATE_PROBLEM' AND status='RUNNING' AND stage='WAIT_VALIDATION' AND startedAt>DATE_SUB(NOW(),INTERVAL 900 SECOND) ORDER BY startedAt LIMIT 20",(rs,row)->rs.getString(1));
        for(String task:tasks)transaction.executeWithoutResult(s->wakeOne(task));
    }
    public void heartbeat(String task,String worker,String stage) {
        heartbeat(task,worker,stage,0);
    }
    public void heartbeat(String task,String worker,String stage,int repairs) {
        if(repairs<0||repairs>2)throw new BusinessException(ErrorCode.PARAMS_ERROR);
        if(!Set.of("KEEP","GENERATING","PRECHECK","VALIDATION_QUEUED","VALIDATING","REPAIRING","PUBLISHING").contains(stage))throw new BusinessException(ErrorCode.PARAMS_ERROR);
        int n=jdbc.update("UPDATE ai_task SET stage=COALESCE(?,stage),generationRepairCount=GREATEST(generationRepairCount,?),leaseExpireAt=DATE_ADD(NOW(),INTERVAL 60 SECOND) WHERE id=? AND type='PRIVATE_PROBLEM' AND status='RUNNING' AND leaseOwner=? AND leaseExpireAt>=NOW() AND startedAt>DATE_SUB(NOW(),INTERVAL 900 SECOND)","KEEP".equals(stage)?null:stage,repairs,task,worker);
        if(n!=1)throw new BusinessException(ErrorCode.OPERATION_ERROR,"任务已超时或租约已变更","AI_LEASE_LOST");
    }
    private void owned(String task,String worker) {
        Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM ai_task WHERE id=? AND type='PRIVATE_PROBLEM' AND status='RUNNING' AND leaseOwner=? AND leaseExpireAt>=NOW() AND startedAt>DATE_SUB(NOW(),INTERVAL 900 SECOND)",Integer.class,task,worker);
        if(n==null||n!=1)throw new BusinessException(ErrorCode.OPERATION_ERROR,"任务已超时或租约已变更","AI_LEASE_LOST");
    }
    private Map<String,Object> read(String id,String task) {
        var rows=jdbc.query("SELECT id,revision,status,resultJson FROM private_validation_job WHERE id=? AND taskId=?",(rs,row)->{Map<String,Object> v=new LinkedHashMap<>();v.put("validationId",rs.getString(1));v.put("revision",rs.getInt(2));v.put("status",rs.getString(3));try{v.put("result",rs.getString(4)==null?null:json.readValue(rs.getString(4),Object.class));}catch(Exception e){throw new IllegalStateException(e);}return v;},id,task);
        if(rows.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);return rows.get(0);
    }
    public Map<String,Object> proof(String task,JsonNode draft) {
        var rows=jdbc.query("SELECT resultJson FROM private_validation_job WHERE taskId=? AND draftHash=? AND status='SUCCEEDED' AND revision=(SELECT MAX(revision) FROM private_validation_job WHERE taskId=?)",(rs,row)->rs.getString(1),task,hash(draft),task);
        if(rows.isEmpty()){
            // A canonical job is keyed by the pre-answer candidate for replay safety.
            // Publication is instead bound to the exact server-materialized draft.
            var generated=jdbc.query("SELECT resultJson FROM private_validation_job WHERE taskId=? AND status='SUCCEEDED' AND revision=(SELECT MAX(revision) FROM private_validation_job WHERE taskId=?)",(rs,row)->rs.getString(1),task,task);
            try{if(!generated.isEmpty()){
                var proof=json.readValue(generated.get(0),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
                completeProof(proof);
                if(Boolean.TRUE.equals(proof.get("oracleValidated"))&&hash(draft).equals(proof.get("normalizedDraftHash"))){proof.remove("normalizedDraft");return proof;}
            }}catch(BusinessException e){throw e;}catch(Exception e){throw new BusinessException(ErrorCode.SYSTEM_ERROR);}
            throw new BusinessException(ErrorCode.OPERATION_ERROR,"候选缺少当前修订的完整验证证据","VALIDATION_REQUIRED");
        }
        try{var proof=json.readValue(rows.get(0),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
            completeProof(proof);
            if(proof.containsKey("normalizedDraftHash"))throw new BusinessException(ErrorCode.OPERATION_ERROR,"必须使用已计算答案的候选","VALIDATION_REQUIRED");
            return proof;
        }catch(BusinessException e){throw e;}catch(Exception e){throw new BusinessException(ErrorCode.SYSTEM_ERROR);}
    }
    public static String hash(JsonNode draft) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(draft.toString().getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}
    }
    static String pythonBinding(JsonNode draft){
        var value=(com.fasterxml.jackson.databind.node.ObjectNode)draft.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)value.path("generation")).remove("protocol");
        ((com.fasterxml.jackson.databind.node.ObjectNode)value.path("referenceSolutions")).remove(List.of("java:FUNCTION","cpp:FUNCTION"));
        return hash(value);
    }
    private static void completeProof(Map<String,Object> proof){
        if(!Boolean.TRUE.equals(proof.get("passed"))||!Boolean.TRUE.equals(proof.get("runtimeValidated"))
            ||Boolean.TRUE.equals(proof.get("pythonPreflightValidated"))||!(proof.get("combinations") instanceof List<?> combinations)
            ||!new HashSet<>(combinations).equals(Set.of("java:ACM","java:FUNCTION","python:ACM","python:FUNCTION","cpp:ACM","cpp:FUNCTION")))
            throw new BusinessException(ErrorCode.OPERATION_ERROR,"预检不能用于发布，必须完成六组合验证","VALIDATION_REQUIRED");
    }
    @Scheduled(fixedDelayString="${ai.training.validation.poll-ms:2000}")
    public void dispatch() {
        if(!running.compareAndSet(false,true))return;
        try {
            jdbc.update("UPDATE private_validation_job v JOIN ai_task t ON t.id=v.taskId SET v.status='CANCELLED',v.leaseToken=NULL,v.leaseExpireAt=NULL,v.completedAt=NOW() WHERE v.status IN ('QUEUED','RUNNING') AND (t.status IN ('FAILED','CANCELLED','SUCCEEDED') OR t.startedAt<DATE_SUB(NOW(),INTERVAL 900 SECOND))");
            // Reserve background work for idle periods; normal user judging has precedence.
            Integer foreground=jdbc.queryForObject("SELECT COUNT(*) FROM question_submit WHERE status IN (0,1) AND isDelete=0",Integer.class);
            if(foreground!=null&&foreground>0){running.set(false);return;}
            var ids=jdbc.query("SELECT v.id FROM private_validation_job v JOIN ai_task t ON t.id=v.taskId WHERE t.status='RUNNING' AND t.startedAt>DATE_SUB(NOW(),INTERVAL 900 SECOND) AND ((v.status='QUEUED' AND v.nextAttemptAt<=NOW()) OR (v.status='RUNNING' AND v.leaseExpireAt<NOW())) ORDER BY v.createTime LIMIT 1",(rs,row)->rs.getString(1));
            if(ids.isEmpty()){running.set(false);return;}
            executor.submit(()->{try{execute(ids.get(0));}catch(Exception e){log.warn("Private validation job failed, id={}",ids.get(0),e);}finally{running.set(false);}});
        }catch(Exception e){running.set(false);log.warn("Private validation queue scan failed",e);}
    }
    private void execute(String id) {
        long validationStarted=System.nanoTime();
        String token=UUID.randomUUID().toString();
        int n=jdbc.update("UPDATE private_validation_job SET status='RUNNING',leaseToken=?,leaseExpireAt=DATE_ADD(NOW(),INTERVAL 90 SECOND),attemptCount=attemptCount+1,startedAt=COALESCE(startedAt,NOW()) WHERE id=? AND (status='QUEUED' OR (status='RUNNING' AND leaseExpireAt<NOW()))",token,id);
        if(n!=1)return;
        var row=jdbc.queryForMap("SELECT taskId,draftJson,attemptCount FROM private_validation_job WHERE id=?",id);String task=String.valueOf(row.get("taskId"));
        jdbc.update("UPDATE ai_task SET stage='VALIDATING' WHERE id=? AND status='RUNNING' AND stage<>'WAIT_VALIDATION'",task);
        Map<String,Object> result;
        ScheduledFuture<?> lease=renewer.scheduleAtFixedRate(()->{
            try{jdbc.update("UPDATE private_validation_job SET leaseExpireAt=DATE_ADD(NOW(),INTERVAL 90 SECOND) WHERE id=? AND leaseToken=? AND status='RUNNING'",id,token);}
            catch(Exception e){log.warn("Private validation lease renewal failed, id={}",id);}
        },20,20,TimeUnit.SECONDS);
        try {
            JsonNode d=json.readTree(String.valueOf(row.get("draftJson")));var request=precheck(d);
            request.setValidationId(id);
            request.setValidationLeaseToken(token);
            result=gateway.validate(request);
            if(result==null)throw new IllegalStateException("empty validator response");
            if(Boolean.TRUE.equals(result.get("paused")) && "CAPACITY".equals(result.get("category"))){
                // Admission rejected before execution: pause the same job, not a model repair
                // or an infrastructure failure. The original 15-minute task deadline stays.
                jdbc.update("UPDATE private_validation_job SET status='QUEUED',leaseToken=NULL,leaseExpireAt=NULL,attemptCount=GREATEST(0,attemptCount-1),nextAttemptAt=DATE_ADD(NOW(),INTERVAL 3 SECOND) WHERE id=? AND leaseToken=?",id,token);
                jdbc.update("UPDATE ai_task SET stage='VALIDATION_QUEUED' WHERE id=? AND status='RUNNING' AND stage<>'WAIT_VALIDATION'",task);
                if(metrics!=null)metrics.counter("nemo.ai.private.validation.pauses").increment();
                return;
            }
            boolean preflight="CANONICAL_PYTHON_PREFLIGHT_V1".equals(d.path("generation").path("protocol").asText());
            if(Boolean.TRUE.equals(result.get("passed"))){
                if(preflight){
                    if(!Boolean.TRUE.equals(result.get("pythonPreflightValidated"))||!Boolean.TRUE.equals(result.get("oracleValidated"))||Boolean.TRUE.equals(result.get("runtimeValidated")))throw new IllegalStateException("invalid preflight proof");
                    result=new LinkedHashMap<>(result);result.put("preflightBinding",pythonBinding(d));
                }else completeProof(result);
            }
            if(!preflight&&d.has("generation")&&Boolean.TRUE.equals(result.get("passed"))){
                if(!Boolean.TRUE.equals(result.get("oracleValidated")))throw new IllegalStateException("oracle proof required");
                if(d.path("generation").has("templateId") && (!Boolean.TRUE.equals(result.get("propertiesValidated"))
                    ||!d.path("generation").path("templateId").asText().equals(result.get("templateId"))
                    ||!d.path("generation").path("templateVersion").asText().equals(result.get("templateVersion"))))throw new IllegalStateException("matching template property proof required");
                var normalized=(com.fasterxml.jackson.databind.node.ObjectNode)d.deepCopy();normalized.remove("generation");
                normalized.set("judgeCases",json.valueToTree(result.get("acmCases")));
                ((com.fasterxml.jackson.databind.node.ObjectNode)normalized.path("problemSpec")).set("judgeCases",json.valueToTree(result.get("functionCases")));
                normalized.set("referenceSolutions",json.valueToTree(result.get("referenceSolutions")));
                precheck(normalized); // Materialization must satisfy the ordinary publication contract.
                result=new LinkedHashMap<>(result);result.remove("acmCases");result.remove("functionCases");result.remove("referenceSolutions");
                result.put("normalizedDraft",normalized);result.put("normalizedDraftHash",hash(normalized));
            }
        }catch(IllegalArgumentException|com.fasterxml.jackson.core.JsonProcessingException invalid){result=Map.of("passed",false,"category","SCHEMA","diagnostic",safe(invalid.getMessage()));}
        catch(Exception infra){
            if(((Number)row.get("attemptCount")).intValue()<3){jdbc.update("UPDATE private_validation_job SET status='QUEUED',leaseToken=NULL,leaseExpireAt=NULL,nextAttemptAt=DATE_ADD(NOW(),INTERVAL 10 SECOND) WHERE id=? AND leaseToken=?",id,token);return;}
            result=Map.of("passed",false,"category","INFRASTRUCTURE","diagnostic","验证服务暂不可用，稍后可重试");
        }finally{lease.cancel(false);}
        try {
            jdbc.update("UPDATE private_validation_job SET status=?,resultJson=?,completedAt=NOW(),leaseToken=NULL,leaseExpireAt=NULL WHERE id=? AND leaseToken=?",Boolean.TRUE.equals(result.get("passed"))?"SUCCEEDED":"FAILED",json.writeValueAsString(result),id,token);
            transaction.executeWithoutResult(s->wakeOne(task));
            String category=Boolean.TRUE.equals(result.get("passed"))?"PASSED":String.valueOf(result.get("category"));
            if(!Set.of("PASSED","SCHEMA","COMPILE","RUNTIME","WRONG_ANSWER","ORACLE_DISAGREEMENT","PROPERTY_VIOLATION","PROTOCOL","INFRASTRUCTURE").contains(category))category="OTHER";
            if(metrics!=null){io.micrometer.core.instrument.Timer.builder("nemo.ai.private.validation").tag("result",category).publishPercentileHistogram().register(metrics).record(System.nanoTime()-validationStarted,TimeUnit.NANOSECONDS);metrics.counter("nemo.ai.private.validation.results","result",category).increment();}
            log.info("Private validation completed, taskId={}, passed={}, category={}, elapsedMs={}",task,result.get("passed"),category,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-validationStarted));
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    public PrivateProblemValidationRequest precheck(JsonNode d) throws com.fasterxml.jackson.core.JsonProcessingException {
        if(!d.isObject()||d.path("title").asText().length()<3||d.path("contentMarkdown").asText().length()<100)throw new IllegalArgumentException("题面或标题不完整");
        Question q=new Question();q.setTitle(d.path("title").asText());q.setContent(d.path("contentMarkdown").asText());
        q.setJudgeConfig(json.treeToValue(d.path("judgeConfig"),JudgeConfig.class));q.setProblemSpec(json.treeToValue(d.path("problemSpec"),ProblemSpec.class));
        List<JudgeCase> cases=json.convertValue(d.path("judgeCases"),new com.fasterxml.jackson.core.type.TypeReference<>(){});q.setJudgeCase(cases);
        var spec=q.getProblemSpec();var cfg=q.getJudgeConfig();
        com.nemo.OJbackendmodel.model.dto.question.CustomStructureContract.validate(spec);
        if(cases==null||cases.size()!=20||spec==null||spec.getJudgeCases()==null||spec.getJudgeCases().size()!=20||spec.getParameters()==null||spec.getParameters().isEmpty())throw new IllegalArgumentException("两种模式各需 20 组用例及完整参数");
        if(cfg==null||cfg.getTimeLimit()==null||cfg.getTimeLimit()<100||cfg.getTimeLimit()>10000||cfg.getMemoryLimit()==null||cfg.getMemoryLimit()<16384||cfg.getMemoryLimit()>1048576)throw new IllegalArgumentException("资源限制无效");
        Set<String> params=new HashSet<>();for(var p:spec.getParameters())if(!params.add(p.getName()))throw new IllegalArgumentException("参数重名");
        for(var c:spec.getJudgeCases()){var args=json.readTree(c.getInput());Set<String> names=new HashSet<>();args.fieldNames().forEachRemaining(names::add);if(!args.isObject()||!names.equals(params))throw new IllegalArgumentException("核心模式输入必须按参数名组织");json.readTree(c.getOutput());
            for(var p:spec.getParameters())if(com.nemo.OJbackendmodel.model.dto.question.CustomStructureContract.isNode(p.getType()))com.nemo.OJbackendmodel.model.dto.question.CustomStructureContract.graph(com.google.gson.JsonParser.parseString(args.get(p.getName()).toString()),com.nemo.OJbackendmodel.model.dto.question.CustomStructureContract.definition(spec,p.getType()));
        }
        for(var c:cases)if(c.getInput()==null||(!"CANONICAL_LINES_V1".equals(d.path("inputProtocol").asText())&&(c.getInput().stripLeading().startsWith("{")||c.getInput().stripLeading().startsWith("["))))throw new IllegalArgumentException("ACM 输入需采用普通文本协议");
        Map<String,String> refs=json.convertValue(d.path("referenceSolutions"),new com.fasterxml.jackson.core.type.TypeReference<>(){});
        boolean canonical=d.has("generation");
        var keys=canonical?Set.of("java:FUNCTION","python:FUNCTION","cpp:FUNCTION"):Set.of("java:ACM","java:FUNCTION","python:ACM","python:FUNCTION","cpp:ACM","cpp:FUNCTION");
        if(refs==null||!refs.keySet().equals(keys)||refs.values().stream().anyMatch(code->code==null||code.isBlank()||code.length()>50000))throw new IllegalArgumentException("参考实现不完整或过大");
        var req=new PrivateProblemValidationRequest();req.setQuestion(q);req.setReferenceSolutions(refs);
        if(canonical){
            String protocol=d.path("generation").path("protocol").asText(),oracle=d.path("generation").path("oracleSolution").asText();
            if(!Set.of("CANONICAL_LINES_V1","CANONICAL_PYTHON_PREFLIGHT_V1").contains(protocol)||oracle.isBlank()||oracle.length()>24000)throw new IllegalArgumentException("独立答案校验规格无效");
            if(!"Solution".equals(spec.getClassName())||spec.getMethodName()==null||!spec.getMethodName().matches("[A-Za-z_][A-Za-z0-9_]*")||!Integer.valueOf(1).equals(spec.getHarnessVersion())||!"return".equals(spec.getResultSource())||spec.getComparator()==null||!"EXACT".equals(spec.getComparator().getType()))throw new IllegalArgumentException("独立答案校验签名无效");
            req.setGenerationProtocol(protocol);req.setOracleSolution(oracle);
            if(d.path("generation").has("templateId")){
                req.setTemplateId(d.path("generation").path("templateId").asText());
                req.setTemplateVersion(d.path("generation").path("templateVersion").asText());
                if(!Set.of("trusted-v1","trusted-v2").contains(req.getTemplateVersion()))throw new IllegalArgumentException("未知可信模板版本");
            }
        }
        return req;
    }
    private String safe(String message){return message==null?"候选结构无效":message.substring(0,Math.min(message.length(),1200));}
    @PreDestroy public void close(){executor.shutdownNow();renewer.shutdownNow();}
}
