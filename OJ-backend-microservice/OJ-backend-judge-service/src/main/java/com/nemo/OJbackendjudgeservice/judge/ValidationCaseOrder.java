package com.nemo.OJbackendjudgeservice.judge;

import com.nemo.OJbackendmodel.model.execution.ExecutionCaseInput;
import java.util.*;

/** Stable, execution-only ordering. Original indexes and published cases are unchanged. */
final class ValidationCaseOrder {
    static List<ExecutionCaseInput> prioritize(List<ExecutionCaseInput> inputs) {
        if(inputs==null)return null;
        var ordered=new ArrayList<>(inputs);
        ordered.sort(Comparator.comparingInt(ValidationCaseOrder::rank)
            .thenComparingInt(c->c.getStdin()==null?0:c.getStdin().length())
            .thenComparingInt(ExecutionCaseInput::getCaseIndex));
        return ordered;
    }
    private static int rank(ExecutionCaseInput c) {
        if(c.getCaseIndex()!=null&&c.getCaseIndex()<2)return -1; // Public examples first.
        String s=c.getStdin()==null?"":c.getStdin();
        if(s.isBlank()||s.contains("[]")||s.contains("\"\"")||s.contains(":null"))return 0;
        if(s.matches("(?s).*\\b(?:0|1|-1)\\b.*"))return 1;
        return 2;
    }
}
