package com.nemo.OJbackendquestionservice.model;

import lombok.Data;

/** Authoritative submission totals aggregated across every version of one public problem number. */
@Data
public class ProblemSubmissionStats {
    private Long problemNo;
    private Long submitNum;
    private Long acceptedNum;
}
