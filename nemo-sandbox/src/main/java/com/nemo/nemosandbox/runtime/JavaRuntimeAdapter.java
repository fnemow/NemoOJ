package com.nemo.nemosandbox.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Java 运行时适配器。
 * 编译: javac -encoding utf-8 -cp /app/lib/* &lt;java files&gt;
 * 运行: java -cp /app:/app/lib/* &lt;entryPoint&gt;
 *
 * 镜像可通过 sandbox.image.java 配置固定 digest，避免可变标签带来的不可复现问题。
 * 生产环境应将标签替换为具体 digest，例如:
 *   sandbox.image.java: openjdk@sha256:abc123...
 */
@Component
public class JavaRuntimeAdapter implements LanguageRuntimeAdapter {
    @Value("${sandbox.language-policies.java.compile-timeout-seconds:30}") private long compileSeconds = 30;
    @Value("${sandbox.language-policies.java.compile-memory-bytes:536870912}") private long compileMemory = 536870912L;
    @Value("${sandbox.language-policies.java.runtime-factor:1.0}") private double factor = 1.0;
    @Value("${sandbox.language-policies.java.process-limit:64}") private int processLimit = 64;
    @Value("${sandbox.language-policies.java.output-limit-bytes:1048576}") private int outputLimit = 1048576;


    @Value("${sandbox.image.java:openjdk:8-alpine}")
    private String imageRef;

    private RuntimeDescriptor descriptor;

    @PostConstruct
    void init() {
        this.descriptor = new RuntimeDescriptor(
                imageRef,
                Collections.singletonList(".java"),
                Collections.singletonList(".java"),
                "2.0",
                "javac 1.8.0",
                "java-8"
        );
    }

    @Override
    public String language() {
        return "java";
    }

    @Override
    public RuntimeDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public Optional<CommandSpec> compileCommand(WorkspaceManifest workspace) {
        List<String> javaFiles = workspace.getContainerFiles().stream()
                .filter(f -> f.endsWith(".java"))
                .collect(Collectors.toList());

        if (javaFiles.isEmpty()) {
            return Optional.empty();
        }

        List<String> cmd = new ArrayList<>();
        cmd.add("javac");
        cmd.add("-encoding");
        cmd.add("utf-8");
        cmd.add("-cp");
        cmd.add("/app/lib/*");
        cmd.addAll(javaFiles);

        return Optional.of(new CommandSpec(cmd.toArray(new String[0])));
    }

    @Override
    public CommandSpec runCommand(WorkspaceManifest workspace) {
        return new CommandSpec(new String[]{
                "java", "-Dfile.encoding=UTF-8", "-cp", "/app:/app/lib/*", workspace.getEntryPoint()
        });
    }

    @Override
    public LanguagePolicy policy() {
        return new LanguagePolicy(compileSeconds, compileMemory, factor, processLimit, outputLimit);
    }
}
