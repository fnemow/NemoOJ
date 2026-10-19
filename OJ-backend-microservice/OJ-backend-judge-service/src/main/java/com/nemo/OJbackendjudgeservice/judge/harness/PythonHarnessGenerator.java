package com.nemo.OJbackendjudgeservice.judge.harness;
import com.google.gson.Gson;
import com.nemo.OJbackendmodel.model.dto.question.ProblemSpec;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
@Component
public class PythonHarnessGenerator implements HarnessGenerator {
    public String language() { return "python"; }
    public String generate(ProblemSpec spec) {
        // Only the invocation signature belongs in the untrusted execution container.
        // Expected answers remain in the judge service, outside generated Runner code.
        ProblemSpec visible=new ProblemSpec();org.springframework.beans.BeanUtils.copyProperties(spec,visible);visible.setJudgeCases(null);
        String encoded = Base64.getEncoder().encodeToString(new Gson().toJson(visible).getBytes(StandardCharsets.UTF_8));
        String code=HarnessGeneratorRegistry.resource("runner.py").replace("__SPEC_BASE64__", encoded);
        if(CustomNodeGenerator.enabled(spec)){
            code=code.replace("def convert(value, kind, output=False):",CustomNodeGenerator.pythonSource(spec)+"\ndef convert(value, kind, output=False):\n    if kind.startswith('node<'): return custom_convert(value, kind, output)");
            code=code.replace("module.ListNode, module.TreeNode = ListNode, TreeNode","module.ListNode, module.TreeNode = ListNode, TreeNode\n    module.__dict__.update(CUSTOM_NODE_CLASSES)");
        }return code;
    }
}
