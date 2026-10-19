package com.nemo.nemosandbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteCodeRequestV2 {
    private Long submissionId;
    /** Authenticated internal validation only; never set for interactive try-run. */
    private boolean backgroundValidation;
    /** Internal platform template namespace; never populated for user submissions. */
    private String trustedCompileScope;
    private ExecutionPlan executionPlan;
}
