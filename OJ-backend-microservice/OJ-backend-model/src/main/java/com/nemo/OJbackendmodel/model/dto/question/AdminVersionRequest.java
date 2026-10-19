package com.nemo.OJbackendmodel.model.dto.question;

import lombok.Data;
import java.io.Serializable;

@Data
public class AdminVersionRequest implements Serializable {
    private Long id;
    private Long expectedRowVersion;
}
