package com.nemo.OJbackendjudgeservice.judge.checker;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * ACM 文本比较器（默认）：
 * - 统一 \r\n → \n
 * - 逐行去行尾空格
 * - 去除末尾空行
 */
@Component
public class TextChecker implements OutputChecker {

    @Override
    public String getType() {
        return "TEXT";
    }

    @Override
    public CheckResult check(String expected, String actual, Map<String, Object> config) {
        String normExpected = normalize(expected);
        String normActual = normalize(actual);
        if (normExpected.equals(normActual)) {
            return CheckResult.pass();
        }
        return CheckResult.fail("Text mismatch");
    }

    private String normalize(String s) {
        if (s == null) return "";
        String n = s.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder sb = new StringBuilder();
        for (String line : n.split("\n", -1)) {
            sb.append(line.replaceAll("\\s+$", "")).append('\n');
        }
        return sb.toString().replaceAll("\\n+$", "");
    }
}
