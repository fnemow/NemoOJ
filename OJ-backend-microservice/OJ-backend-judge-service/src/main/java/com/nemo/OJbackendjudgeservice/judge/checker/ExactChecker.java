package com.nemo.OJbackendjudgeservice.judge.checker;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 结构化精确比较器（核心代码模式默认）：
 * 将 expected 和 actual 解析为 JSON 后深度比较。
 */
@Component
public class ExactChecker implements OutputChecker {

    private static final Gson gson = new Gson();

    @Override
    public String getType() {
        return "EXACT";
    }

    @Override
    public CheckResult check(String expected, String actual, Map<String, Object> config) {
        if (expected == null && actual == null) return CheckResult.pass();
        if (expected == null || actual == null) return CheckResult.fail("One value is null");

        try {
            JsonElement expJson = gson.fromJson(expected, JsonElement.class);
            JsonElement actJson = gson.fromJson(actual, JsonElement.class);
            if (deepEquals(expJson, actJson)) {
                return CheckResult.pass();
            }
            return CheckResult.fail("JSON mismatch: expected=" + expected + ", actual=" + actual);
        } catch (Exception e) {
            // 非 JSON → 直接字符串比较
            if (expected.equals(actual)) return CheckResult.pass();
            return CheckResult.fail("String mismatch");
        }
    }

    private boolean deepEquals(JsonElement a, JsonElement b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.isJsonNull() || b.isJsonNull()) return a.isJsonNull() && b.isJsonNull();
        if (a.isJsonPrimitive() && b.isJsonPrimitive()) {
            var x=a.getAsJsonPrimitive(); var y=b.getAsJsonPrimitive();
            if(x.isNumber() && y.isNumber()) return x.getAsBigDecimal().compareTo(y.getAsBigDecimal())==0;
            return x.equals(y);
        }
        if(a.isJsonArray() && b.isJsonArray()) {
            if(a.getAsJsonArray().size()!=b.getAsJsonArray().size()) return false;
            for(int i=0;i<a.getAsJsonArray().size();i++) if(!deepEquals(a.getAsJsonArray().get(i),b.getAsJsonArray().get(i))) return false;
            return true;
        }
        if(a.isJsonObject() && b.isJsonObject()) {
            if(!a.getAsJsonObject().keySet().equals(b.getAsJsonObject().keySet())) return false;
            for(String key:a.getAsJsonObject().keySet()) if(!deepEquals(a.getAsJsonObject().get(key),b.getAsJsonObject().get(key))) return false;
            return true;
        }
        return false;
    }
}
