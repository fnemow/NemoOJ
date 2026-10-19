package com.nemo.OJbackendjudgeservice.judge.checker;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 浮点比较器：支持绝对误差和相对误差。
 * 配置参数：
 * - "epsilon" (Double): 绝对误差上限，默认 1e-6
 * - "relativeEpsilon" (Double): 相对误差上限，默认 1e-6
 */
@Component
public class FloatChecker implements OutputChecker {

    private static final double DEFAULT_EPSILON = 1e-6;

    @Override
    public String getType() {
        return "FLOAT";
    }

    @Override
    public CheckResult check(String expected, String actual, Map<String, Object> config) {
        try {
            double exp = Double.parseDouble(expected.trim());
            double act = Double.parseDouble(actual.trim());

            double absEpsilon = getConfigDouble(config, "epsilon", DEFAULT_EPSILON);
            double relEpsilon = getConfigDouble(config, "relativeEpsilon", DEFAULT_EPSILON);

            double diff = Math.abs(act - exp);
            if (diff <= absEpsilon) return CheckResult.pass();
            if (exp != 0 && diff / Math.abs(exp) <= relEpsilon) return CheckResult.pass();

            return CheckResult.fail(String.format(
                    "Float mismatch: expected=%s, actual=%s, diff=%e", expected, actual, diff));
        } catch (NumberFormatException e) {
            return CheckResult.fail("Not a valid number: " + e.getMessage());
        }
    }

    private double getConfigDouble(Map<String, Object> config, String key, double defaultValue) {
        if (config == null) return defaultValue;
        Object val = config.get(key);
        if (val instanceof Number) return ((Number) val).doubleValue();
        return defaultValue;
    }
}
