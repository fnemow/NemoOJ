package com.nemo.OJbackendmodel.model.dto.question;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 比较器配置
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ComparatorSpec implements Serializable {

    /** 比较器类型：EXACT, TEXT, FLOAT, UNORDERED_LIST, MULTISET */
    private String type;

    /** 浮点比较的误差范围（仅 FLOAT 类型有效） */
    private Double epsilon;

    private static final long serialVersionUID = 1L;
}
