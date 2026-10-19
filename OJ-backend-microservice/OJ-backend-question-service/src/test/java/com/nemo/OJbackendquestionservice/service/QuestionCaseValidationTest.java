package com.nemo.OJbackendquestionservice.service;

import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendmodel.model.dto.question.JudgeCase;
import com.nemo.OJbackendmodel.model.entity.Question;
import com.nemo.OJbackendquestionservice.service.impl.QuestionServiceImpl;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class QuestionCaseValidationTest {
    @Test void emptyStreamsAreValidButMissingValuesAreRejected() {
        var service = new QuestionServiceImpl() {
            @Override public Question getById(java.io.Serializable id) { return null; }
        };
        var question = new Question();
        var example = new JudgeCase();
        example.setInput("");
        example.setOutput("\n");
        question.setJudgeCase(Collections.singletonList(example));
        assertDoesNotThrow(() -> service.validQuestion(question, false));
        example.setOutput(null);
        assertThrows(BusinessException.class, () -> service.validQuestion(question, false));
        question.setJudgeCase(Collections.singletonList(null));
        assertThrows(BusinessException.class, () -> service.validQuestion(question, false));
    }
}
