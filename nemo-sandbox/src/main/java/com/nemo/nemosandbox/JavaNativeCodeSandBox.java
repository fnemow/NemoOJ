package com.nemo.nemosandbox;

import cn.hutool.dfa.WordTree;
import com.nemo.nemosandbox.model.ExecuteCodeRequest;
import com.nemo.nemosandbox.model.ExecuteCodeResponse;
import com.nemo.nemosandbox.model.JudgeInfo;
import com.nemo.nemosandbox.security.MySecurityManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component("javaNativeCodeSandBox")
@ConditionalOnProperty(name = "sandbox.type", havingValue = "native")
@Profile("dev")
public class JavaNativeCodeSandBox extends JavaCodeSandBoxTemplate implements CodeSandBox {

    private static final List<String> BLACK_LIST = Arrays.asList(
            "java.lang.System",
            "java.lang.Runtime",
            "java.lang.ProcessBuilder",
            "java.lang.reflect",
            "java.io",
            "java.net",
            "java.nio",
            "java.util.concurrent",
            "java.util.function",
            "java.util.stream",
            "javax.script",
            "javax.management",
            "javax.naming",
            "javax.rmi",
            "javax.security",
            "javax.sql",
            "javax.swing",
            "javax.xml"
    );

    private static final WordTree WORD_TREE = new WordTree();

    static {
        WORD_TREE.addWords(BLACK_LIST);
        // 初始化安全管理器类路径
        String classPath = MySecurityManager.class.getProtectionDomain()
                .getCodeSource().getLocation().getPath();
        securityManagerPath = classPath;
        securityManagerClassName = MySecurityManager.class.getName();
    }

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        // 黑名单 DFA 过滤
        String code = executeCodeRequest.getCode();
        if (WORD_TREE.matchWord(code) != null) {
            ExecuteCodeResponse response = new ExecuteCodeResponse();
            response.setOutputList(new ArrayList<>());
            response.setMessage("代码包含禁止使用的操作");
            response.setStatus(2);
            response.setJudgeInfo(new JudgeInfo());
            return response;
        }
        return super.executeCode(executeCodeRequest);
    }
}
