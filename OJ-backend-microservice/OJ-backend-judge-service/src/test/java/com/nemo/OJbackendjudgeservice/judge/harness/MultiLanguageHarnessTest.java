package com.nemo.OJbackendjudgeservice.judge.harness;
import com.google.gson.*;
import com.nemo.OJbackendmodel.model.dto.question.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
class MultiLanguageHarnessTest {
    @TempDir Path dir;
    static final String[][] VALUES={
        {"int","2147483647"},{"long","9223372036854775807"},{"double","1.25"},{"boolean","true"},
        {"string","\"你好\\n\\\"text\""},{"int[]","[1,-2]"},{"long[]","[9223372036854775807]"},
        {"double[]","[1.5]"},{"boolean[]","[true,false]"},{"string[]","[\"你好\"]"},
        {"int[][]","[[],[1,2]]"},{"list<int>","[1,2]"},{"list_node<int>","[1,2]"},{"tree_node<int>","[1,null,2]"}
    };
    ProblemSpec spec(String type) {
        return new ProblemSpec("function","Solution","solve",List.of(new ParameterSpec("value",type)),type,"return",null,1);
    }
    @Test void javaBaselineAllTypesStillCompileAndRun() throws Exception {
        var compiler=javax.tools.ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler,"Java harness tests require a JDK");
        String gsonPath=Path.of(Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        var adapter=new com.nemo.OJbackendjudgeservice.judge.mode.FunctionJudgeModeAdapter();
        org.springframework.test.util.ReflectionTestUtils.setField(adapter,"harnessRegistry",new HarnessGeneratorRegistry(List.of(new JavaHarnessGenerator())));
        for(String[] item:VALUES) {
            var question=new com.nemo.OJbackendmodel.model.entity.Question();question.setProblemSpec(spec(item[0]));
            var submit=new com.nemo.OJbackendmodel.model.entity.QuestionSubmit();submit.setLanguage("java");
            String type=JavaHarnessGenerator.javaType(item[0]);
            submit.setCode("import java.util.*; public class Solution { public "+type+" solve("+type+" value) { System.out.println(\"debug\"); return value; } }");
            var plan=adapter.buildExecutionPlan(question,submit);
            List<String> args=new ArrayList<>(List.of("--release","8","-encoding","UTF-8","-cp",gsonPath,"-d",dir.toString()));
            for(var file:plan.getSourceFiles()) {
                Path path=dir.resolve(file.getFileName());Files.writeString(path,file.getContent());args.add(path.toString());
            }
            var diagnostics=new java.io.ByteArrayOutputStream();
            assertEquals(0,compiler.run(null,diagnostics,diagnostics,args.toArray(new String[0])),diagnostics.toString(StandardCharsets.UTF_8));
            String javaExecutable=Path.of(System.getProperty("java.home"),"bin","java").toString();
            var result=run(List.of(javaExecutable,"-Dfile.encoding=UTF-8","-Dstdout.encoding=UTF-8","-Dstderr.encoding=UTF-8","-cp",dir+java.io.File.pathSeparator+gsonPath,"Runner"),"{\"value\":"+item[1]+"}");
            assertEquals("OK",result.get("status").getAsString(),item[0]);
            assertEquals(JsonParser.parseString(item[1]),result.get("actual"),item[0]);
            if(item[0].contains("node")) {
                var empty=run(List.of(javaExecutable,"-Dfile.encoding=UTF-8","-Dstdout.encoding=UTF-8","-Dstderr.encoding=UTF-8","-cp",dir+java.io.File.pathSeparator+gsonPath,"Runner"),"{\"value\":[]}");
                assertTrue(empty.get("actual").isJsonNull());
            }
        }
    }
    @Test void registriesRejectDuplicates() {
        var python=new PythonHarnessGenerator();
        assertThrows(IllegalStateException.class,()->new HarnessGeneratorRegistry(List.of(python,python)));
    }
    @Test void javaCommonLibrariesCompileWithoutUserImportsAndKeepErrorLines() throws Exception {
        var compiler=javax.tools.ToolProvider.getSystemJavaCompiler();
        String code="class Solution {\n int solve() {\n"
            +"List<Integer> xs=new ArrayList<>(); xs.add(3); Deque<Integer> q=new ArrayDeque<>(xs);\n"
            +"PriorityQueue<Integer> heap=new PriorityQueue<>(q); Map<String,Integer> m=new HashMap<>();\n"
            +"BigInteger n=BigInteger.valueOf(3); Pattern p=Pattern.compile(\"3\");\n"
            +"Function<Integer,Integer> f=x->x+1; StringReader r=new StringReader(\"x\");\n"
            +"return xs.stream().mapToInt(f::apply).sum()+n.intValue()+heap.peek(); } }\n";
        var file=dir.resolve("Solution.java");
        Files.writeString(file,FunctionCodePrelude.javaSource(code));
        assertEquals(0,compiler.run(null,null,null,"--release","8",file.toString()));
        Files.writeString(file,FunctionCodePrelude.javaSource("class Solution {\n int solve() {\n return badSymbol;\n }\n}\n"));
        var diagnostic=new java.io.ByteArrayOutputStream();
        assertNotEquals(0,compiler.run(null,diagnostic,diagnostic,"--release","8",file.toString()));
        assertTrue(diagnostic.toString(StandardCharsets.UTF_8).contains("Solution.java:3:"));
    }
    @Test void pythonCommonLibrariesAndAnnotationsNeedNoUserImports() throws Exception {
        String executable=System.getenv("NEMO_TEST_PYTHON");
        Assumptions.assumeTrue(executable!=null);
        String code="class Solution:\n def solve(self, value: List[int]) -> List[int]:\n"
            +"  counts=Counter(value); q=deque(value); h=list(q); heapify(h)\n"
            +"  @cache\n  def cached(x): return x\n"
            +"  assert gcd(6,4)==2 and bisect_left(sorted(h),-100)==0\n"
            +"  assert list(accumulate([1,2]))==[1,3] and re.match('a','abc')\n"
            +"  return [cached(x) for x in q]\n";
        assertEquals("[3,1,3]",python(executable,spec("int[]"),code,"[3,1,3]").get("actual").toString());
        String overridden="from collections import Counter\ndef gcd(a,b): return 99\nclass Solution:\n def solve(self,value): return gcd(0,0)\n";
        assertEquals("99",python(executable,spec("int"),overridden,"0").get("actual").toString());
    }
    @Test void generatedCodeContainsNoAnswersAndUsesOnlyOneCppUnit() {
        for(String[] item:VALUES) {
            var s=spec(item[0]);
            assertFalse(new PythonHarnessGenerator().generate(s).contains("expected"));
            String cpp=new CppHarnessGenerator().generate(s);
            assertTrue(cpp.contains("#include \"solution.hpp\""));
            assertFalse(cpp.contains("__BODY__"));
        }
    }
    @Test void pythonAllTypesAndFailureBoundaries() throws Exception {
        String python=System.getenv("NEMO_TEST_PYTHON");
        Assumptions.assumeTrue(python!=null,"Set NEMO_TEST_PYTHON to run native harness verification");
        for(String[] item:VALUES) {
            var result=python(python,spec(item[0]),"class Solution:\n def solve(self, value):\n  print('debug')\n  return value\n",item[1]);
            assertEquals("OK",result.get("status").getAsString(),item[0]);
            assertEquals(JsonParser.parseString(item[1]),result.get("actual"),item[0]);
        }
        for(String type:List.of("int[]","int[][]","list_node<int>","tree_node<int>")) {
            assertEquals(JsonParser.parseString(type.contains("node") ? "null" : "[]"),python(python,spec(type),"class Solution:\n def solve(self, value): return value\n","[]").get("actual"));
        }
        for(String[] bad:new String[][]{{"int","2147483648"},{"long","9223372036854775808"},{"boolean","1"},{"double","1e999"}})
            assertEquals("RUNTIME_ERROR",python(python,spec(bad[0]),"class Solution:\n def solve(self, value): return value\n",bad[1]).get("status").getAsString());
        assertEquals("RUNTIME_ERROR",python(python,spec("list_node<int>"),"class Solution:\n def solve(self, value):\n  value.next=value\n  return value\n","[1]").get("status").getAsString());
        var inplace=spec("int[]"); inplace.setReturnType("void"); inplace.setResultSource("parameter:0");
        assertEquals("[2,1]",python(python,inplace,"class Solution:\n def solve(self, value): value.reverse()\n","[1,2]").get("actual").toString());
        inplace.setResultSource("parameters:[0,0]");
        assertEquals("[[2,1],[2,1]]",python(python,inplace,"class Solution:\n def solve(self, value): value.reverse()\n","[1,2]").get("actual").toString());
    }
    JsonObject python(String executable,ProblemSpec spec,String code,String input) throws Exception {
        Path solution=dir.resolve("solution.py"),runner=dir.resolve("runner.py");
        Files.writeString(solution,code);
        Files.writeString(runner,new PythonHarnessGenerator().generate(spec).replace("/app/solution.py",solution.toString().replace('\\','/')));
        return run(List.of(executable,"-I","-B","-u","-X","utf8",runner.toString()),"{\"value\":"+input+"}");
    }
    @Test void cppAllTypesAndInPlace() throws Exception {
        String cpp=System.getenv("NEMO_TEST_CPP"),include=System.getenv("NEMO_TEST_CPP_INCLUDE");
        Assumptions.assumeTrue(cpp!=null && include!=null,"Set NEMO_TEST_CPP and NEMO_TEST_CPP_INCLUDE");
        Files.writeString(dir.resolve("nodes.hpp"),HarnessGeneratorRegistry.resource("nodes.hpp"));
        for(String[] item:VALUES) {
            var spec=spec(item[0]);
            String type=CppHarnessGenerator.cppType(item[0]);
            Files.writeString(dir.resolve("solution.hpp"),"class Solution { public: "+type+" solve("+type+" value) { std::cout << \"debug\"; printf(\"debug\"); return value; } }; ");
            Files.writeString(dir.resolve("runner.cpp"),new CppHarnessGenerator().generate(spec));
            Path exe=dir.resolve("main.exe");
            runCompile(List.of(cpp,"-std=c++17","-O0","-I"+include,dir.resolve("runner.cpp").toString(),"-o",exe.toString()));
            JsonObject result=run(List.of(exe.toString()),"{\"value\":"+item[1]+"}");
            assertEquals("OK",result.get("status").getAsString(),item[0]);
            assertEquals(JsonParser.parseString(item[1]),result.get("actual"),item[0]);
            if (item[0].equals("int")) assertEquals("RUNTIME_ERROR",run(List.of(exe.toString()),"{\"value\":2147483648}").get("status").getAsString());
            if (item[0].equals("boolean")) assertEquals("RUNTIME_ERROR",run(List.of(exe.toString()),"{\"value\":1}").get("status").getAsString());
            if (item[0].endsWith("[]") || item[0].contains("node")) assertEquals(item[0].contains("node") ? "null" : "[]",run(List.of(exe.toString()),"{\"value\":[]}").get("actual").toString());
        }
    }
    @Test void cppMutableVectorsAndCycleRejection() throws Exception {
        String cpp=System.getenv("NEMO_TEST_CPP"),include=System.getenv("NEMO_TEST_CPP_INCLUDE");
        Assumptions.assumeTrue(cpp!=null && include!=null);
        Files.writeString(dir.resolve("nodes.hpp"),HarnessGeneratorRegistry.resource("nodes.hpp"));
        var spec=new ProblemSpec("function","Solution","solve",List.of(new ParameterSpec("value","boolean[]")),"void","parameters:[0,0]",null,1);
        Files.writeString(dir.resolve("solution.hpp"),"class Solution { public: void solve(vector<bool>& value) { value[0]=!value[0]; } };");
        Files.writeString(dir.resolve("runner.cpp"),new CppHarnessGenerator().generate(spec));
        Path exe=dir.resolve("main.exe");
        runCompile(List.of(cpp,"-std=c++17","-O0","-I"+include,dir.resolve("runner.cpp").toString(),"-o",exe.toString()));
        assertEquals("[[false],[false]]",run(List.of(exe.toString()),"{\"value\":[true]}").get("actual").toString());
        spec=spec("list_node<int>");
        Files.writeString(dir.resolve("solution.hpp"),"class Solution { public: ListNode* solve(ListNode* value) { value->next=value; return value; } };");
        Files.writeString(dir.resolve("runner.cpp"),new CppHarnessGenerator().generate(spec));
        runCompile(List.of(cpp,"-std=c++17","-O0","-I"+include,dir.resolve("runner.cpp").toString(),"-o",exe.toString()));
        assertEquals("RUNTIME_ERROR",run(List.of(exe.toString()),"{\"value\":[1]}").get("status").getAsString());
    }
    @Test void cppPublicLongLongArrayReturnKeepsTheInt64WireContract() throws Exception {
        String cpp=System.getenv("NEMO_TEST_CPP"),include=System.getenv("NEMO_TEST_CPP_INCLUDE");
        Assumptions.assumeTrue(cpp!=null && include!=null,"Set NEMO_TEST_CPP and NEMO_TEST_CPP_INCLUDE");
        Files.writeString(dir.resolve("nodes.hpp"),HarnessGeneratorRegistry.resource("nodes.hpp"));
        var problem=spec("int"); problem.setReturnType("long[]");
        Files.writeString(dir.resolve("solution.hpp"),
                "class Solution {public: std::vector<long long> solve(int value){return {9223372036854775807LL, -9223372036854775807LL-1};}};");
        Files.writeString(dir.resolve("runner.cpp"),new CppHarnessGenerator().generate(problem));
        Path exe=dir.resolve("public-long-array.exe");
        runCompile(List.of(cpp,"-std=c++17","-O0","-I"+include,dir.resolve("runner.cpp").toString(),"-o",exe.toString()));
        var result=run(List.of(exe.toString()),"{\"value\":0}");
        assertEquals("OK",result.get("status").getAsString());
        assertEquals(JsonParser.parseString("[9223372036854775807,-9223372036854775808]"),result.get("actual"));
        problem.setReturnType("int[]");
        Files.writeString(dir.resolve("runner.cpp"),new CppHarnessGenerator().generate(problem));
        runCompile(List.of(cpp,"-std=c++17","-O0","-I"+include,dir.resolve("runner.cpp").toString(),"-o",exe.toString()));
        assertEquals("RUNTIME_ERROR",run(List.of(exe.toString()),"{\"value\":0}").get("status").getAsString());
    }

    void runCompile(List<String> command) throws Exception {
        Process process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(dir.resolve("compile.log").toFile()).start();
        try { assertTrue(process.waitFor(60,TimeUnit.SECONDS)); assertEquals(0,process.exitValue(),Files.readString(dir.resolve("compile.log"))); }
        finally {process.destroyForcibly();}
    }
    JsonObject run(List<String> command,String input) throws Exception {
        Process process=new ProcessBuilder(command).redirectError(dir.resolve("stderr.log").toFile()).redirectOutput(dir.resolve("stdout.log").toFile()).start();
        try {
            process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8)); process.getOutputStream().close();
            assertTrue(process.waitFor(10,TimeUnit.SECONDS));
            assertEquals(0,process.exitValue(),Files.readString(dir.resolve("stderr.log")));
            return JsonParser.parseString(Files.readString(dir.resolve("stdout.log"))).getAsJsonObject();
        } finally {process.destroyForcibly();}
    }
}
