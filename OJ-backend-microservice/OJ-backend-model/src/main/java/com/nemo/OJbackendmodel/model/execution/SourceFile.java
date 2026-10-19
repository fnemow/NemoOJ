package com.nemo.OJbackendmodel.model.execution;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 源码文件
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SourceFile {

    /** 文件名（如 Main.java, Solution.java） */
    private String fileName;

    /** 文件内容 */
    private String content;
}
