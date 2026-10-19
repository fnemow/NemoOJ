package com.nemo.OJbackendjudgeservice.judge.harness;
import com.nemo.OJbackendmodel.model.dto.question.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class FunctionResultValidatorTest {
    @Test void cannotSpoofTypesOrOverflowRunnerResults() {
        var spec=new ProblemSpec("function","Solution","solve",List.of(new ParameterSpec("value","int[]")),"long","return",null,1);
        assertTrue(FunctionResultValidator.valid(spec,"9223372036854775807"));
        assertFalse(FunctionResultValidator.valid(spec,"9223372036854775808"));
        assertFalse(FunctionResultValidator.valid(spec,"null"));
        assertFalse(FunctionResultValidator.valid(spec,"\"1\""));
        spec.setReturnType("boolean");assertFalse(FunctionResultValidator.valid(spec,"1"));
        spec.setReturnType("double");assertFalse(FunctionResultValidator.valid(spec,"1e9999"));
        spec.setResultSource("parameters:[0,0]");
        assertTrue(FunctionResultValidator.valid(spec,"[[1],[2]]"));
        assertFalse(FunctionResultValidator.valid(spec,"[[1]]"));
    }
}
