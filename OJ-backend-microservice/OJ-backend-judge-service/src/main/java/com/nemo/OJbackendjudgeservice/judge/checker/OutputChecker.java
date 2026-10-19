package com.nemo.OJbackendjudgeservice.judge.checker;

import java.util.Map;

/**
 * 输出比较器接口
 */
public interface OutputChecker {

    /**
     * 比较期望输出和实际输出
     * @param expected 期望值
     * @param actual   实际值
     * @param config   比较器配置参数（如 FLOAT 的 epsilon）
     * @return 比较结果
     */
    CheckResult check(String expected, String actual, Map<String, Object> config);

    /** 比较器类型标识 */
    String getType();
}
