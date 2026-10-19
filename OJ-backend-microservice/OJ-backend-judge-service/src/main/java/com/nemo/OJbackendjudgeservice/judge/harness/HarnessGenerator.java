package com.nemo.OJbackendjudgeservice.judge.harness;
import com.nemo.OJbackendmodel.model.dto.question.ProblemSpec;
public interface HarnessGenerator {
    String language();
    String generate(ProblemSpec spec);
}
