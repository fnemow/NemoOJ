package com.nemo.nemosandbox;

import com.nemo.nemosandbox.model.ExecuteCodeRequest;
import com.nemo.nemosandbox.model.ExecuteCodeRequestV2;
import com.nemo.nemosandbox.model.ExecuteCodeResponse;
import com.nemo.nemosandbox.model.ExecuteCodeResponseV2;

public interface CodeSandBox {

    /**
     * 代码沙箱执行接口 (V1)
     * @param executeCodeRequest
     * @return
     */
    ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest);

    /**
     * V2 执行接口：支持多源文件、stdin 输入、逐用例结构化响应。
     * 默认抛出 UnsupportedOperationException，由支持 V2 的实现类重写。
     */
    default ExecuteCodeResponseV2 executeCodeV2(ExecuteCodeRequestV2 request) {
        throw new UnsupportedOperationException("V2 protocol not supported by this sandbox");
    }
    default ExecuteCodeResponseV2 executeCodeV2(ExecuteCodeRequestV2 request, Runnable betweenContainers) {
        return executeCodeV2(request);
    }
}
