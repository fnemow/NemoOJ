package com.nemo.OJbackendmodel.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RuntimeSummaryVO implements Serializable {
    private String runtimeId;
    private String displayName;
}
