package com.nemo.OJbackendquestionservice.controller;

import com.nemo.OJbackendcommon.common.*;
import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendmodel.model.entity.User;
import com.nemo.OJserviceclient.UserFeignClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Business aggregate only: billing and leases belong to the three existing child tasks. */
@RestController
@RequestMapping("/ai/private-problem-batches")
public class PrivateProblemBatchController {
    private final JdbcTemplate jdbc;
    private final UserFeignClient users;
    private final PrivateProblemController single;
    @Value("${ai.training.batches.enabled:true}") private boolean enabled;
    public PrivateProblemBatchController(JdbcTemplate jdbc,UserFeignClient users,PrivateProblemController single) {this.jdbc=jdbc;this.users=users;this.single=single;}

    @PostMapping
    @Transactional(rollbackFor=Exception.class)
    public BaseResponse<Map<String,Object>> create(@RequestBody Map<String,Object> body,HttpServletRequest request) {
        long owner=member(request); if(!enabled)throw new BusinessException(ErrorCode.OPERATION_ERROR,"批量生成暂未开放");
        String key=uuid(body.get("idempotencyKey")); String goal=String.valueOf(body.getOrDefault("goal","")).trim();
        if(goal.isBlank()||goal.length()>1000)throw new BusinessException(ErrorCode.PARAMS_ERROR,"请填写不超过 1000 字的训练目标");
        jdbc.queryForObject("SELECT id FROM `user` WHERE id=? AND isDelete=0 FOR UPDATE",Long.class,owner);
        List<String> old=jdbc.query("SELECT id FROM private_generation_batch WHERE ownerUserId=? AND idempotencyKey=?",(rs,row)->rs.getString(1),owner,key);
        if(!old.isEmpty()){var existing=read(old.get(0),owner);if(!goal.equals(existing.get("goal")))throw new BusinessException(ErrorCode.PARAMS_ERROR,"幂等键不能用于不同训练目标");return ResultUtils.success(existing);}
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO private_generation_batch(id,ownerUserId,goal,idempotencyKey) VALUES (?,?,?,?)",id,owner,goal,key);
        for(String difficulty:List.of("EASY","MEDIUM","HARD")) {
            // All reservations share this transaction. A failure in any slot rolls back all three.
            var child=single.create(Map.of("goal",goal,"difficulty",difficulty,"idempotencyKey",UUID.randomUUID().toString()),request).getData();
            jdbc.update("UPDATE ai_task SET requestJson=JSON_SET(requestJson,'$.batchId',?,'$.batchDifficulty',?) WHERE id=?",id,difficulty,child.get("taskId"));
            jdbc.update("INSERT INTO private_generation_batch_item(batchId,difficulty,privateProblemId,taskId) VALUES (?,?,?,?)",id,difficulty,child.get("privateProblemId"),child.get("taskId"));
        }
        return ResultUtils.success(read(id,owner));
    }
    @GetMapping
    public BaseResponse<Map<String,Object>> list(@RequestParam(defaultValue="1") int current,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="") String difficulty,@RequestParam(defaultValue="") String status,HttpServletRequest request) {
        long owner=member(request);if(current<1||current>100000||pageSize<1||pageSize>20)throw new BusinessException(ErrorCode.PARAMS_ERROR);
        if(!difficulty.isEmpty()&&!Set.of("EASY","MEDIUM","HARD").contains(difficulty)||!status.isEmpty()&&!Set.of("READY","GENERATING","REJECTED").contains(status))throw new BusinessException(ErrorCode.PARAMS_ERROR);
        String where=" WHERE b.ownerUserId=? AND EXISTS (SELECT 1 FROM private_generation_batch_item i JOIN private_problem p ON p.id=i.privateProblemId WHERE i.batchId=b.id AND (?='' OR i.difficulty=?) AND (?='' OR p.status=?))";
        List<String> ids=jdbc.query("SELECT b.id FROM private_generation_batch b"+where+" ORDER BY b.createTime DESC,b.id DESC LIMIT ? OFFSET ?",(rs,row)->rs.getString(1),owner,difficulty,difficulty,status,status,pageSize,(current-1)*pageSize);
        return ResultUtils.success(Map.of("current",current,"pageSize",pageSize,"total",Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM private_generation_batch b"+where,Long.class,owner,difficulty,difficulty,status,status)),"records",ids.stream().map(id->read(id,owner)).toList()));
    }
    @GetMapping("/{id}")
    public BaseResponse<Map<String,Object>> detail(@PathVariable String id,HttpServletRequest request){return ResultUtils.success(read(id,member(request)));}

    @PostMapping("/{id}/items/{difficulty}/retry")
    @Transactional(rollbackFor=Exception.class)
    public BaseResponse<Map<String,Object>> retry(@PathVariable String id,@PathVariable String difficulty,@RequestBody Map<String,Object> body,HttpServletRequest request) {
        long owner=member(request);String key=uuid(body.get("idempotencyKey"));
        if(!enabled||!List.of("EASY","MEDIUM","HARD").contains(difficulty))throw new BusinessException(ErrorCode.PARAMS_ERROR);
        jdbc.queryForObject("SELECT id FROM `user` WHERE id=? AND isDelete=0 FOR UPDATE",Long.class,owner);
        var batch=read(id,owner);
        var slots=jdbc.queryForList("SELECT i.retryKey,t.status FROM private_generation_batch_item i JOIN ai_task t ON t.id=i.taskId WHERE i.batchId=? AND i.difficulty=?",id,difficulty);
        if(slots.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        if(key.equals(slots.get(0).get("retryKey")))return ResultUtils.success(batch);
        long expected;try{expected=Long.parseLong(String.valueOf(body.get("expectedVersion")));}catch(Exception e){throw new BusinessException(ErrorCode.PARAMS_ERROR);}
        if(expected!=((Number)batch.get("rowVersion")).longValue())throw new BusinessException(ErrorCode.OPERATION_ERROR,"批次已变化，请刷新","EDIT_CONFLICT");
        if(!List.of("FAILED","CANCELLED").contains(slots.get(0).get("status")))throw new BusinessException(ErrorCode.OPERATION_ERROR,"只能重试已结束的失败题目");
        String childKey=UUID.nameUUIDFromBytes((id+":"+difficulty+":"+key).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        var child=single.create(Map.of("goal",batch.get("goal"),"difficulty",difficulty,"idempotencyKey",childKey),request).getData();
        jdbc.update("UPDATE ai_task SET requestJson=JSON_SET(requestJson,'$.batchId',?,'$.batchDifficulty',?,'$.previousTaskId',?) WHERE id=?",id,difficulty,((List<?>)batch.get("items")).stream().map(v->(Map<?,?>)v).filter(v->difficulty.equals(v.get("difficulty"))).findFirst().orElseThrow().get("taskId"),child.get("taskId"));
        jdbc.update("UPDATE private_generation_batch_item SET privateProblemId=?,taskId=?,retryKey=?,revision=revision+1 WHERE batchId=? AND difficulty=?",child.get("privateProblemId"),child.get("taskId"),key,id,difficulty);
        jdbc.update("UPDATE private_generation_batch SET rowVersion=rowVersion+1 WHERE id=? AND rowVersion=?",id,expected);
        return ResultUtils.success(read(id,owner));
    }
    private Map<String,Object> read(String id,long owner) {
        var rows=jdbc.query("SELECT id,goal,rowVersion,createTime FROM private_generation_batch WHERE id=? AND ownerUserId=?",(rs,row)->{Map<String,Object> b=new LinkedHashMap<>();b.put("id",rs.getString(1));b.put("goal",rs.getString(2));b.put("rowVersion",rs.getLong(3));b.put("createTime",rs.getTimestamp(4));return b;},id,owner);
        if(rows.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        var items=jdbc.query("SELECT i.difficulty,i.privateProblemId,i.taskId,i.revision,t.status,t.stage,p.title,p.status,p.rejectReason,p.currentVersionId,t.generationRepairCount,t.errorCode,t.errorMessage,COALESCE(JSON_LENGTH(JSON_EXTRACT(IF(JSON_VALID(v.resultJson),v.resultJson,'{}'),'$.combinations')),0) "
            +"FROM private_generation_batch_item i JOIN ai_task t ON t.id=i.taskId JOIN private_problem p ON p.id=i.privateProblemId LEFT JOIN private_validation_job v ON v.taskId=t.id AND v.revision=(SELECT MAX(revision) FROM private_validation_job WHERE taskId=t.id) WHERE i.batchId=? ORDER BY FIELD(i.difficulty,'EASY','MEDIUM','HARD')",(rs,row)-> {
                Map<String,Object> v=new LinkedHashMap<>();v.put("difficulty",rs.getString(1));v.put("privateProblemId",rs.getString(2));v.put("taskId",rs.getString(3));v.put("attempt",rs.getInt(4));v.put("status",rs.getString(5));v.put("stage",rs.getString(6));v.put("title",rs.getString(7));v.put("problemStatus",rs.getString(8));
                // Public diagnostics never contain compiler output, hidden cases, or source paths.
                v.put("message","GOAL_UNSUPPORTED".equals(rs.getString(12))?rs.getString(13):"REJECTED".equals(rs.getString(8))?"生成或验证未通过，可以重试这道题":null);v.put("versionId",rs.getString(10));v.put("repairCount",rs.getInt(11));v.put("validatedCombinations",rs.getInt(14));
                v.put("completed",Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM question_submit WHERE userId=? AND privateProblemVersionId=? AND problemSource='PRIVATE' AND status=2 AND isDelete=0",Integer.class,owner,rs.getString(10)))>0);return v;},id);
        var b=rows.get(0);b.put("items",items);b.put("status",aggregate(items.stream().map(v->String.valueOf(v.get("status"))).toList()));return b;
    }
    public static String aggregate(List<String> statuses) {
        if(statuses.isEmpty())return "FAILED";
        if(statuses.stream().allMatch("SUCCEEDED"::equals))return "SUCCEEDED";
        if(statuses.stream().anyMatch("RUNNING"::equals))return "RUNNING";
        if(statuses.stream().anyMatch("QUEUED"::equals))return "QUEUED";
        return statuses.contains("SUCCEEDED")?"PARTIAL_FAILED":statuses.stream().allMatch("CANCELLED"::equals)?"CANCELLED":"FAILED";
    }
    private long member(HttpServletRequest request) {User u=users.getLoginUser(request);if(u==null||u.getId()==null||!"user".equals(u.getUserRole()))throw new BusinessException(ErrorCode.NO_AUTH_ERROR);return u.getId();}
    private String uuid(Object raw){try{return UUID.fromString(String.valueOf(raw)).toString();}catch(Exception e){throw new BusinessException(ErrorCode.PARAMS_ERROR,"幂等键必须为 UUID");}}
}
