package com.nemo.OJbackendjudgeservice.judge;

import java.util.regex.Pattern;

/**
 * 将沙箱诊断转换成可直接展示给用户的文本。
 * 容器目录属于基础设施细节，不能出现在题目工作区或历史提交中。
 */
final class UserDiagnosticSanitizer {

    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))");
    private static final Pattern COMPILE_HEADING = Pattern.compile(
            "(?i)^(?:编译错误|compile(?:r)? error)\\s*[:：]?\\s*(?:\\n+)?");
    private static final Pattern LEADING_BLANK_LINES = Pattern.compile("^\\n+");
    private static final Pattern COMPILER_LOCATION = Pattern.compile(
            "^([^\\s:]+\\.(?:java|cpp|hpp|h)):(\\d+)(?::(\\d+))?:\\s*(.*)$");
    private static final Pattern PYTHON_LOCATION = Pattern.compile("^\\s*File \"([^\"]+)\", line \\d+.*$");

    /** User sources are separate files (Java's imports share line 1), so their
     * compiler locations already match the editor. Never subtract a Runner offset
     * or invent a user line for a signature error emitted at a generated call site. */
    static String functionCompile(String detail) {
        String value = sanitize(detail);
        if (value == null || value.isBlank()) return value;
        StringBuilder output = new StringBuilder();
        boolean generated = false;
        boolean pythonPrecheck = value.startsWith("Traceback (most recent call last):");
        for (String line : value.split("\n", -1)) {
            if (line.startsWith("In file included from ") || line.matches("\\s+from \\S+:\\d+.*")) continue;
            if (line.matches("(?:Runner\\.java|runner\\.cpp|nodes\\.hpp|ListNode\\.java|TreeNode\\.java):.*")
                    && !COMPILER_LOCATION.matcher(line).matches()) {
                generated = true;
                continue;
            }
            var location = COMPILER_LOCATION.matcher(line);
            var python = PYTHON_LOCATION.matcher(line);
            if (location.matches()) {
                String file = location.group(1);
                generated = !file.equals("Solution.java") && !file.equals("solution.hpp");
                if (generated) {
                    String message = location.group(4);
                    if (message.matches("(?:fatal error|error|warning|note|错误|警告|注):.*"))
                        output.append("题目接口校验：").append(message).append('\n');
                    continue;
                }
            } else if (python.matches()) {
                generated = !python.group(1).equals("solution.py");
                pythonPrecheck = false;
                if (generated) continue;
            } else if (pythonPrecheck) {
                continue;
            } else if (generated) {
                // Keep compiler explanations, but not generated source/carets/line numbers.
                String text = line.stripLeading();
                if (!(text.matches("(?:symbol|required|found|reason|符号|需要|找到|原因):.*")
                        || text.matches("(?:SyntaxError|IndentationError|TabError):.*"))) continue;
            }
            output.append(line).append('\n');
        }
        return output.isEmpty() ? "题目接口校验失败，请检查类名、方法名、参数和返回类型是否符合题目要求。"
                : output.toString().trim();
    }

    private UserDiagnosticSanitizer() {
    }

    static String sanitize(String detail) {
        if (detail == null) {
            return null;
        }
        String value = detail.replace("\r\n", "\n").replace('\r', '\n');
        value = ANSI_ESCAPE.matcher(value).replaceAll("");
        value = com.nemo.OJbackendjudgeservice.judge.harness.FunctionCodePrelude.hideJavaImports(value);

        // 兼容编译器、解释器和 URI 形式输出；只隐藏路径前缀，保留文件名与行列号。
        value = value.replace("file:///app/", "")
                .replace("file:/app/", "")
                .replace("/app/", "")
                .replace("\\app\\", "");

        // 页面已经按阶段展示标题，去掉沙箱重复添加的“编译错误”。
        value = COMPILE_HEADING.matcher(value).replaceFirst("");
        value = LEADING_BLANK_LINES.matcher(value).replaceFirst("");
        return value.trim();
    }
}
