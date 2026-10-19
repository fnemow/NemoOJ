package com.nemo.OJbackendmodel.model.dto.question;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 函数参数规格
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParameterSpec implements Serializable {

    /** 参数名（如 "head", "nums", "target"） */
    private String name;

    /** 参数类型（如 "int", "int[]", "list_node<int>", "tree_node<int>"） */
    private String type;

    private static final long serialVersionUID = 1L;
}
