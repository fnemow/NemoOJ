package com.nemo.OJbackendjudgeservice.judge.harness;

import com.google.gson.Gson;
import com.nemo.OJbackendjudgeservice.judge.mode.FunctionJudgeModeAdapter;
import com.nemo.OJbackendmodel.model.dto.question.*;
import com.nemo.OJbackendmodel.model.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Export actual adapter execution plans for opt-in native sandbox acceptance. */
class FunctionPreludeAcceptanceTest {
    @Test void buildsImportFreePlansAndOptionallyExportsNativeFixtures() throws Exception {
        String java="class Solution {\n public int[] solve(int[] value) {\n"
            +"List<Integer> xs=new ArrayList<>();for(int x:value)xs.add(x);\n"
            +"Deque<Integer> q=new ArrayDeque<>(xs);PriorityQueue<Integer> h=new PriorityQueue<>(q);\n"
            +"Map<String,Integer> m=new HashMap<>();m.put(\"a\",h.peek());\n"
            +"BigInteger b=BigInteger.valueOf(m.get(\"a\"));Pattern p=Pattern.compile(\"1\");\n"
            +"Function<Integer,Integer> f=x->x;StringReader r=new StringReader(\"x\");\n"
            +"if(b.intValue()!=1||!p.matcher(\"1\").matches())throw new AssertionError();\n"
            +"return xs.stream().sorted().mapToInt(f::apply).toArray();\n }\n}\n";
        String python="class Solution:\n def solve(self,value: List[int]) -> List[int]:\n"
            +"  q=deque(value);c=Counter(q);h=list(q);heapify(h)\n"
            +"  @cache\n  def identity(x): return x\n"
            +"  assert gcd(6,4)==2 and math.isqrt(9)==3 and c[3]==2\n"
            +"  assert bisect_left(sorted(h),1)==0 and list(accumulate([1,2]))==[1,3]\n"
            +"  assert re.match('a','abc') and isinstance(None,type(None))\n"
            +"  node: Optional[ListNode]=None;tree: Optional[TreeNode]=None\n"
            +"  return sorted(identity(x) for x in q)\n";
        String cpp="class Solution {public: vector<int> solve(vector<int>& value) {"
            +"array<int,2> a{1,2};bitset<4> b(3);map<int,int> m;unordered_map<int,int> u;"
            +"set<int> s;multiset<int> ms;stack<int> st;deque<int> d;list<int> li;"
            +"priority_queue<int,vector<int>,greater<int>> q;function<int(int)> f=[](int x){return x;};"
            +"tuple<int,int> t{1,2};stringstream text;text<<a[0];auto p=make_unique<int>(1);"
            +"for(int x:value){m[x]++;u[x]++;s.insert(x);ms.insert(x);st.push(x);d.push_back(x);li.push_back(x);q.push(x);}"
            +"assert(gcd(6,4)==2&&accumulate(value.begin(),value.end(),0)==7&&q.top()==1&&b.count()==2);"
            +"sort(value.begin(),value.end());return value;}};";
        List<Map<String,Object>> fixtures=new ArrayList<>();
        add(fixtures,"java",java,false,"OK",null);
        add(fixtures,"java","import java.util.ArrayList;\n"+java,false,"OK",null);
        add(fixtures,"python",python,false,"OK",null);
        add(fixtures,"python","from __future__ import annotations\nfrom collections import deque\n"+python,false,"OK",null);
        for(boolean split:List.of(false,true)) {
            add(fixtures,"cpp",cpp,split,"OK",null);
            add(fixtures,"cpp","#include <vector>\nusing namespace std;\n"+cpp,split,"OK",null);
        }
        add(fixtures,"java","class Solution { public int[] solve(int[] value) { return missingName; } }",false,"COMPILE_ERROR","Solution.java:1:");
        add(fixtures,"java","class Solution {\n public int[] solve(int[] value) {\n return missingName;\n }\n}",false,"COMPILE_ERROR","Solution.java:3:");
        add(fixtures,"java","class Solution {\n public int[] solve(int[] value) {\n throw new IllegalStateException(\"expected\");\n }\n}",false,"RUNTIME_ERROR","Solution.java:3");
        add(fixtures,"python","class Solution:\n def solve(self,value):\n  raise ValueError('expected')\n",false,"RUNTIME_ERROR","line 3");
        assertEquals(12,fixtures.size());
        String export=System.getenv("NEMO_PRELUDE_FIXTURES");
        if(export!=null)Files.writeString(Path.of(export),new Gson().toJson(fixtures));
    }

    void add(List<Map<String,Object>> fixtures,String language,String code,boolean split,String status,String location) {
        var spec=new ProblemSpec("function","Solution","solve",List.of(new ParameterSpec("value","int[]")),"int[]","return",null,1);
        var c=new JudgeCase();c.setInput("{\"value\":[3,1,3]}");c.setOutput("[1,3,3]");spec.setJudgeCases(List.of(c));
        var q=new Question();q.setProblemSpec(spec);
        var config=new JudgeConfig();config.setTimeLimit(2000L);config.setMemoryLimit(262144L);config.setOutputLimitBytes(1048576L);q.setJudgeConfig(config);
        var cpp=new CppHarnessGenerator();ReflectionTestUtils.setField(cpp,"splitProtocolEnabled",split);
        var adapter=new FunctionJudgeModeAdapter();ReflectionTestUtils.setField(adapter,"harnessRegistry",new HarnessGeneratorRegistry(List.of(new JavaHarnessGenerator(),new PythonHarnessGenerator(),cpp)));
        var submission=new QuestionSubmit();submission.setLanguage(language);submission.setCode(code);
        var plan=adapter.buildExecutionPlan(q,submission);
        assertEquals(code,submission.getCode()); // Never mutate stored user code.
        String staged=plan.getSourceFiles().get(0).getContent();
        if(language.equals("java"))assertEquals(FunctionCodePrelude.JAVA_IMPORTS+code,staged);
        else assertEquals(code,staged);
        var fixture=new LinkedHashMap<String,Object>();fixture.put("language",language);fixture.put("split",split);fixture.put("status",status);
        fixture.put("executionPlan",plan);if(location!=null)fixture.put("location",location);fixtures.add(fixture);
    }
}
