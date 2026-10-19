package com.nemo.nemosandbox.runtime;

import java.util.Optional;

/**
 * 语言运行时适配器 —— 负责返回工具链命令和镜像信息。
 * 适配器不直接操作 Docker，只返回固定命令数组。
 */
public interface LanguageRuntimeAdapter {

    /** 语言标识，与 ExecutionPlan.language 对应 */
    String language();

    /** 运行时静态描述符 */
    RuntimeDescriptor descriptor();

    /**
     * 编译或语法预检查命令；无需预检查的运行时可以返回 Optional.empty()。
     * @param workspace 工作区清单（仅容器内路径）
     */
    Optional<CommandSpec> compileCommand(WorkspaceManifest workspace);

    /** 运行命令 */
    CommandSpec runCommand(WorkspaceManifest workspace);

    /** 资源策略 */
    LanguagePolicy policy();
}
