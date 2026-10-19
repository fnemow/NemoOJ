package com.nemo.OJbackendquestionservice.service;
import com.nemo.OJserviceclient.JudgeFeignClient;
import com.nemo.OJbackendmodel.model.dto.question.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.nemo.OJbackendmodel.model.entity.Question;
class LanguageCapabilityServiceTest {
    @Test void combinesDeploymentModesSignatureAndReadinessWithoutLeakingImages() {
        var judge=mock(JudgeFeignClient.class);
        var env=new MockEnvironment().withProperty("judge.language.enabled","java,python,cpp").withProperty("judge.language.modes.python","ACM");
        List<Map<String,Object>> remote=new ArrayList<>();
        for(String language:List.of("java","python","cpp")) remote.add(new HashMap<>(Map.of("language",language,"modes",List.of("ACM","FUNCTION"),"imageReady",true,"runtimeId",language+"-fixed","imageDigest","private-image")));
        when(judge.capabilities()).thenReturn(remote);
        var service=new LanguageCapabilityService(judge,env);
        assertEquals(3,service.available(null).size());
        var spec=new ProblemSpec("function","Solution","solve",List.of(new ParameterSpec("value","int")),"int","return",null,1);
        assertEquals(2,service.available(spec).size());
        var visible=service.publicCapabilities(spec);
        assertTrue(visible.stream().noneMatch(c->c.containsKey("imageDigest")));
        assertTrue(visible.stream().anyMatch(c->"python".equals(c.get("language")) && Boolean.FALSE.equals(c.get("available"))));
        spec.setMethodName("def");
        assertEquals(1,service.available(spec).size());
    }
    @Test void dependencyFailureDoesNotReuseAnExpiredPositiveCapability() {
        var judge=mock(JudgeFeignClient.class);
        when(judge.capabilities()).thenThrow(new IllegalStateException("down"));
        var service=new LanguageCapabilityService(judge,new MockEnvironment());
        assertThrows(com.nemo.OJbackendcommon.exception.BusinessException.class,()->service.require("java",null));
    }
    @Test void exposesStableFrontendCapabilityAndTemplateContracts() {
        var judge=mock(JudgeFeignClient.class);
        when(judge.capabilities()).thenReturn(List.of(new HashMap<>(Map.of(
                "language","python","modes",List.of("ACM"),"imageReady",true,
                "runtimeId","python-fixed","runtimeFactor",3.0))));
        var service=new LanguageCapabilityService(judge,new MockEnvironment().withProperty("judge.language.enabled","python"));
        var question=new Question(); question.setId(9007199254740993L); question.setProblemVersion(7);
        var config=new JudgeConfig(); config.setTimeLimit(1000L); config.setMemoryLimit(65536L); config.setOutputLimitBytes(1048576L);
        question.setJudgeConfig(config);
        var response=service.problemCapabilities(question);
        assertEquals("ACM",response.get("mode"));
        var languages=(List<Map<String,Object>>)response.get("languages");
        var python=languages.stream().filter(v->"python".equals(v.get("id"))).findFirst().orElseThrow();
        assertEquals(true,python.get("enabled"));
        assertEquals(3000,((Map<?,?>)python.get("effectiveLimits")).get("timeLimitMs"));
        var template=service.codeTemplate(question,"python",7);
        assertEquals("main.py",template.get("fileName"));
        assertThrows(com.nemo.OJbackendcommon.exception.BusinessException.class,
                ()->service.codeTemplate(question,"python",6));
    }

