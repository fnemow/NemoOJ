package com.nemo.OJbackendmodel.model.vo;

import com.nemo.OJbackendmodel.model.dto.question.JudgeCase;
import com.nemo.OJbackendmodel.model.dto.question.ProblemSpec;
import com.nemo.OJbackendmodel.model.entity.Question;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QuestionVOTest {
    @Test
    void publicViewKeepsSignatureButNeverSharesHiddenFunctionCases() {
        JudgeCase hidden = new JudgeCase();
        hidden.setInput("private-input");
        hidden.setOutput("private-answer");
        hidden.setIsHidden(true);
        ProblemSpec spec = new ProblemSpec();
        spec.setMode("function");
        spec.setClassName("Solution");
        spec.setMethodName("solve");
        spec.setReturnType("int");
        spec.setJudgeCases(List.of(hidden));
        var node=new com.nemo.OJbackendmodel.model.dto.question.CustomStructureSpec();node.setName("RBNode");node.setFields(List.of(new com.nemo.OJbackendmodel.model.dto.question.ParameterSpec("value","int")));spec.setDataStructures(List.of(node));
        Question question = new Question();
        question.setTags("[]");
        question.setProblemSpec(spec);

        QuestionVO publicView = QuestionVO.objToVo(question);

        assertEquals("solve", publicView.getProblemSpec().getMethodName());
        assertNull(publicView.getProblemSpec().getJudgeCases());
        assertNotSame(spec, publicView.getProblemSpec());
        assertEquals("RBNode",publicView.getProblemSpec().getDataStructures().get(0).getName());
        assertNotSame(node,publicView.getProblemSpec().getDataStructures().get(0));
        assertEquals(1, question.getProblemSpec().getJudgeCases().size());
    }
}
