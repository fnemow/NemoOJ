package com.nemo.nemosandbox.runtime;

import com.nemo.nemosandbox.model.*;
import java.util.*;

/** Exact server-owned file layouts; no arbitrary entry points or extra translation units. */
public final class ExecutionLayout {
    private ExecutionLayout() {}
    public static void validate(ExecutionPlan plan, RuntimeDescriptor descriptor) {
        boolean function = plan.getMode() == ExecutionMode.FUNCTION;
        if (plan.getMode() == null) throw new IllegalArgumentException("Missing execution mode");
        Set<String> expected;
        String entry;
        switch (plan.getLanguage()) {
            case "java":
                expected = function ? Set.of("Solution.java", "Runner.java", "ListNode.java", "TreeNode.java") : Set.of("Main.java");
                entry = function ? "Runner" : "Main"; break;
            case "python":
                expected = function ? Set.of("solution.py", "runner.py") : Set.of("main.py");
                entry = function ? "runner.py" : "main.py"; break;
            case "cpp":
                expected = function ? Set.of("solution.hpp", "runner.cpp", "nodes.hpp") : Set.of("main.cpp");
                entry = "main"; break;
            default: throw new IllegalArgumentException("Unsupported language");
        }
        if (!entry.equals(plan.getEntryPoint())) throw new IllegalArgumentException("Invalid entry point");
        Set<String> actual = new HashSet<>();
        if (plan.getSourceFiles() == null) throw new IllegalArgumentException("Missing sources");
        for (SourceFile file : plan.getSourceFiles()) {
            if (file == null || file.getFileName() == null || !actual.add(file.getFileName()))
                throw new IllegalArgumentException("Null or duplicate source");
            if (descriptor.getAcceptedExtensions().stream().noneMatch(file.getFileName()::endsWith))
                throw new IllegalArgumentException("Invalid source extension");
        }
        if (!actual.equals(expected)) throw new IllegalArgumentException("Invalid source layout");
    }
}
