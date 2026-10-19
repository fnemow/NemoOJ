package com.nemo.OJbackendjudgeservice.judge.codesandbox.impl;


import com.nemo.OJbackendjudgeservice.judge.codesandbox.CodeSandBox;
import com.nemo.OJbackendmodel.model.codesandbox.ExecuteCodeRequest;
import com.nemo.OJbackendmodel.model.codesandbox.ExecuteCodeResponse;

/**
 * 跑通业务流程（不是具体实现）
 */
public class ThirdPartyCodeSandBox implements CodeSandBox {

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        System.out.println("第三方代码沙箱");
        //return null
        return new ExecuteCodeResponse();
    }
}
