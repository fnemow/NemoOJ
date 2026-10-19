package com.nemo.OJbackendjudgeservice.judge;

import com.nemo.OJbackendjudgeservice.judge.checker.TextChecker;
import com.nemo.OJbackendjudgeservice.judge.strategy.JudgeContext;
import com.nemo.OJbackendmodel.model.codesandbox.JudgeInfo;
import com.nemo.OJbackendmodel.model.dto.question.ComparatorSpec;
import com.nemo.OJbackendmodel.model.dto.question.JudgeCase;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PrivateValidationDiagnosticTest {
    @Test void internalDiagnosticIdentifiesFirstMismatchUsingTheRealComparator() {
        var manager=new JudgeManager();ReflectionTestUtils.setField(manager,"checkerList",List.of(new TextChecker()));
        var validator=new PrivateProblemValidationService(null,null,null,null,manager,null);
        var good=new JudgeCase();good.setInput("first");good.setOutput("3\n");
        var bad=new JudgeCase();bad.setInput("second");bad.setOutput("7");
        var context=new JudgeContext();context.setJudgeInfo(new JudgeInfo());context.setJudgeCaseList(List.of(good,bad));
        context.setInputList(List.of("first","second"));context.setOutputList(List.of("3","8"));
        try {
            String diagnostic=validator.mismatchDiagnostic(context,new ComparatorSpec("TEXT",null));
            assertTrue(diagnostic.contains("\"caseIndex\":1"));assertTrue(diagnostic.contains("\"input\":\"second\""));
            assertTrue(diagnostic.contains("\"expected\":\"7\""));assertTrue(diagnostic.contains("\"actual\":\"8\""));
        } finally {validator.close();}
    }
}