    @Test void exposesBothModesAndBuildsModeSpecificTemplatesForFunctionQuestions() {
        var judge=mock(JudgeFeignClient.class);
        when(judge.capabilities()).thenReturn(List.of(new HashMap<>(Map.of(
                "language","java","modes",List.of("ACM","FUNCTION"),"imageReady",true,
                "runtimeId","java-fixed","runtimeFactor",1.0))));
        var service=new LanguageCapabilityService(judge,new MockEnvironment().withProperty("judge.language.enabled","java"));
        var spec=new ProblemSpec("function","Solution","solve",List.of(new ParameterSpec("value","int")),"int","return",null,1);
        var question=new Question(); question.setId(42L); question.setProblemVersion(3); question.setProblemSpec(spec);

        var response=service.problemCapabilities(question);
        assertEquals("FUNCTION",response.get("mode"));
        assertEquals(List.of("ACM","FUNCTION"),response.get("availableModes"));
        var languages=(List<Map<String,Object>>)response.get("languages");
        var java=languages.stream().filter(v->"java".equals(v.get("id"))).findFirst().orElseThrow();
        assertEquals(List.of("ACM","FUNCTION"),java.get("modes"));

        var acm=service.codeTemplate(question,"java",3,"ACM");
        var function=service.codeTemplate(question,"java",3,"FUNCTION");
        assertEquals("Main.java",acm.get("fileName"));
        assertTrue(String.valueOf(acm.get("code")).contains("class Main"));
        assertFalse(String.valueOf(acm.get("code")).contains("throws Exception"));
        assertEquals("Solution.java",function.get("fileName"));
        assertTrue(String.valueOf(function.get("templateVersion")).endsWith("-v2"));
        assertTrue(String.valueOf(function.get("code")).contains("class Solution"));
    }

    @Test void buildsLeetCodeStyleFunctionTemplatesWithoutExceptionPlaceholders() {
        var service=new LanguageCapabilityService(mock(JudgeFeignClient.class),new MockEnvironment());
        var spec=new ProblemSpec("function","Solution","solve",List.of(
                new ParameterSpec("values","int[]"),new ParameterSpec("target","int")),"int","return",null,1);

        var cpp=service.template("cpp",spec);
        assertTrue(cpp.startsWith("class Solution"));
        assertFalse(cpp.contains("#include"));
        assertFalse(cpp.contains("using namespace"));
        assertTrue(cpp.contains("int solve(std::vector<int>& values, int target)"));
        assertFalse(cpp.contains("int32_t"));
        assertFalse(cpp.contains("throw"));
        assertFalse(cpp.contains("<stdexcept>"));

        var java=service.template("java",spec);
        assertTrue(java.startsWith("class Solution"));
        assertFalse(java.contains("import "));
        assertTrue(java.contains("public int solve(int[] values, int target)"));
        assertFalse(java.contains("UnsupportedOperationException"));

        var python=service.template("python",spec);
        assertTrue(python.contains("def solve(self, values, target):\n        pass"));
        assertFalse(python.contains("NotImplementedError"));
    }

    @Test void providesOnlyMinimalAcmEntryPoints() {
        var service=new LanguageCapabilityService(mock(JudgeFeignClient.class),new MockEnvironment());
        String java=service.template("java",null);
        assertTrue(java.contains("public class Main"));
        assertFalse(java.contains("Scanner"));
        assertFalse(java.contains("PrintWriter"));
        assertFalse(java.contains("out.flush();"));
        assertFalse(java.contains("throws Exception"));
        String python=service.template("python",null);
        assertFalse(python.contains("read = sys.stdin.readline"));
        assertFalse(python.contains("write = sys.stdout.write"));
        String cpp=service.template("cpp",null);
        assertTrue(cpp.contains("using namespace std;"));
        assertFalse(cpp.contains("ios::sync_with_stdio(false);"));
        assertFalse(cpp.contains("cin.tie(nullptr);"));
        assertFalse(cpp.contains("int32_t"));
    }

    @Test void versionsAcmRollbackWithoutChangingFunctionTemplates() {
        var judge=mock(JudgeFeignClient.class);
        when(judge.capabilities()).thenReturn(List.of(new HashMap<>(Map.of(
                "language","java","modes",List.of("ACM","FUNCTION"),"imageReady",true,"runtimeId","java8"))));
        var service=new LanguageCapabilityService(judge,new MockEnvironment().withProperty("judge.language.enabled","java"));
        var question=new Question();question.setId(1L);question.setProblemVersion(1);
        question.setProblemSpec(new ProblemSpec("function","Solution","solve",List.of(new ParameterSpec("n","int")),"int","return",null,1));
        assertEquals("java8-acm-basic-v4",service.codeTemplate(question,"java",1,"ACM").get("templateVersion"));
        assertEquals("java8-v2",service.codeTemplate(question,"java",1,"FUNCTION").get("templateVersion"));
    }
}
