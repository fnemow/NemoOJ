package com.nemo.OJbackendmodel.model.dto.feedback;

import lombok.Data;

@Data
public class FeedbackQueryRequest {
    private long current = 1;
    private long pageSize = 20;
    private String status;
    private String type;
    private Long problemNo;
    private Long userId;
}
