package com.nemo.OJbackendjudgeservice.judge.codesandbox;


import com.nemo.OJbackendmodel.model.codesandbox.ExecuteCodeRequest;
import com.nemo.OJbackendmodel.model.codesandbox.ExecuteCodeRequestV2;
import com.nemo.OJbackendmodel.model.codesandbox.ExecuteCodeResponse;
import com.nemo.OJbackendmodel.model.codesandbox.ExecuteCodeResponseV2;

public interface CodeSandBox {

    /**
     * 代码沙箱执行接口 (V1)
     * @param executeCodeRequest
     * @return
     */
    ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest);

    /**
     * V2 执行接口：支持多源文件、stdin、逐用例结构化响应。
     * 默认抛异常，由支持 V2 的实现类重写。
     */
    default ExecuteCodeResponseV2 executeCodeV2(ExecuteCodeRequestV2 request) {
        throw new UnsupportedOperationException("V2 protocol not supported");
    }
}
