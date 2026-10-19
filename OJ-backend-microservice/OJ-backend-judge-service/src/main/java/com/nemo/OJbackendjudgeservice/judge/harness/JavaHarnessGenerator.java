package com.nemo.OJbackendjudgeservice.judge.harness;

import com.nemo.OJbackendmodel.model.dto.question.ParameterSpec;
import com.nemo.OJbackendmodel.model.dto.question.ProblemSpec;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 根据 ProblemSpec 生成 Runner.java 源码。
 *
 * Runner 协议：
 * 1. 从 stdin 读取单个 JSON 对象（一个测试用例的参数）
 * 2. 解析参数，创建 Solution 实例，调用目标方法
 * 3. 调用期间隔离用户 stdout
 * 4. 输出单行 JSON 结果到 stdout：{"status":"OK","actual":...}
 */
@Component
public class JavaHarnessGenerator implements HarnessGenerator {
    public String language() { return "java"; }

    /**
     * 将 DSL 类型映射为 Java 合法类型名
     */
    public static String javaType(String dslType) {
        if (dslType == null) return "Object";
        if (com.nemo.OJbackendmodel.model.dto.question.CustomStructureContract.isNode(dslType)) return com.nemo.OJbackendmodel.model.dto.question.CustomStructureContract.name(dslType);
        if (dslType.startsWith("list_node<")) return "ListNode";
        if (dslType.startsWith("tree_node<")) return "TreeNode";
        if (dslType.endsWith("[]")) return javaType(dslType.substring(0, dslType.length() - 2)) + "[]";  // int[], int[][], String[] etc.
        if (dslType.startsWith("list<")) return "List";
        // 基础类型映射
        if ("int".equals(dslType)) return "int";
        if ("long".equals(dslType)) return "long";
        if ("double".equals(dslType)) return "double";
        if ("boolean".equals(dslType)) return "boolean";
        if ("string".equals(dslType)) return "String";
        if ("void".equals(dslType)) return "void";
        return "Object"; // 未知类型回退
    }

    public String generate(ProblemSpec spec) {
        String className = spec.getClassName();
        String methodName = spec.getMethodName();
        List<ParameterSpec> params = spec.getParameters();
        String returnType = spec.getReturnType();
        String resultSource = spec.getResultSource();

        StringBuilder sb = new StringBuilder();
        sb.append("import com.google.gson.*;\n");
        sb.append("import com.google.gson.stream.JsonReader;\n");
        sb.append("import java.io.*;\n");
        sb.append("import java.util.*;\n\n");
        sb.append("public class Runner {\n");
        sb.append("    public static void main(String[] args) throws Exception {\n");
        sb.append("        Gson gson = new GsonBuilder().serializeNulls().create();\n");
        sb.append("        JsonReader reader = new JsonReader(new InputStreamReader(System.in, \"UTF-8\"));\n");
        // 单用例协议：stdin 是一个 JSON 对象，不是数组
        sb.append("        JsonObject input = gson.fromJson(reader, JsonObject.class);\n");
        sb.append("        JsonObject result = new JsonObject();\n\n");
        sb.append("        // 保存原始 System.out，隔离用户 stdout\n");
        sb.append("        PrintStream originalOut = System.out;\n");
        sb.append("        System.setOut(new PrintStream(new ByteArrayOutputStream()));\n");
        sb.append("        try {\n");
        sb.append("            ").append(className).append(" solution = new ").append(className).append("();\n\n");

        // 参数解析
        sb.append("            // Parse parameters\n");
        for (int i = 0; i < params.size(); i++) {
            String paramName = nullSafeName(params.get(i).getName(), i);
            String pType = params.get(i).getType();
            sb.append("            ").append(javaType(pType)).append(" ").append(paramName)
              .append(" = (").append(javaType(pType)).append(")")
              .append(" deserializeParam(input.get(\"").append(paramName)
              .append("\"), \"").append(pType).append("\", gson);\n");
        }

        // 方法调用
        sb.append("\n            long t0 = System.nanoTime();\n");
        if (!"void".equals(returnType)) {
            sb.append("            ").append(javaType(returnType)).append(" ret = ");
        } else {
            sb.append("            ");
        }
        sb.append("solution.").append(methodName).append("(");
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(nullSafeName(params.get(i).getName(), i));
        }
        sb.append(");\n");
        sb.append("            long elapsed = (System.nanoTime() - t0) / 1_000_000;\n");
        sb.append("            result.addProperty(\"timeMs\", elapsed);\n\n");

