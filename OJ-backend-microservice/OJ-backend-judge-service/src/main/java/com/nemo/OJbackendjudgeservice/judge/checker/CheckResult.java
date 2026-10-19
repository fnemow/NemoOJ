package com.nemo.OJbackendjudgeservice.judge.checker;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CheckResult {
    private boolean passed;
    private String message;

    public static CheckResult pass() {
        return new CheckResult(true, null);
    }

    public static CheckResult fail(String message) {
        return new CheckResult(false, message);
    }
}
