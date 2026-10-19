package com.nemo.OJbackendjudgeservice.judge.checker;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 多重集合比较器：不关心顺序，但保留重复次数。
 * [1,2,2] 匹配 [2,1,2]，但不匹配 [1,2] 或 [1,1,2]。
 */
@Component
public class MultisetChecker implements OutputChecker {

    private static final Gson gson = new Gson();

    @Override
    public String getType() {
        return "MULTISET";
    }

    @Override
    public CheckResult check(String expected, String actual, Map<String, Object> config) {
        try {
            JsonArray expArr = gson.fromJson(expected, JsonArray.class);
            JsonArray actArr = gson.fromJson(actual, JsonArray.class);

            if (expArr == null || actArr == null) {
                return CheckResult.fail("Not valid JSON arrays");
            }

            Map<String, Integer> freq = new HashMap<>();
            for (int i = 0; i < expArr.size(); i++) {
                String key = expArr.get(i).toString();
                freq.put(key, freq.getOrDefault(key, 0) + 1);
            }
            for (int i = 0; i < actArr.size(); i++) {
                String key = actArr.get(i).toString();
                int count = freq.getOrDefault(key, 0);
                if (count == 0) return CheckResult.fail("Extra element: " + key);
                if (count == 1) freq.remove(key);
                else freq.put(key, count - 1);
            }
            if (!freq.isEmpty()) {
                return CheckResult.fail("Missing elements: " + freq.keySet());
            }
            return CheckResult.pass();
        } catch (Exception e) {
            return CheckResult.fail("Comparison error: " + e.getMessage());
        }
    }
}