        // 恢复 stdout
        sb.append("            // 恢复原始 stdout\n");
        sb.append("            System.setOut(originalOut);\n\n");

        // 结果序列化
        sb.append("            // Serialize result\n");
        if ("return".equals(resultSource) && !"void".equals(returnType)) {
            sb.append("            JsonElement actual = serializeResult(ret, \"")
              .append(returnType).append("\", gson);\n");
            sb.append("            result.add(\"actual\", actual);\n");
        } else if ("return".equals(resultSource) && "void".equals(returnType)) {
            // void 方法无返回值（防御代码，正常应在校验层拒绝此组合）
            sb.append("            result.add(\"actual\", JsonNull.INSTANCE);\n");
        } else if (resultSource != null && resultSource.startsWith("parameter:")) {
            int paramIdx = Integer.parseInt(resultSource.substring("parameter:".length()));
            String paramName = nullSafeName(params.get(paramIdx).getName(), paramIdx);
            String paramType = params.get(paramIdx).getType();
            sb.append("            JsonElement actual = serializeResult(").append(paramName)
              .append(", \"").append(paramType).append("\", gson);\n");
            sb.append("            result.add(\"actual\", actual);\n");
        } else if (resultSource != null && resultSource.startsWith("parameters:[")) {
            // parameters:[0,1] → 将多个原地修改的参数序列化为 JSON 数组
            String indicesStr = resultSource.substring("parameters:[".length(),
                    resultSource.length() - 1); // 去掉末尾 "]"
            String[] idxParts = indicesStr.split(",");
            sb.append("            JsonArray actualArr = new JsonArray();\n");
            for (String idxPart : idxParts) {
                int paramIdx = Integer.parseInt(idxPart.trim());
                String paramName = nullSafeName(params.get(paramIdx).getName(), paramIdx);
                String paramType = params.get(paramIdx).getType();
                sb.append("            actualArr.add(serializeResult(").append(paramName)
                  .append(", \"").append(paramType).append("\", gson));\n");
            }
            sb.append("            result.add(\"actual\", actualArr);\n");
        } else {
            sb.append("            result.add(\"actual\", JsonNull.INSTANCE);\n");
        }

        sb.append("            result.addProperty(\"status\", \"OK\");\n");
        sb.append("        } catch (Exception e) {\n");
        sb.append("            System.setOut(originalOut);\n");
        sb.append("            result.addProperty(\"status\", \"RUNTIME_ERROR\");\n");
        sb.append("            StringWriter sw = new StringWriter();\n");
        sb.append("            e.printStackTrace(new PrintWriter(sw));\n");
        sb.append("            result.addProperty(\"error\", sw.toString());\n");
        sb.append("        }\n\n");

        // 单行协议输出
        sb.append("        // Write single-line protocol output\n");
        sb.append("        System.out.println(gson.toJson(result));\n");
        sb.append("        System.out.flush();\n");
        sb.append("    }\n\n");

