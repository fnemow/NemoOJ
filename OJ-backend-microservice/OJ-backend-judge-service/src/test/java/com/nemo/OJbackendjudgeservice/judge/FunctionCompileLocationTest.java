package com.nemo.OJbackendjudgeservice.judge;

import com.google.gson.Gson;
import com.nemo.OJbackendjudgeservice.judge.harness.*;
import com.nemo.OJbackendjudgeservice.judge.mode.FunctionJudgeModeAdapter;
import com.nemo.OJbackendmodel.model.dto.question.*;
import com.nemo.OJbackendmodel.model.entity.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FunctionCompileLocationTest {
    @TempDir Path temp;

    @Test void javacReportsEditorLineAndHandlesSignatureErrorsWithoutFakeLine() throws Exception {
        Locale previous=Locale.getDefault();Locale.setDefault(Locale.ENGLISH);
        try {
        for (String newline : List.of("\n", "\r\n")) {
            String code=String.join(newline,"class Solution {"," public int solve(int value) {","  return missing;"," }","}");
            String diagnostic=compileJava(code);
            assertTrue(diagnostic.contains("Solution.java:3: error:"),diagnostic);
            assertFalse(diagnostic.contains("Runner.java:"));
        }
        String signature=compileJava("class Solution { public int other(int value) { return value; } }");
        assertTrue(signature.contains("题目接口校验"),signature);
        assertTrue(signature.contains("solve"),signature);
        assertFalse(signature.contains("Runner.java:"),signature);
        assertFalse(signature.contains("solution.solve"),signature);
        } finally { Locale.setDefault(previous); }
    }

    String compileJava(String code) throws Exception {
        var plan=plan("java",code,false);
        List<String> args=new ArrayList<>(List.of("--release","8","-proc:none","-Xlint:-options","-encoding","UTF-8","-cp",
                Path.of(Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString()));
        for(var source:plan.getSourceFiles()) {
            Path path=temp.resolve(source.getFileName());Files.writeString(path,source.getContent());args.add(path.toString());
        }
        var bytes=new java.io.ByteArrayOutputStream();
        assertNotEquals(0,javax.tools.ToolProvider.getSystemJavaCompiler().run(null,bytes,bytes,args.toArray(new String[0])));
        return UserDiagnosticSanitizer.functionCompile(bytes.toString(StandardCharsets.UTF_8).replace(temp.toString()+"\\","").replace(temp.toString()+"/",""));
    }

    @Test void exportsRealPlansAndChecksNativeDiagnosticsWhenProvided() throws Exception {
        List<Map<String,Object>> fixtures=new ArrayList<>();
        add(fixtures,"java","class Solution {\n public int solve(int value) {\n  return missing;\n }\n}",false,"Solution.java:3:");
        add(fixtures,"java","class Solution { public int other(int value) { return value; } }",false,null);
        add(fixtures,"python","class Solution:\n def solve(self, value):\n  return (\n",false,"line 3");
        for(boolean split:List.of(false,true)) {
            add(fixtures,"cpp","class Solution {\npublic:\n int solve(int value) {\n  return missing;\n }\n};",split,"solution.hpp:4:");
            add(fixtures,"cpp","class Solution { public: int other(int value) { return value; } };",split,null);
        }
        String export=System.getenv("NEMO_DIAGNOSTIC_FIXTURES");
        if(export!=null)Files.writeString(Path.of(export),new Gson().toJson(fixtures));
        String results=System.getenv("NEMO_DIAGNOSTIC_RESULTS");
        if(results!=null) {
            var raw=com.google.gson.JsonParser.parseString(Files.readString(Path.of(results))).getAsJsonArray();
            assertEquals(fixtures.size(),raw.size());
            for(int i=0;i<raw.size();i++) {
                String shown=UserDiagnosticSanitizer.functionCompile(raw.get(i).getAsString());
                Object location=fixtures.get(i).get("location");
                if(location!=null)assertTrue(shown.contains(location.toString()),"fixture "+i+": "+shown);
                else assertTrue(shown.contains("题目接口校验"),shown);
                assertFalse(shown.contains("Runner.java:"),shown);
                assertFalse(shown.contains("runner.cpp:"),shown);
                assertFalse(shown.contains("<string>"),shown);
                assertFalse(shown.contains("/app/"),shown);
            }
        }
    }

    void add(List<Map<String,Object>> fixtures,String language,String code,boolean split,String location) {
        var item=new LinkedHashMap<String,Object>();item.put("language",language);item.put("executionPlan",plan(language,code,split));item.put("location",location);fixtures.add(item);
    }
    com.nemo.OJbackendmodel.model.execution.ExecutionPlan plan(String language,String code,boolean split) {
        var spec=new ProblemSpec("function","Solution","solve",List.of(new ParameterSpec("value","int")),"int","return",null,1);
        var q=new Question();q.setProblemSpec(spec);
        var c=new JudgeCase();c.setInput("{\"value\":1}");c.setOutput("1");spec.setJudgeCases(List.of(c));
        var config=new JudgeConfig();config.setTimeLimit(2000L);config.setMemoryLimit(262144L);config.setOutputLimitBytes(1048576L);q.setJudgeConfig(config);
        var cpp=new CppHarnessGenerator();ReflectionTestUtils.setField(cpp,"splitProtocolEnabled",split);
        var adapter=new FunctionJudgeModeAdapter();ReflectionTestUtils.setField(adapter,"harnessRegistry",new HarnessGeneratorRegistry(List.of(new JavaHarnessGenerator(),new PythonHarnessGenerator(),cpp)));
        var submit=new QuestionSubmit();submit.setLanguage(language);submit.setCode(code);
        return adapter.buildExecutionPlan(q,submit);
    }
}
