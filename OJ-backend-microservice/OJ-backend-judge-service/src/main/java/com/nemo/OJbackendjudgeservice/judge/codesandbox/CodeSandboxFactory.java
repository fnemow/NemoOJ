package com.nemo.OJbackendjudgeservice.judge.codesandbox;

import com.nemo.OJbackendcommon.common.ErrorCode;
import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendjudgeservice.judge.codesandbox.impl.ExampleCodeSandBox;
import com.nemo.OJbackendjudgeservice.judge.codesandbox.impl.RemoteCodeSandBox;
import com.nemo.OJbackendjudgeservice.judge.codesandbox.impl.ThirdPartyCodeSandBox;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 代码沙箱工厂 —— Spring 管理的 Bean，配置由 @Value 注入后传递给沙箱实例
 */
@Slf4j
@Component
public class CodeSandboxFactory {

    /**
     * 沙箱地址与密钥不设代码内默认值：
     * 缺少配置时应用启动即失败，避免用示例密钥连上生产沙箱。
     */
    @Value("${judge.sandbox.base-url}")
    private String sandboxBaseUrl;

    @Value("${judge.sandbox.secret}")
    private String sandboxSecret;

    @Value("${judge.sandbox.connect-timeout-ms:2000}")
    private int connectTimeoutMs;

    @Value("${judge.sandbox.read-timeout-ms:15000}")
    private int readTimeoutMs;

    public CodeSandBox newInstance(String type) {
        if (type == null || type.isEmpty()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "沙箱类型未配置");
        }
        switch (type) {
            case "example":
                log.warn("使用 Example 沙箱（仅限开发测试）");
                return new ExampleCodeSandBox();
            case "remote":
                RemoteCodeSandBox remote = new RemoteCodeSandBox();
                remote.setBaseUrl(sandboxBaseUrl);
                remote.setSecret(sandboxSecret);
                remote.setConnectTimeoutMs(connectTimeoutMs);
                remote.setReadTimeoutMs(readTimeoutMs);
                return remote;
            case "thirdParty":
                return new ThirdPartyCodeSandBox();
            default:
                throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                        "未知的沙箱类型: " + type + "，有效值: example, remote, thirdParty");
        }
    }
}