        // 参数反序列化
        sb.append("    private static Object deserializeParam(JsonElement json, String type, Gson gson) {\n");
        sb.append("        if (json == null || json.isJsonNull()) return null;\n");
        sb.append("        if (type.startsWith(\"list_node<\")) {\n");
        sb.append("            return ListNode.fromJson(json.getAsJsonArray());\n");
        sb.append("        }\n");
        sb.append("        if (type.startsWith(\"tree_node<\")) {\n");
        sb.append("            return TreeNode.fromLevelOrder(json.getAsJsonArray());\n");
        sb.append("        }\n");
        sb.append("        if (\"int\".equals(type)) return json.getAsInt();\n");
        sb.append("        if (\"long\".equals(type)) return json.getAsLong();\n");
        sb.append("        if (\"double\".equals(type)) return json.getAsDouble();\n");
        sb.append("        if (\"boolean\".equals(type)) return json.getAsBoolean();\n");
        sb.append("        if (\"string\".equals(type)) return json.getAsString();\n");
        sb.append("        if (\"int[]\".equals(type)) {\n");
        sb.append("            JsonArray arr = json.getAsJsonArray();\n");
        sb.append("            int[] out = new int[arr.size()];\n");
        sb.append("            for (int i = 0; i < arr.size(); i++) out[i] = arr.get(i).getAsInt();\n");
        sb.append("            return out;\n");
        sb.append("        }\n");
        sb.append("        if (\"long[]\".equals(type)) {\n");
        sb.append("            JsonArray arr = json.getAsJsonArray();\n");
        sb.append("            long[] out = new long[arr.size()];\n");
        sb.append("            for (int i = 0; i < arr.size(); i++) out[i] = arr.get(i).getAsLong();\n");
        sb.append("            return out;\n");
        sb.append("        }\n");
        sb.append("        if (\"double[]\".equals(type)) {\n");
        sb.append("            JsonArray arr = json.getAsJsonArray();\n");
        sb.append("            double[] out = new double[arr.size()];\n");
        sb.append("            for (int i = 0; i < arr.size(); i++) out[i] = arr.get(i).getAsDouble();\n");
        sb.append("            return out;\n");
        sb.append("        }\n");
        sb.append("        if (\"boolean[]\".equals(type)) {\n");
        sb.append("            JsonArray arr = json.getAsJsonArray();\n");
        sb.append("            boolean[] out = new boolean[arr.size()];\n");
        sb.append("            for (int i = 0; i < arr.size(); i++) out[i] = arr.get(i).getAsBoolean();\n");
        sb.append("            return out;\n");
        sb.append("        }\n");
        sb.append("        if (\"int[][]\".equals(type)) {\n");
        sb.append("            JsonArray arr = json.getAsJsonArray();\n");
        sb.append("            int[][] out = new int[arr.size()][];\n");
        sb.append("            for (int i = 0; i < arr.size(); i++) {\n");
        sb.append("                JsonArray row = arr.get(i).getAsJsonArray();\n");
        sb.append("                out[i] = new int[row.size()];\n");
        sb.append("                for (int j = 0; j < row.size(); j++) out[i][j] = row.get(j).getAsInt();\n");
        sb.append("            }\n");
        sb.append("            return out;\n");
        sb.append("        }\n");
        sb.append("        if (\"string[]\".equals(type)) {\n");
        sb.append("            JsonArray arr = json.getAsJsonArray();\n");
        sb.append("            String[] out = new String[arr.size()];\n");
        sb.append("            for (int i = 0; i < arr.size(); i++) out[i] = arr.get(i).getAsString();\n");
        sb.append("            return out;\n");
        sb.append("        }\n");
        sb.append("        if (\"list<int>\".equals(type)) {\n");
        sb.append("            JsonArray arr = json.getAsJsonArray();\n");
        sb.append("            List<Integer> out = new ArrayList<>();\n");
        sb.append("            for (int i = 0; i < arr.size(); i++) out.add(arr.get(i).getAsInt());\n");
        sb.append("            return out;\n");
        sb.append("        }\n");
        sb.append("        return gson.fromJson(json, Object.class);\n");
        sb.append("    }\n\n");

