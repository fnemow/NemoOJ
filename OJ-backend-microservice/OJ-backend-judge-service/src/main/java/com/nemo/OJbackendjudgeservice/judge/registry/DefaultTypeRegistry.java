package com.nemo.OJbackendjudgeservice.judge.registry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 默认类型注册表：第一版支持 int, long, double, boolean, string,
 * int[], int[][], list&lt;int&gt;, list_node&lt;int&gt;, tree_node&lt;int&gt;。
 */
@Slf4j
@Component
public class DefaultTypeRegistry implements TypeRegistry {

    private static final Set<String> SUPPORTED_TYPES = new HashSet<>(Arrays.asList(
            "int", "long", "double", "boolean", "string",
            "int[]", "long[]", "double[]", "boolean[]", "string[]",
            "int[][]", "long[][]",
            "list<int>", "list<long>", "list<double>", "list<string>",
            "list_node<int>", "list_node<long>", "list_node<double>", "list_node<string>",
            "tree_node<int>", "tree_node<long>", "tree_node<double>", "tree_node<string>",
            "void"
    ));

    @Override
    public boolean isSupported(String typeName) {
        if (typeName == null) return false;
        // 泛化支持：list_node<*> 和 tree_node<*> 模式
        if (typeName.startsWith("list_node<") || typeName.startsWith("tree_node<")
                || typeName.startsWith("list<") || typeName.endsWith("[]")) {
            return true;
        }
        return SUPPORTED_TYPES.contains(typeName);
    }

    @Override
    public Object deserialize(String typeName, JsonElement json) {
        if (json == null || json.isJsonNull()) return null;
        if ("int".equals(typeName)) return json.getAsInt();
        if ("long".equals(typeName)) return json.getAsLong();
        if ("double".equals(typeName)) return json.getAsDouble();
        if ("boolean".equals(typeName)) return json.getAsBoolean();
        if ("string".equals(typeName)) return json.getAsString();
        if ("int[]".equals(typeName)) return parseJsonToIntArray(json.getAsJsonArray());
        if ("int[][]".equals(typeName)) return parseJsonToIntMatrix(json.getAsJsonArray());
        log.warn("TypeRegistry.deserialize: 类型 {} 未实现反序列化，返回原始 JsonElement", typeName);
        return json;
    }

    @Override
    public JsonElement serialize(String typeName, Object value) {
        if (value == null) return JsonNull.INSTANCE;
        if ("int".equals(typeName) || "long".equals(typeName) || "double".equals(typeName)) {
            return new JsonPrimitive((Number) value);
        }
        if ("boolean".equals(typeName)) return new JsonPrimitive((Boolean) value);
        if ("string".equals(typeName)) return new JsonPrimitive((String) value);
        if ("int[]".equals(typeName)) return intArrayToJson((int[]) value);
        if ("int[][]".equals(typeName)) return intMatrixToJson((int[][]) value);
        log.warn("TypeRegistry.serialize: 类型 {} 未实现序列化", typeName);
        return JsonNull.INSTANCE;
    }

    private int[] parseJsonToIntArray(JsonArray arr) {
        int[] out = new int[arr.size()];
        for (int i = 0; i < arr.size(); i++) out[i] = arr.get(i).getAsInt();
        return out;
    }

    private int[][] parseJsonToIntMatrix(JsonArray arr) {
        int[][] out = new int[arr.size()][];
        for (int i = 0; i < arr.size(); i++) {
            JsonArray row = arr.get(i).getAsJsonArray();
            out[i] = parseJsonToIntArray(row);
        }
        return out;
    }

    private JsonArray intArrayToJson(int[] arr) {
        JsonArray ja = new JsonArray();
        for (int v : arr) ja.add(v);
        return ja;
    }

    private JsonArray intMatrixToJson(int[][] arr) {
        JsonArray ja = new JsonArray();
        for (int[] row : arr) ja.add(intArrayToJson(row));
        return ja;
    }
}
