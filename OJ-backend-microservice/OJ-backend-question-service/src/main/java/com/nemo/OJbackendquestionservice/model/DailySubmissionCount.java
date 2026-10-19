package com.nemo.OJbackendquestionservice.model;

import lombok.Data;

@Data
public class DailySubmissionCount {
    private String date;
    private int submissions;
    private int problems;
}
