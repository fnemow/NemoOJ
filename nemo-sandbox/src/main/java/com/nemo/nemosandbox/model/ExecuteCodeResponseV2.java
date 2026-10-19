package com.nemo.nemosandbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteCodeResponseV2 {
    private List<CaseExecutionResult> cases;
    private String compileMessage;
    private String systemMessage;
    private ResourceLimit effectiveLimits;
    private String policyVersion;
    private Integer overallStatus;
}
