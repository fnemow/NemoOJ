package com.nemo.OJbackendmodel.model.dto.question;

import lombok.Data;

@Data
public class ProblemLifecycleRequest {
    private Long problemNo;
    private Long expectedLifecycleVersion;
    private String reason;
}
