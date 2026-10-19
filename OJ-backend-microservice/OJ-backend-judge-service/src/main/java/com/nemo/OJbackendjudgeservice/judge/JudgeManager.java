package com.nemo.OJbackendjudgeservice.judge;

import com.nemo.OJbackendjudgeservice.judge.checker.CheckResult;
import com.nemo.OJbackendjudgeservice.judge.checker.OutputChecker;
import com.nemo.OJbackendjudgeservice.judge.strategy.DefaultJudgeStrategy;
import com.nemo.OJbackendjudgeservice.judge.strategy.JudgeContext;
import com.nemo.OJbackendmodel.model.codesandbox.JudgeInfo;
import com.nemo.OJbackendmodel.model.dto.question.ComparatorSpec;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 判题管理：协调策略选择和比较器选择。
 */
@Service
public class JudgeManager {

    @Resource
    private List<OutputChecker> checkerList;

    /**
     * V1 判题（保持向后兼容：使用 DefaultJudgeStrategy 的字符串比较）
     */
    public JudgeInfo doJudge(JudgeContext judgeContext) {
        DefaultJudgeStrategy judgeStrategy = new DefaultJudgeStrategy();
        return judgeStrategy.doJudge(judgeContext);
    }

    /**
     * V2 判题：根据 ComparatorSpec 选择对应的比较器。
     *
     * @param judgeContext  判题上下文（含 expected/actual 列表）
     * @param comparator    比较器配置（null 时默认使用 TextChecker）
     * @return 判题结果
     */
    public JudgeInfo doJudgeV2(JudgeContext judgeContext, ComparatorSpec comparator) {
        DefaultJudgeStrategy judgeStrategy = new DefaultJudgeStrategy();

        // 选择比较器
        String checkerType = (comparator != null && comparator.getType() != null)
                ? comparator.getType().toUpperCase() : "TEXT";
        OutputChecker checker = getChecker(checkerType);

        // 构建比较器配置参数
        Map<String, Object> checkerConfig = new HashMap<>();
        if (comparator != null && comparator.getEpsilon() != null) {
            checkerConfig.put("epsilon", comparator.getEpsilon());
        }

        return judgeStrategy.doJudgeWithChecker(judgeContext, checker, checkerConfig);
    }

    private OutputChecker getChecker(String type) {
        for (OutputChecker c : checkerList) {
            if (c.getType().equalsIgnoreCase(type)) {
                return c;
            }
        }
        // 回退：取第一个匹配 "TEXT" 的比较器
        for (OutputChecker c : checkerList) {
            if ("TEXT".equals(c.getType())) return c;
        }
        return checkerList.get(0);
    }
}
