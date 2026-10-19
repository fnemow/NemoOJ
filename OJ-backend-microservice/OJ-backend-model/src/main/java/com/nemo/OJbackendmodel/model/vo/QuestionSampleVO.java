package com.nemo.OJbackendmodel.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuestionSampleVO implements Serializable {
    private String input;
    private String output;
    private String explanation;
}
