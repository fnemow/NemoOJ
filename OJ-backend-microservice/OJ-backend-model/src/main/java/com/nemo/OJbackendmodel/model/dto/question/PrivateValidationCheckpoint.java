package com.nemo.OJbackendmodel.model.dto.question;

import com.nemo.OJbackendmodel.model.execution.ResourceLimit;
import lombok.Data;
import java.util.List;

/** Hidden execution evidence transported only over authenticated internal calls. */
@Data
public class PrivateValidationCheckpoint {
    private String validationId;
    private String executionHash;
    private String leaseToken;
    private List<String> outputs;
    private Long time;
    private Long memory;
    private ResourceLimit limits;
}
