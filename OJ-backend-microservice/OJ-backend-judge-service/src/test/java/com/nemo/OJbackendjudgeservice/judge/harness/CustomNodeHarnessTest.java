package com.nemo.OJbackendjudgeservice.judge.harness;
import com.nemo.OJbackendmodel.model.dto.question.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
public class CustomNodeHarnessTest {
    public static ProblemSpec spec(){
        var s=new ProblemSpec("function","Solution","roundTrip",List.of(new ParameterSpec("start","node<ColoredVertex>")),"node<ColoredVertex>","return",new ComparatorSpec("EXACT",null),1);
        var d=new CustomStructureSpec();d.setName("ColoredVertex");d.setFields(List.of(new ParameterSpec("value","int"),new ParameterSpec("color","boolean"),new ParameterSpec("label","string"),new ParameterSpec("weight","long"),new ParameterSpec("cost","double"),new ParameterSpec("left","ref"),new ParameterSpec("right","ref"),new ParameterSpec("parent","ref"),new ParameterSpec("edges","ref[]")));s.setDataStructures(List.of(d));return s;
    }
    @Test void rejectsInvalidIdentifiersUndeclaredTypesAndDanglingReferences(){
        var s=spec();CustomStructureContract.validate(s);s.getDataStructures().get(0).setName("Solution");var badName=s;assertThrows(IllegalArgumentException.class,()->CustomStructureContract.validate(badName));
        s=spec();s.getDataStructures().get(0).getFields().get(0).setName("class");var invalid=s;assertThrows(IllegalArgumentException.class,()->CustomStructureContract.validate(invalid));
        s=spec();s.setDataStructures(List.of());var undeclared=s;assertThrows(IllegalArgumentException.class,()->CustomStructureContract.validate(undeclared));
        assertFalse(FunctionResultValidator.valid(spec(),"{\"root\":0,\"nodes\":[]}"));assertFalse(FunctionResultValidator.valid(spec(),"[]"));
    }
    @Test void generatesCompilablePlansWithoutExposingExpectedAnswers()throws Exception {
        var s=spec();String input="{\"start\":{\"root\":0,\"nodes\":[{\"value\":5,\"color\":false,\"label\":\"中文/<>\",\"weight\":9223372036854775807,\"cost\":1.5,\"left\":1,\"right\":1,\"parent\":-1,\"edges\":[0,1,-1]},{\"value\":7,\"color\":true,\"label\":\"leaf\",\"weight\":-9223372036854775808,\"cost\":-4.25,\"left\":-1,\"right\":-1,\"parent\":0,\"edges\":[0]}]}}";
        var graph=JsonParser.parseString(input).getAsJsonObject().get("start");CustomStructureContract.graph(graph,s.getDataStructures().get(0));
        var expected=graph.deepCopy();for(var node:expected.getAsJsonObject().getAsJsonArray("nodes"))node.getAsJsonObject().addProperty("value",node.getAsJsonObject().get("value").getAsInt()+1);
        assertTrue(FunctionResultValidator.valid(s,expected.toString()));
        var c=new JudgeCase();c.setInput(input);c.setOutput("SECRET_EXPECTED_NEVER_EMBED");s.setJudgeCases(List.of(c));
        String java="class Solution {public ColoredVertex roundTrip(ColoredVertex start){if(start==null)return null;java.util.Set<ColoredVertex> seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<ColoredVertex,Boolean>());java.util.ArrayList<ColoredVertex> q=new java.util.ArrayList<>();q.add(start);for(int i=0;i<q.size();i++){ColoredVertex n=q.get(i);if(n==null||!seen.add(n))continue;n.value++;q.add(n.left);q.add(n.right);q.add(n.parent);q.addAll(n.edges);}return start;}}";
        String python="class Solution:\n def roundTrip(self,start:ColoredVertex):\n  q=[start];seen=set()\n  for n in q:\n   if n is None or id(n) in seen:continue\n   seen.add(id(n));n.value+=1;q.extend([n.left,n.right,n.parent]+n.edges)\n  return start\n";
        String cpp="class Solution {public:ColoredVertex* roundTrip(ColoredVertex* start){vector<ColoredVertex*> q{start};unordered_set<ColoredVertex*> seen;for(size_t i=0;i<q.size();i++){auto n=q[i];if(!n||!seen.insert(n).second)continue;n->value++;q.push_back(n->left);q.push_back(n->right);q.push_back(n->parent);for(auto e:n->edges)q.push_back(e);}return start;}};";
        Map<String,String> cores=Map.of("java",java,"python",python,"cpp",cpp);List<Map<String,Object>> programs=new ArrayList<>();
        for(String lang:List.of("java","python","cpp")){
            String runner=lang.equals("java")?new JavaHarnessGenerator().generate(s):lang.equals("python")?new PythonHarnessGenerator().generate(s):new CppHarnessGenerator().generate(s);
            assertFalse(runner.contains("SECRET_EXPECTED"));assertTrue(runner.contains(lang.equals("python")?"CUSTOM_NODE_CLASSES":"ColoredVertex"));
            Map<String,String> files=new LinkedHashMap<>();
            if(lang.equals("java")){files.put("Solution.java",cores.get(lang));files.put("Runner.java",runner+"\n"+CustomNodeGenerator.javaSource(s).replaceAll("(?m)^import[^;]+;\\s*",""));files.put("ListNode.java",HarnessGeneratorRegistry.resource("ListNode.java"));files.put("TreeNode.java",HarnessGeneratorRegistry.resource("TreeNode.java"));}
            else if(lang.equals("python")){files.put("solution.py",cores.get(lang));files.put("runner.py",runner);}
            else {files.put("solution.hpp",cores.get(lang));files.put("runner.cpp",runner);files.put("nodes.hpp",HarnessGeneratorRegistry.resource("nodes.hpp"));}
            var mapper=new com.fasterxml.jackson.databind.ObjectMapper();var args=mapper.readTree(input);
            String stdin=CanonicalPlainProtocol.encodeInput(args,s),stdout=CanonicalPlainProtocol.encode(mapper.readTree(expected.toString()),s.getReturnType(),s);
            programs.add(Map.of("language",lang,"mode","FUNCTION","files",files,"stdin",input,"expected",expected.toString()));
            String plain=CanonicalPlainProtocol.wrap(lang,cores.get(lang),s);assertFalse(plain.contains("SECRET_EXPECTED"));programs.add(Map.of("language",lang,"mode","ACM","files",Map.of(lang.equals("java")?"Main.java":lang.equals("python")?"main.py":"main.cpp",plain),"stdin",stdin,"expected",stdout));
            if(lang.equals("cpp")){var g=new CppHarnessGenerator();org.springframework.test.util.ReflectionTestUtils.setField(g,"splitProtocolEnabled",true);Map<String,String> split=new LinkedHashMap<>(files);split.put("runner.cpp",g.generate(s));programs.add(Map.of("language",lang,"mode","FUNCTION_SPLIT","files",split,"stdin",input,"expected",expected.toString()));}
        }
        String path=System.getProperty("custom.fixture.dir");if(path!=null){Path out=Path.of(path);Files.createDirectories(out);Files.writeString(out.resolve("programs.json"),new Gson().toJson(programs));Files.writeString(out.resolve("spec.json"),new Gson().toJson(s));Files.writeString(out.resolve("cores.json"),new Gson().toJson(cores));}
    }
}
