package com.nemo.nemosandbox.runtime;

import com.nemo.nemosandbox.model.ExecutionMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;

/** GCC 14, C++17; compile and run use the same pinned deployment image. */
@Component
public class CppRuntimeAdapter implements LanguageRuntimeAdapter {
    @Value("${sandbox.language-policies.cpp.compile-timeout-seconds:30}") private long compileSeconds = 30;
    @Value("${sandbox.language-policies.cpp.compile-memory-bytes:536870912}") private long compileMemory = 536870912L;
    @Value("${sandbox.language-policies.cpp.runtime-factor:1.0}") private double factor = 1.0;
    @Value("${sandbox.language-policies.cpp.process-limit:64}") private int processLimit = 64;
    @Value("${sandbox.language-policies.cpp.output-limit-bytes:1048576}") private int outputLimit = 1048576;

    @Value("${sandbox.image.cpp:}")
    private String image;
    @Value("${SANDBOX_CPP_SPLIT_PROTOCOL_ENABLED:false}")
    private boolean splitProtocolEnabled;
    public String language() { return "cpp"; }
    public RuntimeDescriptor descriptor() {
        return new RuntimeDescriptor(image, List.of(".cpp", ".hpp"), List.of(".cpp"), "2.0", "GCC 14", "cpp-17-gcc14");
    }
    public Optional<CommandSpec> compileCommand(WorkspaceManifest workspace) {
        String unit = workspace.getMode() == ExecutionMode.FUNCTION ? "/app/runner.cpp" : "/app/main.cpp";
        if (splitProtocolEnabled && workspace.getMode() == ExecutionMode.FUNCTION) {
            return Optional.of(new CommandSpec(new String[]{"/usr/local/bin/g++", "-std=c++17", "-O2", "-pipe",
                    unit, "/opt/nemooj/libjson-lite.a", "-o", "/app/main"}));
        }
        return Optional.of(new CommandSpec(new String[]{"/usr/local/bin/g++", "-std=c++17", "-O2", "-pipe", unit, "-o", "/app/main"}));
    }
    public CommandSpec runCommand(WorkspaceManifest workspace) { return new CommandSpec(new String[]{"/app/main"}); }
    public LanguagePolicy policy() { return new LanguagePolicy(compileSeconds, compileMemory, factor, processLimit, outputLimit); }
}
