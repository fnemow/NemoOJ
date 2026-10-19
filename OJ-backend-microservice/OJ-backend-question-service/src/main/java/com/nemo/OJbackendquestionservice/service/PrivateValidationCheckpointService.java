package com.nemo.OJbackendquestionservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nemo.OJbackendcommon.common.ErrorCode;
import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendmodel.model.dto.question.PrivateValidationCheckpoint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class PrivateValidationCheckpointService {
    @org.springframework.beans.factory.annotation.Value("${AI_PRIVATE_REPAIR_CHECKPOINT_REUSE_ENABLED:false}")
    private boolean repairReuseEnabled;
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    public PrivateValidationCheckpointService(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    private void keys(String job,String hash){
        if(job==null||!job.matches("[a-fA-F0-9-]{36}")||hash==null||!hash.matches("[a-f0-9]{64}"))throw new BusinessException(ErrorCode.PARAMS_ERROR);
    }
    public PrivateValidationCheckpoint load(String job,String hash){
        keys(job,hash);
        var current=jdbc.queryForList("SELECT v.taskId,v.revision,v.draftJson FROM private_validation_job v JOIN ai_task t ON t.id=v.taskId WHERE v.id=? AND v.status='RUNNING' AND v.leaseExpireAt>=NOW() AND t.status='RUNNING' AND t.startedAt>DATE_SUB(NOW(),INTERVAL 900 SECOND)",job);
        if(current.size()!=1)return null;
        var rows=jdbc.query("SELECT evidenceJson FROM private_validation_checkpoint WHERE validationId=? AND executionHash=? AND expiresAt>NOW()",(rs,n)->rs.getString(1),job,hash);
        if(rows.isEmpty()){
            // Only a live full-validation job may borrow immutable Python evidence from
            // a successful preflight of the SAME task and identical frozen contract/code.
            try{
                var draft=json.readTree(String.valueOf(current.get(0).get("draftJson")));
                if(!"CANONICAL_LINES_V1".equals(draft.path("generation").path("protocol").asText()))return null;
                String binding=PrivateValidationJobs.pythonBinding(draft);
                rows=jdbc.query("SELECT c.evidenceJson FROM private_validation_checkpoint c JOIN private_validation_job p ON p.id=c.validationId WHERE p.taskId=? AND p.revision<? AND p.status='SUCCEEDED' AND JSON_EXTRACT(p.resultJson,'$.pythonPreflightValidated')=true AND JSON_UNQUOTE(JSON_EXTRACT(p.resultJson,'$.preflightBinding'))=? AND c.executionHash=? AND c.expiresAt>NOW() ORDER BY p.revision DESC LIMIT 1",(rs,n)->rs.getString(1),current.get(0).get("taskId"),current.get(0).get("revision"),binding,hash);
                if(rows.isEmpty() && repairReuseEnabled){
                    // SQL binds task and exact execution hash; metadata equality freezes
                    // the problem contract. Changed source/harness/inputs/limits/image
                    // cannot match the execution hash computed by judge.
                    var prior=jdbc.queryForList("SELECT c.evidenceJson,p.draftJson FROM private_validation_checkpoint c JOIN private_validation_job p ON p.id=c.validationId WHERE p.taskId=? AND p.revision<? AND p.status='FAILED' AND c.executionHash=? AND c.expiresAt>NOW() ORDER BY p.revision DESC LIMIT 3",current.get(0).get("taskId"),current.get(0).get("revision"),hash);
                    for(var evidence:prior){
                        var previous=json.readTree(String.valueOf(evidence.get("draftJson")));
                        if(repairBinding(draft).equals(repairBinding(previous))){rows=java.util.List.of(String.valueOf(evidence.get("evidenceJson")));break;}
                    }
                }
                if(rows.isEmpty())return null;
            }catch(com.fasterxml.jackson.core.JsonProcessingException invalid){return null;}
        }
        try{
            var evidence=json.readValue(rows.get(0),PrivateValidationCheckpoint.class);
            if(!hash.equals(evidence.getExecutionHash()) || evidence.getOutputs()==null || evidence.getOutputs().size()<20 || evidence.getOutputs().size()>100)return null;
            evidence.setValidationId(job);evidence.setLeaseToken(null);return evidence;
        }catch(Exception corrupt){throw new IllegalStateException("Invalid validation checkpoint",corrupt);}
    }
    static String repairBinding(com.fasterxml.jackson.databind.JsonNode draft){
        if(!"CANONICAL_LINES_V1".equals(draft.path("generation").path("protocol").asText()))return "invalid:"+PrivateValidationJobs.hash(draft);
        var frozen=(com.fasterxml.jackson.databind.node.ObjectNode)draft.deepCopy();
        frozen.remove("referenceSolutions");
        ((com.fasterxml.jackson.databind.node.ObjectNode)frozen.path("generation")).remove("oracleSolution");
        return PrivateValidationJobs.hash(frozen);
    }
    public void save(PrivateValidationCheckpoint evidence){
        if(evidence==null)throw new BusinessException(ErrorCode.PARAMS_ERROR);
        keys(evidence.getValidationId(),evidence.getExecutionHash());
        if(evidence.getLeaseToken()==null||evidence.getOutputs()==null||evidence.getOutputs().size()<20||evidence.getOutputs().size()>100
                ||evidence.getOutputs().stream().anyMatch(output->output==null||output.length()>1048576)
                ||evidence.getTime()==null||evidence.getTime()<0||evidence.getMemory()==null||evidence.getMemory()<0)throw new BusinessException(ErrorCode.PARAMS_ERROR);
        try{
            com.fasterxml.jackson.databind.node.ObjectNode stored=json.valueToTree(evidence);stored.remove("leaseToken");
            String encoded=json.writeValueAsString(stored);
            if(encoded.length()>1000000)throw new BusinessException(ErrorCode.PARAMS_ERROR);
            Integer owned=jdbc.queryForObject("SELECT COUNT(*) FROM private_validation_job v JOIN ai_task t ON t.id=v.taskId WHERE v.id=? AND v.status='RUNNING' AND v.leaseToken=? AND v.leaseExpireAt>=NOW() AND t.status='RUNNING' AND t.startedAt>DATE_SUB(NOW(),INTERVAL 900 SECOND)",Integer.class,evidence.getValidationId(),evidence.getLeaseToken());
            if(owned==null||owned!=1)throw new BusinessException(ErrorCode.OPERATION_ERROR,"验证租约已变更","AI_LEASE_LOST");
            // Immutable insert plus the current lease condition rejects late worker writes.
            int saved=jdbc.update("INSERT INTO private_validation_checkpoint(validationId,executionHash,evidenceJson,expiresAt) SELECT ?,?,?,DATE_ADD(NOW(),INTERVAL 20 MINUTE) FROM private_validation_job v JOIN ai_task t ON t.id=v.taskId WHERE v.id=? AND v.status='RUNNING' AND v.leaseToken=? AND v.leaseExpireAt>=NOW() AND t.status='RUNNING' AND t.startedAt>DATE_SUB(NOW(),INTERVAL 900 SECOND) ON DUPLICATE KEY UPDATE executionHash=VALUES(executionHash)",evidence.getValidationId(),evidence.getExecutionHash(),encoded,evidence.getValidationId(),evidence.getLeaseToken());
            if(saved==0){
                // Distinguish an immutable duplicate from lease loss during the write.
                Integer current=jdbc.queryForObject("SELECT COUNT(*) FROM private_validation_job v JOIN ai_task t ON t.id=v.taskId WHERE v.id=? AND v.status='RUNNING' AND v.leaseToken=? AND v.leaseExpireAt>=NOW() AND t.status='RUNNING' AND t.startedAt>DATE_SUB(NOW(),INTERVAL 900 SECOND)",Integer.class,evidence.getValidationId(),evidence.getLeaseToken());
                if(current==null||current!=1||load(evidence.getValidationId(),evidence.getExecutionHash())==null)throw new BusinessException(ErrorCode.OPERATION_ERROR,"验证租约已变更","AI_LEASE_LOST");
            }
        }catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new IllegalStateException(invalid);}
    }
    @Scheduled(fixedDelay=60000)
    public void expire(){jdbc.update("DELETE FROM private_validation_checkpoint WHERE expiresAt<NOW() LIMIT 500");}
}
