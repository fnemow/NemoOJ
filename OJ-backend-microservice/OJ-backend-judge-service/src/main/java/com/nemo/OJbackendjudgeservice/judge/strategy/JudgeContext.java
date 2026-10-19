package com.nemo.OJbackendjudgeservice.judge.strategy;


import com.nemo.OJbackendmodel.model.codesandbox.JudgeInfo;
import com.nemo.OJbackendmodel.model.dto.question.JudgeCase;
import com.nemo.OJbackendmodel.model.entity.Question;
import com.nemo.OJbackendmodel.model.entity.QuestionSubmit;
import lombok.Data;

import java.util.List;

@Data
public class JudgeContext {
    private com.nemo.OJbackendmodel.model.execution.ResourceLimit effectiveLimits;
    private JudgeInfo judgeInfo;

    private List<String> inputList;

    private List<String> outputList;

    private List<JudgeCase> judgeCaseList;

    private Question question;

    private QuestionSubmit questionSubmit;

}
