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
public class ExecutionPlan {
    private String runtimeId;
    private String language;
    private ExecutionMode mode;
    private List<SourceFile> sourceFiles;
    private List<ExecutionCaseInput> cases;
    private ResourceLimit limits;
    private String entryPoint;
    private String harnessVersion;
}
