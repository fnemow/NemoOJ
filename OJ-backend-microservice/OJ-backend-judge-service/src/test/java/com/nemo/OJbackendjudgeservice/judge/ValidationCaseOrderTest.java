package com.nemo.OJbackendjudgeservice.judge;
import com.nemo.OJbackendmodel.model.execution.ExecutionCaseInput;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class ValidationCaseOrderTest {
    @Test void prioritizesExamplesAndEmptyInputsWithoutDroppingOrChangingIndexes(){
        var inputs=List.of(new ExecutionCaseInput(0,"123"),new ExecutionCaseInput(1,"456"),
            new ExecutionCaseInput(2,"longer typical input"),new ExecutionCaseInput(3,"{\"a\":[]}"),new ExecutionCaseInput(4,"0"));
        var ordered=ValidationCaseOrder.prioritize(inputs);
        assertEquals(List.of(0,1,3,4,2),ordered.stream().map(ExecutionCaseInput::getCaseIndex).toList());
        assertEquals(List.of(0,1,2,3,4),inputs.stream().map(ExecutionCaseInput::getCaseIndex).toList());
        assertEquals(new java.util.HashSet<>(inputs),new java.util.HashSet<>(ordered));
    }
}
