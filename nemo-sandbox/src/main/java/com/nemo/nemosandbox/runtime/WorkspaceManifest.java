package com.nemo.nemosandbox.runtime;

import com.nemo.nemosandbox.model.ExecutionMode;

import java.util.List;

/**
 * 工作区清单 —— 仅暴露容器内路径，不暴露宿主机文件系统。
 */
public class WorkspaceManifest {

    private final ExecutionMode mode;
    private final List<String> containerFiles;  // ["/app/Main.java", "/app/Solution.java"]
    private final String entryPoint;            // "Main" or "Runner"

    public WorkspaceManifest(ExecutionMode mode, List<String> containerFiles, String entryPoint) {
        this.mode = mode;
        this.containerFiles = containerFiles;
        this.entryPoint = entryPoint;
    }

    public ExecutionMode getMode() {
        return mode;
    }

    public List<String> getContainerFiles() {
        return containerFiles;
    }

    public String getEntryPoint() {
        return entryPoint;
    }
}
