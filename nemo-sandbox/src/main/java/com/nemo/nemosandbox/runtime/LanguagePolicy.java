package com.nemo.nemosandbox.runtime;

/**
 * 语言资源策略 —— 每种语言独立配置编译超时、内存和补偿系数。
 */
public class LanguagePolicy {

    private final long compileTimeoutSeconds;
    private final long compileMemoryBytes;
    private final double runtimeFactor;
    private final int processLimit;
    private final int outputLimitBytes;

    public LanguagePolicy(long compileTimeoutSeconds, long compileMemoryBytes,
                          double runtimeFactor, int processLimit, int outputLimitBytes) {
        if (compileTimeoutSeconds <= 0 || compileTimeoutSeconds > 60 || compileMemoryBytes < 16777216L || compileMemoryBytes > 1073741824L || !Double.isFinite(runtimeFactor) || runtimeFactor <= 0 || runtimeFactor > 3 || processLimit <= 0 || processLimit > 256 || outputLimitBytes <= 0 || outputLimitBytes > 1048576) throw new IllegalArgumentException("Invalid runtime policy");
        this.compileTimeoutSeconds = compileTimeoutSeconds;
        this.compileMemoryBytes = compileMemoryBytes;
        this.runtimeFactor = runtimeFactor;
        this.processLimit = processLimit;
        this.outputLimitBytes = outputLimitBytes;
    }

    public long getCompileTimeoutSeconds() { return compileTimeoutSeconds; }
    public long getCompileMemoryBytes() { return compileMemoryBytes; }
    public double getRuntimeFactor() { return runtimeFactor; }
    public int getProcessLimit() { return processLimit; }
    public int getOutputLimitBytes() { return outputLimitBytes; }
}
