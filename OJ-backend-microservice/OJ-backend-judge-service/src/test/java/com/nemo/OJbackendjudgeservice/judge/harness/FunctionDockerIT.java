package com.nemo.OJbackendjudgeservice.judge.harness;

import com.google.gson.*;
import com.nemo.OJbackendjudgeservice.judge.mode.FunctionJudgeModeAdapter;
import com.nemo.OJbackendmodel.model.codesandbox.*;
import com.nemo.OJbackendmodel.model.dto.question.*;
import com.nemo.OJbackendmodel.model.entity.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicitly opt in; runs generated plans against the real Linux Docker sandbox. */
@EnabledIfEnvironmentVariable(named="NEMO_TEST_SANDBOX_URL",matches=".+")
class FunctionDockerIT {
    @Test void allTypesExecuteWithThePublishedContainerProtocol() throws Exception {
        String base=System.getenv("NEMO_TEST_SANDBOX_URL");
        String secret=System.getenv("SANDBOX_SECRET");
        assertNotNull(secret,"SANDBOX_SECRET must be configured");
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var gson=new Gson();
        var adapter=new FunctionJudgeModeAdapter();
        ReflectionTestUtils.setField(adapter,"harnessRegistry",new HarnessGeneratorRegistry(List.of(new JavaHarnessGenerator(),new PythonHarnessGenerator(),new CppHarnessGenerator())));
        for(String language:List.of("java","python","cpp")) {
            for(String[] item:MultiLanguageHarnessTest.VALUES) {
                var spec=new ProblemSpec("function","Solution","solve",List.of(new ParameterSpec("value",item[0])),item[0],"return",null,1);
                var question=new Question();question.setProblemSpec(spec);
                var config=new JudgeConfig();config.setTimeLimit(2000L);config.setMemoryLimit(262144L);config.setOutputLimitBytes(1048576L);question.setJudgeConfig(config);
                var testCase=new JudgeCase();testCase.setInput("{\"value\":"+item[1]+"}");question.setJudgeCase(List.of(testCase));
                var submit=new QuestionSubmit();submit.setLanguage(language);
                String type=language.equals("cpp")?CppHarnessGenerator.cppType(item[0]):JavaHarnessGenerator.javaType(item[0]);
                submit.setCode(switch(language) {
                    case "python" -> "class Solution:\n def solve(self, value):\n  print('debug')\n  return value\n";
                    case "cpp" -> "class Solution { public: "+type+" solve("+type+" value) { std::cout << \"debug\"; return value; } };";
                    default -> "import java.util.*; public class Solution { public "+type+" solve("+type+" value) { System.out.println(\"debug\"); return value; } }";
                });
                var plan=adapter.buildExecutionPlan(question,submit);
                String body=gson.toJson(ExecuteCodeRequestV2.builder().submissionId(0L).executionPlan(plan).build());
                var request=HttpRequest.newBuilder(URI.create(base.replaceAll("/$", "")+"/api/v2/executeCode"))
                    .header("auth",secret).header("Content-Type","application/json").timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                var response=client.send(request,HttpResponse.BodyHandlers.ofString());
                assertEquals(200,response.statusCode(),language+" "+item[0]+" "+response.body());
                var result=gson.fromJson(response.body(),ExecuteCodeResponseV2.class);
                assertEquals(0,result.getOverallStatus(),language+" "+item[0]+" "+response.body());
                assertEquals(1,result.getCases().size());
                var wire=JsonParser.parseString(result.getCases().get(0).getStdout()).getAsJsonObject();
                assertEquals("OK",wire.get("status").getAsString(),response.body());
                assertEquals(JsonParser.parseString(item[1]),wire.get("actual"),language+" "+item[0]);
                assertNotNull(result.getEffectiveLimits());
                System.out.println("FUNCTION " + language + " " + item[0] + " PASS");
            }
        }
    }
}
