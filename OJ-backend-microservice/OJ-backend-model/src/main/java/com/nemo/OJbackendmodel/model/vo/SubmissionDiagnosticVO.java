package com.nemo.OJbackendmodel.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubmissionDiagnosticVO implements Serializable {
    private String phase;
    private String message;
    private boolean truncated;
}
