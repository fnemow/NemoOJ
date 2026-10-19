package com.nemo.OJbackendmodel.model.dto.question;

import com.nemo.OJbackendmodel.model.entity.Question;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/** Internal sandbox validation input; must never be exposed through public API. */
@Data
public class PrivateProblemValidationRequest implements Serializable {
    private Question question;
    /** Keys use language:mode, such as java:ACM or cpp:FUNCTION. */
    private Map<String, String> referenceSolutions;
    /** Optional canonical generation path: expected answers are derived in the sandbox. */
    private String generationProtocol;
    private String oracleSolution;
    private String templateId;
    private String templateVersion;
    /** Server-created durable validation job ID; scopes execution checkpoints. */
    private String validationId;
    private String validationLeaseToken;
    private static final long serialVersionUID = 1L;
}
