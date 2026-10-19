package com.nemo.nemosandbox.runtime;

import java.util.Collections;
import java.util.Map;

/**
 * 编译/运行命令规格。
 * Runtime Adapter 只返回固定参数数组，不直接操作 Docker。
 */
public class CommandSpec {

    private final String[] command;
    private final Map<String, String> env;

    public CommandSpec(String[] command) {
        this(command, Collections.emptyMap());
    }

    public CommandSpec(String[] command, Map<String, String> env) {
        this.command = command;
        this.env = env;
    }

    public String[] getCommand() {
        return command;
    }

    public Map<String, String> getEnv() {
        return env;
    }
}
