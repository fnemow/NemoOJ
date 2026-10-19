package com.nemo.OJbackendmodel.model.dto.feedback;

import lombok.Data;

@Data
public class FeedbackHandleRequest {
    private Long id;
    private String status;
    private String adminReply;
    private Long expectedRowVersion;
}
