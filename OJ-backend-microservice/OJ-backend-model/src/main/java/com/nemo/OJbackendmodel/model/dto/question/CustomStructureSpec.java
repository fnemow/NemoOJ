package com.nemo.OJbackendmodel.model.dto.question;
import lombok.Data;
import java.io.Serializable;
import java.util.List;
/** Declarative node fields; ref/ref[] always refer to this same node class. */
@Data
public class CustomStructureSpec implements Serializable {
    private String name;
    private List<ParameterSpec> fields;
    private static final long serialVersionUID=1L;
}
