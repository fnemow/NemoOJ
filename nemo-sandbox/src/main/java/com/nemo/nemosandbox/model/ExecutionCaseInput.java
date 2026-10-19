package com.nemo.nemosandbox.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionCaseInput {
    private Integer caseIndex;
    private String stdin;
}
