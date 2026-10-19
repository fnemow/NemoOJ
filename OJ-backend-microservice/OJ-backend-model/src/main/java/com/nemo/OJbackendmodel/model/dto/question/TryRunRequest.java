package com.nemo.OJbackendmodel.model.dto.question;

import lombok.Data;

/** A single public example or caller-provided input, never an official submission. */
@Data
public class TryRunRequest {
    private Long questionId;
    private Integer problemVersion;
    private String language;
    private String executionMode;
    private String code;
    private String input;
    private Integer sampleIndex;
}
