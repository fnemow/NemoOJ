package com.nemo.nemosandbox.runtime;

import java.util.List;

/**
 * 运行时描述符 —— 镜像、工具链版本等静态元数据。
 */
public class RuntimeDescriptor {

    private final String imageRef;
    private final List<String> acceptedExtensions;
    private final List<String> compileUnits;
    private final String protocolVersion;
    private final String toolchainVersion;
    private final String runtimeId;

    public RuntimeDescriptor(String imageRef, List<String> acceptedExtensions,
                             List<String> compileUnits, String protocolVersion,
                             String toolchainVersion, String runtimeId) {
        this.imageRef = imageRef;
        this.acceptedExtensions = acceptedExtensions;
        this.compileUnits = compileUnits;
        this.protocolVersion = protocolVersion;
        this.toolchainVersion = toolchainVersion;
        this.runtimeId = runtimeId;
    }

    public String getImageRef() { return imageRef; }
    public List<String> getAcceptedExtensions() { return acceptedExtensions; }
    public List<String> getCompileUnits() { return compileUnits; }
    public String getProtocolVersion() { return protocolVersion; }
    public String getToolchainVersion() { return toolchainVersion; }
    public String getRuntimeId() { return runtimeId; }
}
