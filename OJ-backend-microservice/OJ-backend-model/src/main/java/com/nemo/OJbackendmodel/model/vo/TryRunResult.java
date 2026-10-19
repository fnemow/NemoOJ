package com.nemo.OJbackendmodel.model.vo;

import lombok.Data;

@Data
public class TryRunResult {
    private String verdict;
    private String output;
    private String diagnostic;
    private Long executedTime;
    private Long executedMemory;
    private Integer exitCode;
}
