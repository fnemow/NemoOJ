package com.nemo.OJbackendjudgeservice.judge.harness;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.nemo.OJbackendmodel.model.dto.question.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
class CanonicalPlainProtocolTest {
    @TempDir Path dir;
    final ObjectMapper json=new ObjectMapper();
    static final String[][] VALUES={{"int","2147483647"},{"long","9223372036854775807"},{"double","1.25"},{"boolean","true"},{"string","\"[你好 text]\""},{"int[]","[1,-2]"},{"long[]","[9223372036854775807]"},{"double[]","[0.00001,1.5]"},{"boolean[]","[true,false]"},{"string[]","[\"\",\"{}\",\"你好\"]"},{"int[][]","[[],[1,2]]"},{"list<int>","[1,2]"},{"list_node<int>","[1,2]"},{"tree_node<int>","[1,null,2]"}};
    ProblemSpec spec(String type){return new ProblemSpec("function","Solution","solve",List.of(new ParameterSpec("value",type)),type,"return",new ComparatorSpec("EXACT",null),1);}
    @Test void pythonRunnerCannotReadHiddenAnswersFromItsEncodedSignature() throws Exception {
        var s=spec("int");var hidden=new JudgeCase();hidden.setInput("{\"value\":7}");hidden.setOutput("123456789");hidden.setIsHidden(true);s.setJudgeCases(List.of(hidden));
        String runner=new PythonHarnessGenerator().generate(s);
        var match=java.util.regex.Pattern.compile("b64decode\\('([^']+)'\\)").matcher(runner);assertTrue(match.find());
        var visible=json.readTree(Base64.getDecoder().decode(match.group(1)));
        assertFalse(visible.has("judgeCases"));assertEquals("solve",visible.path("methodName").asText());
        assertEquals("123456789",s.getJudgeCases().get(0).getOutput());
    }
    @Test void canonicalEncodingRejectsTypeAndOverflowAndHandlesEmptyNodes() throws Exception {
        assertThrows(IllegalArgumentException.class,()->CanonicalPlainProtocol.encode(json.readTree("true"),"int"));
        assertThrows(IllegalArgumentException.class,()->CanonicalPlainProtocol.encode(json.readTree("2147483648"),"int"));
        assertThrows(IllegalArgumentException.class,()->CanonicalPlainProtocol.encode(json.readTree("9223372036854775808"),"long"));
        assertThrows(IllegalArgumentException.class,()->CanonicalPlainProtocol.encode(json.readTree("\"a\\nb\""),"string"));
        assertEquals("-1\n",CanonicalPlainProtocol.encode(json.readTree("null"),"tree_node<int>"));
        assertEquals("2\n0\n2\n1\n2\n",CanonicalPlainProtocol.encode(json.readTree("[[],[1,2]]"),"int[][]"));
    }
    @Test void standaloneJavaWrappersCompileUnderJava8AndRoundTripEverySupportedType() throws Exception {
        var compiler=javax.tools.ToolProvider.getSystemJavaCompiler();assertNotNull(compiler);
        String gson=Path.of(Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        for(var item:VALUES){
            String type=JavaHarnessGenerator.javaType(item[0]);
            String source="import java.util.*;\nclass Solution { public "+type+" solve("+type+" value){System.out.println(\"debug\");return value;} }";
            Path file=dir.resolve("Main.java");Files.writeString(file,CanonicalPlainProtocol.wrap("java",source,spec(item[0])));
            var diagnostics=new java.io.ByteArrayOutputStream();
            assertEquals(0,compiler.run(null,diagnostics,diagnostics,"--release","8","-encoding","UTF-8","-cp",gson,"-d",dir.toString(),file.toString()),diagnostics.toString(StandardCharsets.UTF_8));
            String expected=CanonicalPlainProtocol.encode(json.readTree(item[1]),item[0]);
            assertEquals(expected,run(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Dfile.encoding=UTF-8","-Dstdout.encoding=UTF-8","-cp",dir+java.io.File.pathSeparator+gson,"Main"),expected),item[0]);
        }
    }
    @Test void standalonePythonWrappersRoundTripEverySupportedType() throws Exception {
        String python=System.getenv("NEMO_TEST_PYTHON");Assumptions.assumeTrue(python!=null);
        for(var item:VALUES){
            Path file=dir.resolve("main.py");Files.writeString(file,CanonicalPlainProtocol.wrap("python","class Solution:\n def solve(self,value):\n  print('debug')\n  return value\n",spec(item[0])));
            String expected=CanonicalPlainProtocol.encode(json.readTree(item[1]),item[0]);
            assertEquals(expected,run(List.of(python,"-I","-B","-X","utf8",file.toString()),expected),item[0]);
        }
    }
    @Test void cppWrapperIsStandaloneAndPreservesTrustedStdoutIsolation(){
        var code=CanonicalPlainProtocol.wrap("cpp","class Solution {public:int solve(int value){return value;}};",spec("int"));
        assertFalse(code.contains("#include \"solution.hpp\""));assertFalse(code.contains("#include \"nodes.hpp\""));
        assertTrue(code.contains("nemo_protocol::fd"));assertTrue(code.contains("plain_read(\"int\")"));
    }
    @Test void exportAuthoredCppFixturesForRealIsolatedRuntimeVerification() throws Exception {
        String path=System.getenv("NEMO_CANONICAL_FIXTURES");if(path==null)return;
        List<Map<String,Object>> cases=new ArrayList<>();
        for(var item:VALUES){
            String type=CppHarnessGenerator.cppType(item[0]);String source="class Solution {public: "+type+" solve("+type+" value){cout<<\"debug\";return value;}};";
            String wire=CanonicalPlainProtocol.encode(json.readTree(item[1]),item[0]);
            cases.add(Map.of("type",item[0],"code",CanonicalPlainProtocol.wrap("cpp",source,spec(item[0])),"input",wire,"expected",wire));
        }
        Files.writeString(Path.of(path),json.writeValueAsString(cases));
    }
    String run(List<String> args,String input) throws Exception {
        var p=new ProcessBuilder(args).redirectOutput(dir.resolve("stdout").toFile()).redirectError(dir.resolve("stderr").toFile()).start();
        try{p.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));p.getOutputStream().close();assertTrue(p.waitFor(10,TimeUnit.SECONDS));assertEquals(0,p.exitValue(),Files.readString(dir.resolve("stderr")));return Files.readString(dir.resolve("stdout")).replace("\r\n","\n");}finally{p.destroyForcibly();}
    }
}
