package com.nemo.OJbackendmodel.model.dto.question;

import com.nemo.OJbackendmodel.model.entity.Question;
import lombok.Data;

@Data
public class TryRunExecutionRequest {
    private Question question;
    private String language;
    private String executionMode;
    private String runtimeId;
    private String code;
    private String input;
}
