package com.nemo.OJbackendjudgeservice.judge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserDiagnosticSanitizerTest {
    @Test
    void functionCompileKeepsUserLocationsButNeverPresentsRunnerLinesAsUserLines() {
        String raw = "/app/Runner.java:143: error: cannot find symbol\n"
                + "        result = solution.solve(arg0);\n                       ^\n"
                + "  symbol:   method solve(int[])\n  location: variable solution of type Solution\n"
                + "/app/Solution.java:3: error: missing return statement\n  }\n  ^\n2 errors";
        String result = UserDiagnosticSanitizer.functionCompile(raw);
        assertTrue(result.contains("Solution.java:3: error: missing return statement"));
        assertTrue(result.contains("method solve(int[])"));
        assertTrue(result.contains("题目接口校验"));
        assertFalse(result.contains("143"));
        assertFalse(result.contains("solution.solve(arg0)"));
        assertFalse(result.contains("Runner.java"));
    }

    @Test
    void cppKeepsUserLineAndColumnAndDropsIncludeStackAndGeneratedSnippets() {
        String raw = "In file included from /app/runner.cpp:42:\n"
                + "/app/solution.hpp:4:9: error: 'missing' was not declared\n"
                + "    4 | return missing;\n      |        ^~~~~~~\n"
                + "/app/runner.cpp:150:24: error: no member named solve\n"
                + "  150 | auto result = solution.solve(arg0);\n      |                        ^~~~~\n"
                + "/app/solution.hpp:2:7: note: candidate declared here\n    2 | int other() {\n";
        String result = UserDiagnosticSanitizer.functionCompile(raw);
        assertTrue(result.contains("solution.hpp:4:9: error:"));
        assertTrue(result.contains("solution.hpp:2:7: note:"));
        assertFalse(result.contains("runner.cpp"));
        assertFalse(result.contains("150"));
        assertFalse(result.contains("included from"));
    }

    @Test
    void pythonShowsSyntaxLocationWithoutPrecheckTraceback() {
        String raw = "Traceback (most recent call last):\n"
                + "  File \"<string>\", line 1, in <module>\n"
                + "  File \"<string>\", line 1, in <listcomp>\n"
                + "  File \"/app/solution.py\", line 4\n    return (\n           ^\nSyntaxError: '(' was never closed";
        String result = UserDiagnosticSanitizer.functionCompile(raw);
        assertTrue(result.startsWith("File \"solution.py\", line 4"));
        assertTrue(result.contains("SyntaxError"));
        assertFalse(result.contains("<string>"));
        assertFalse(result.contains("Traceback"));
        assertEquals(null, UserDiagnosticSanitizer.functionCompile(null));
    }
    @Test
    void hidesSameLineImportsAndCorrectsFirstLineCaretWithoutChangingLocations() {
        String prefix = com.nemo.OJbackendjudgeservice.judge.harness.FunctionCodePrelude.JAVA_IMPORTS;
        String source = "class Solution { wrong }";
        String raw = "/app/Solution.java:1: error: expected\n" + prefix + source + "\n"
                + " ".repeat(prefix.length() + 17) + "^\n1 error";
        assertEquals("Solution.java:1: error: expected\n" + source + "\n" + " ".repeat(17) + "^\n1 error",
                UserDiagnosticSanitizer.sanitize(raw));
    }

    @Test
    void hidesContainerPathAndKeepsCompilerLocation() {
        String raw = "编译错误:\n/app/Solution.java:4: error: ';' expected\n"
                + "        return 3\n"
                + "                ^\n"
                + "1 error\n";

        String result = UserDiagnosticSanitizer.sanitize(raw);

        assertFalse(result.contains("/app/"));
        assertFalse(result.startsWith("编译错误"));
        assertTrue(result.startsWith("Solution.java:4: error: ';' expected"));
        assertTrue(result.contains("return 3\n                ^"));
        assertTrue(result.endsWith("1 error"));
    }

    @Test
    void normalizesUriPathsLineEndingsAndAnsiSequences() {
        String raw = "Compile error:\r\n\u001B[31mfile:///app/main.cpp:2:3: error: boom\u001B[0m\r\n";

        assertEquals("main.cpp:2:3: error: boom", UserDiagnosticSanitizer.sanitize(raw));
    }
}
