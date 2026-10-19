package com.nemo.nemosandbox.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;

/** CPython 3.12. The precheck compiles source text without executing user code. */
@Component
public class PythonRuntimeAdapter implements LanguageRuntimeAdapter {
    @Value("${sandbox.language-policies.python.compile-timeout-seconds:10}") private long compileSeconds = 10;
    @Value("${sandbox.language-policies.python.compile-memory-bytes:268435456}") private long compileMemory = 268435456L;
    @Value("${sandbox.language-policies.python.runtime-factor:3.0}") private double factor = 3.0;
    @Value("${sandbox.language-policies.python.process-limit:64}") private int processLimit = 64;
    @Value("${sandbox.language-policies.python.output-limit-bytes:1048576}") private int outputLimit = 1048576;

    @Value("${sandbox.image.python:}")
    private String image;
    public String language() { return "python"; }
    public RuntimeDescriptor descriptor() {
        return new RuntimeDescriptor(image, List.of(".py"), List.of(".py"), "2.0", "CPython 3.12", "python-3.12");
    }
    public Optional<CommandSpec> compileCommand(WorkspaceManifest workspace) {
        List<String> args = new ArrayList<>(List.of("/usr/local/bin/python3", "-I", "-B", "-X", "utf8", "-c",
                "import sys,pathlib; [(compile(pathlib.Path(p).read_text(encoding='utf-8'),p,'exec')) for p in sys.argv[1:]]"));
        args.addAll(workspace.getContainerFiles());
        return Optional.of(new CommandSpec(args.toArray(new String[0])));
    }
    public CommandSpec runCommand(WorkspaceManifest workspace) {
        return new CommandSpec(new String[]{"/usr/local/bin/python3", "-I", "-B", "-u", "-X", "utf8", "/app/" + workspace.getEntryPoint()});
    }
    public LanguagePolicy policy() { return new LanguagePolicy(compileSeconds, compileMemory, factor, processLimit, outputLimit); }
}
