package com.nemo.OJbackendmodel.model.dto.question;

import lombok.Data;

@Data
public class AdminProblemQueryRequest {
    private long current = 1;
    private long pageSize = 20;
    private Long problemNo;
    private String title;
    private String status;
}