        // 结果序列化
        sb.append("    private static JsonElement serializeResult(Object obj, String type, Gson gson) {\n");
        sb.append("        if (obj == null) return JsonNull.INSTANCE;\n");
        sb.append("        if (type.startsWith(\"list_node<\")) {\n");
        sb.append("            return ((ListNode) obj).toJson();\n");
        sb.append("        }\n");
        sb.append("        if (type.startsWith(\"tree_node<\")) {\n");
        sb.append("            return ((TreeNode) obj).toJson();\n");
        sb.append("        }\n");
        sb.append("        if (\"int[]\".equals(type)) {\n");
        sb.append("            int[] arr = (int[]) obj;\n");
        sb.append("            JsonArray jarr = new JsonArray();\n");
        sb.append("            for (int v : arr) jarr.add(v);\n");
        sb.append("            return jarr;\n");
        sb.append("        }\n");
        sb.append("        if (\"long[]\".equals(type)) {\n");
        sb.append("            long[] arr = (long[]) obj;\n");
        sb.append("            JsonArray jarr = new JsonArray();\n");
        sb.append("            for (long v : arr) jarr.add(v);\n");
        sb.append("            return jarr;\n");
        sb.append("        }\n");
        sb.append("        if (\"double[]\".equals(type)) {\n");
        sb.append("            double[] arr = (double[]) obj;\n");
        sb.append("            JsonArray jarr = new JsonArray();\n");
        sb.append("            for (double v : arr) jarr.add(v);\n");
        sb.append("            return jarr;\n");
        sb.append("        }\n");
        sb.append("        if (\"boolean[]\".equals(type)) {\n");
        sb.append("            boolean[] arr = (boolean[]) obj;\n");
        sb.append("            JsonArray jarr = new JsonArray();\n");
        sb.append("            for (boolean v : arr) jarr.add(v);\n");
        sb.append("            return jarr;\n");
        sb.append("        }\n");
        sb.append("        if (\"int[][]\".equals(type)) {\n");
        sb.append("            int[][] arr = (int[][]) obj;\n");
        sb.append("            JsonArray jarr = new JsonArray();\n");
        sb.append("            for (int[] row : arr) {\n");
        sb.append("                JsonArray jrow = new JsonArray();\n");
        sb.append("                for (int v : row) jrow.add(v);\n");
        sb.append("                jarr.add(jrow);\n");
        sb.append("            }\n");
        sb.append("            return jarr;\n");
        sb.append("        }\n");
        sb.append("        if (\"string[]\".equals(type)) {\n");
        sb.append("            String[] arr = (String[]) obj;\n");
        sb.append("            JsonArray jarr = new JsonArray();\n");
        sb.append("            for (String v : arr) jarr.add(v);\n");
        sb.append("            return jarr;\n");
        sb.append("        }\n");
        sb.append("        if (\"list<int>\".equals(type)) {\n");
        sb.append("            @SuppressWarnings(\"unchecked\")\n");
        sb.append("            List<Integer> list = (List<Integer>) obj;\n");
        sb.append("            JsonArray jarr = new JsonArray();\n");
        sb.append("            for (int v : list) jarr.add(v);\n");
        sb.append("            return jarr;\n");
        sb.append("        }\n");
        sb.append("        if (obj instanceof Number) return new JsonPrimitive((Number) obj);\n");
        sb.append("        if (obj instanceof Boolean) return new JsonPrimitive((Boolean) obj);\n");
        sb.append("        if (obj instanceof String) return new JsonPrimitive((String) obj);\n");
        sb.append("        return gson.toJsonTree(obj);\n");
        sb.append("    }\n");

        sb.append("}\n");
        String code=sb.toString();
        if(CustomNodeGenerator.enabled(spec)){
            code=code.replace("if (json == null || json.isJsonNull()) return null;","if (type.startsWith(\"node<\")) return NemoCustomCodec.read(json, type);\n        if (json == null || json.isJsonNull()) return null;");
            code=code.replace("private static JsonElement serializeResult(Object obj, String type, Gson gson) {","private static JsonElement serializeResult(Object obj, String type, Gson gson) {\n        if (type.startsWith(\"node<\")) return NemoCustomCodec.write(obj, type);");
        }
        return code;
    }

    private String nullSafeName(String name, int idx) {
        return (name != null && !name.isEmpty()) ? name : "param" + idx;
    }
}
