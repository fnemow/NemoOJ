package com.nemo.OJbackendmodel.model.codesandbox;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteCodeRequest {
    /**
     * 输入用例
     */
    private List<String> inputList;

    /**
     * 代码
     */
    private String code;

    /**
     * 编程语言
     */
    private String language;

    /**
     * 时间限制 (ms)，可选；null 时沙箱使用默认值
     */
    private Long timeLimitMs;

    /**
     * 内存限制 (KB)，可选；null 时沙箱使用默认值
     */
    private Long memoryLimitKB;
}
