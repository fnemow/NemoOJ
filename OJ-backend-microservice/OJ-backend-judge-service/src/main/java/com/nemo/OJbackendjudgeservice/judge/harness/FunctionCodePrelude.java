package com.nemo.OJbackendjudgeservice.judge.harness;

/** Java imports are compilation-unit scoped, so Runner imports cannot cover Solution.java. */
public final class FunctionCodePrelude {
    // One physical line preserves every user source line, including stack-trace locations.
    public static final String JAVA_IMPORTS = "import java.util.*; import java.math.*; import java.io.*; "
            + "import java.util.regex.*; import java.util.stream.*; import java.util.function.*; ";

    private FunctionCodePrelude() { }

    public static String javaSource(String code) {
        return JAVA_IMPORTS + code;
    }

    public static String hideJavaImports(String diagnostic) {
        String[] lines = diagnostic.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].startsWith(JAVA_IMPORTS)) continue;
            lines[i] = lines[i].substring(JAVA_IMPORTS.length());
            if (i + 1 < lines.length && lines[i + 1].matches(" *\\^.*")
                    && lines[i + 1].indexOf('^') >= JAVA_IMPORTS.length()) {
                lines[i + 1] = lines[i + 1].substring(JAVA_IMPORTS.length());
            }
        }
        return String.join("\n", lines);
    }
}
