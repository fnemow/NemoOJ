package com.nemo.OJbackendjudgeservice.judge.checker;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 无序列表比较器：不关心元素顺序，每个期望元素都能在结果中找到匹配即可。
 */
@Component
public class UnorderedListChecker implements OutputChecker {

    private static final Gson gson = new Gson();

    @Override
    public String getType() {
        return "UNORDERED_LIST";
    }

    @Override
    public CheckResult check(String expected, String actual, Map<String, Object> config) {
        try {
            JsonArray expArr = gson.fromJson(expected, JsonArray.class);
            JsonArray actArr = gson.fromJson(actual, JsonArray.class);

            if (expArr == null || actArr == null) {
                return CheckResult.fail("Not valid JSON arrays");
            }
            if (expArr.size() != actArr.size()) {
                return CheckResult.fail("Array size mismatch: " + expArr.size() + " vs " + actArr.size());
            }

            List<JsonElement> remaining = new ArrayList<>();
            for (int i = 0; i < actArr.size(); i++) remaining.add(actArr.get(i));

            for (int i = 0; i < expArr.size(); i++) {
                JsonElement expElem = expArr.get(i);
                boolean found = false;
                for (int j = 0; j < remaining.size(); j++) {
                    if (jsonEquals(expElem, remaining.get(j))) {
                        remaining.remove(j);
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return CheckResult.fail("Element not found in actual: " + expElem);
                }
            }
            return CheckResult.pass();
        } catch (Exception e) {
            return CheckResult.fail("Comparison error: " + e.getMessage());
        }
    }

    private boolean jsonEquals(JsonElement a, JsonElement b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.toString().equals(b.toString());
    }
}
