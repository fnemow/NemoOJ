package com.nemo.OJbackendjudgeservice.judge;

import com.nemo.OJbackendmodel.model.dto.question.PrivateValidationCheckpoint;
import com.nemo.OJbackendmodel.model.execution.ResourceLimit;
import com.nemo.OJserviceclient.QuestionFeignClient;
import org.springframework.stereotype.Component;
import java.util.List;

/** Job-scoped immutable successful execution evidence, durable across process restarts.
 * Expected answers are always compared again by the caller. Never reuse a container.
 */
@Component
public class ValidationCheckpointStore {
    public record Evidence(List<String> outputs,long time,long memory,ResourceLimit limits) {}
    private final QuestionFeignClient question;
    public ValidationCheckpointStore(QuestionFeignClient question){this.question=question;}
    public Evidence load(String job,String hash){
        if(job==null)return null;
        var stored=question.getValidationCheckpoint(job,hash);
        return stored==null?null:new Evidence(stored.getOutputs(),stored.getTime(),stored.getMemory(),stored.getLimits());
    }
    public void save(String job,String hash,Evidence evidence,String token){
        if(job==null)return;
        var stored=new PrivateValidationCheckpoint();stored.setValidationId(job);stored.setExecutionHash(hash);stored.setLeaseToken(token);
        stored.setOutputs(evidence.outputs());stored.setTime(evidence.time());stored.setMemory(evidence.memory());stored.setLimits(evidence.limits());
        if(!question.saveValidationCheckpoint(stored))throw new IllegalStateException("Validation checkpoint was not acknowledged");
    }
}
