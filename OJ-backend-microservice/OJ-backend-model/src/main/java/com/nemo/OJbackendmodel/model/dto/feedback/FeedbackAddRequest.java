package com.nemo.OJbackendmodel.model.dto.feedback;

import lombok.Data;

@Data
public class FeedbackAddRequest {
    private String type;
    private Long problemNo;
    private String title;
    private String content;
}
