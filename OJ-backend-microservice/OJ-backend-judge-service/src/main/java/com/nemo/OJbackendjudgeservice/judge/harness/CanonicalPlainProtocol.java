package com.nemo.OJbackendjudgeservice.judge.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.nemo.OJbackendmodel.model.dto.question.ProblemSpec;
import java.util.*;
import java.util.regex.Pattern;

/** A versioned length-prefixed text codec shared by cases and standalone ACM references. */
public final class CanonicalPlainProtocol {
    public static final String VERSION="CANONICAL_LINES_V1";
    public static final Set<String> TYPES=Set.of("int","long","double","boolean","string","int[]","long[]","double[]","boolean[]","string[]","int[][]","list<int>","list_node<int>","tree_node<int>");
    private CanonicalPlainProtocol(){}
    public static String element(String kind){return kind.endsWith("[]")?kind.substring(0,kind.length()-2):kind.startsWith("list<")?kind.substring(5,kind.length()-1):null;}
    public static String encode(JsonNode value,String kind){
        if(!TYPES.contains(kind))throw new IllegalArgumentException("unsupported canonical type");
        String element=element(kind);boolean nodes=kind.equals("list_node<int>")||kind.equals("tree_node<int>");
        if(element!=null||nodes){
            if(nodes&&value.isNull())return "-1\n";
            if(!value.isArray()||value.size()>10000)throw new IllegalArgumentException("array limit/type");
            StringBuilder out=new StringBuilder(value.size()+"\n");
            for(var v:value)out.append(nodes&&v.isNull()&&kind.startsWith("tree")?"null\n":encode(v,element==null?"int":element));
            return out.toString();
        }
        if(kind.equals("string")){
            if(!value.isTextual()||value.asText().contains("\n")||value.asText().contains("\r"))throw new IllegalArgumentException("single-line string required");
            return value.asText()+"\n";
        }
        if(kind.equals("boolean")){if(!value.isBoolean())throw new IllegalArgumentException("boolean type");return value.asText()+"\n";}
        if(kind.equals("int")||kind.equals("long")){
            if(!value.isIntegralNumber()||!value.canConvertToLong()||(kind.equals("int")&&!value.canConvertToInt()))throw new IllegalArgumentException("integer range/type");
            return value.asText()+"\n";
        }
        if(!value.isNumber()||!Double.isFinite(value.asDouble()))throw new IllegalArgumentException("finite number required");
        return String.format(Locale.ROOT,"%.16e",new java.math.BigDecimal(value.asDouble()))+"\n";
    }
    public static String encodeInput(JsonNode args,ProblemSpec spec){
        com.nemo.OJbackendmodel.model.dto.question.CustomStructureContract.validate(spec);
        Set<String> names=new HashSet<>();args.fieldNames().forEachRemaining(names::add);
        if(!args.isObject()||!names.equals(new HashSet<>(spec.getParameters().stream().map(p->p.getName()).toList())))throw new IllegalArgumentException("parameter object required");
        StringBuilder out=new StringBuilder();for(var p:spec.getParameters())out.append(encode(args.get(p.getName()),p.getType(),spec));return out.toString();
    }
    public static String encode(JsonNode value,String kind,ProblemSpec spec){
        if(!com.nemo.OJbackendmodel.model.dto.question.CustomStructureContract.isNode(kind))return encode(value,kind);
        var d=com.nemo.OJbackendmodel.model.dto.question.CustomStructureContract.definition(spec,kind);
        com.nemo.OJbackendmodel.model.dto.question.CustomStructureContract.graph(com.google.gson.JsonParser.parseString(value.toString()),d);
        if(value.isNull())return "-1\n";StringBuilder out=new StringBuilder(value.path("nodes").size()+"\n"+value.path("root").asInt()+"\n");
        for(var node:value.path("nodes"))for(var f:d.getFields())out.append(encode(node.get(f.getName()),f.getType().equals("ref")?"int":f.getType().equals("ref[]")?"int[]":f.getType()));return out.toString();
    }
    static String replaceRequired(String text,String from,String to){
        if(!text.contains(from))throw new IllegalStateException("trusted harness contract changed");
        return text.replace(from,to);
    }
    public static String wrap(String language,String source,ProblemSpec spec){
        com.nemo.OJbackendmodel.model.dto.question.CustomStructureContract.validate(spec);
        if((!TYPES.contains(spec.getReturnType())&&!com.nemo.OJbackendmodel.model.dto.question.CustomStructureContract.isNode(spec.getReturnType()))||spec.getParameters().stream().anyMatch(p->!TYPES.contains(p.getType())&&!com.nemo.OJbackendmodel.model.dto.question.CustomStructureContract.isNode(p.getType())))throw new IllegalArgumentException("unsupported signature");
        var gson=new com.google.gson.Gson();
        if(language.equals("java")){
            String runner=new JavaHarnessGenerator().generate(spec);
            runner=replaceRequired(runner,"public class Runner {","public class Main {");
            StringBuilder input=new StringBuilder("JsonObject input = new JsonObject();\n        BufferedReader plainReader = new BufferedReader(new InputStreamReader(System.in, \"UTF-8\"));\n");
            for(var p:spec.getParameters())input.append("        input.add(").append(gson.toJson(p.getName())).append(", NemoPlainIO.read(plainReader,").append(gson.toJson(p.getType())).append("));\n");
            runner=replaceRequired(runner,"JsonObject input = gson.fromJson(reader, JsonObject.class);",input.toString());
            runner=replaceRequired(runner,"System.out.println(gson.toJson(result));","if (!\"OK\".equals(result.get(\"status\").getAsString())) throw new RuntimeException(result.toString());\n        System.out.print(NemoPlainIO.format(result.get(\"actual\"),"+gson.toJson(spec.getReturnType())+"));");
            String nodes=HarnessGeneratorRegistry.resource("ListNode.java").replace("public class ListNode","class ListNode")+"\n"+HarnessGeneratorRegistry.resource("TreeNode.java").replace("public class TreeNode","class TreeNode");
            String joined=FunctionCodePrelude.JAVA_IMPORTS.replace("; ",";\n")+runner+"\n"+source.replaceAll("\\bpublic\\s+class\\s+Solution\\b","class Solution")+"\n"+nodes+"\n"+HarnessGeneratorRegistry.resource("plain-io.java");
            if(CustomNodeGenerator.enabled(spec)){
                joined=replaceRequired(joined,"static JsonElement read(BufferedReader reader,String t) throws IOException {","static JsonElement read(BufferedReader reader,String t) throws IOException {\n if(t.startsWith(\"node<\"))return NemoCustomCodec.plainRead(reader,t);");
                joined=replaceRequired(joined,"static String format(JsonElement value,String t) {","static String format(JsonElement value,String t) {\n if(t.startsWith(\"node<\"))return NemoCustomCodec.plainFormat(value,t);");
                joined+="\n"+CustomNodeGenerator.javaSource(spec,true);
            }
            var imports=Pattern.compile("(?m)^[ \\t]*import[^;]+;").matcher(joined);StringBuilder prefix=new StringBuilder();while(imports.find())prefix.append(imports.group()).append('\n');
            return prefix+joined.replaceAll("(?m)^[ \\t]*import[^;]+;","");
        }
        if(language.equals("python")){
            String runner=new PythonHarnessGenerator().generate(spec);
            runner=replaceRequired(runner,"source = json.load(sys.stdin, parse_constant=lambda _: (_ for _ in ()).throw(ValueError('nonfinite JSON')))","source = {p['name']: plain_read(p['type']) for p in spec['parameters']}");
            runner=replaceRequired(runner,"loader = importlib.util.spec_from_file_location('solution', '/app/solution.py')\n    module = importlib.util.module_from_spec(loader)","module = __import__('types').ModuleType('solution')");
            runner=replaceRequired(runner,"loader.loader.exec_module(module)","exec(base64.b64decode('"+Base64.getEncoder().encodeToString(source.getBytes(java.nio.charset.StandardCharsets.UTF_8))+"'), module.__dict__)");
            runner=replaceRequired(runner,"protocol.write(json.dumps({'status':'OK', 'actual':actual, 'timeMs':elapsed}, ensure_ascii=False, allow_nan=False))","protocol.write(plain_format(actual, spec['returnType']))");
            String plain=HarnessGeneratorRegistry.resource("plain-io.py");
            if(CustomNodeGenerator.enabled(spec)){
                plain=replaceRequired(plain,"def plain_read(t):","def plain_read(t):\n    if t.startswith('node<'):\n        n=int(_plain_sys.stdin.readline())\n        if n==-1:return None\n        if not 1<=n<=200:raise ValueError('node limit')\n        root=int(_plain_sys.stdin.readline())\n        fields=CUSTOM_DESCRIPTORS[t[5:-1]]\n        return {'root':root,'nodes':[{f['name']:plain_read('int' if f['type']=='ref' else 'int[]' if f['type']=='ref[]' else f['type']) for f in fields} for _ in range(n)]}");
                plain=replaceRequired(plain,"def plain_format(v, t):","def plain_format(v, t):\n    if t.startswith('node<'):\n        if v is None:return '-1\\n'\n        fields=CUSTOM_DESCRIPTORS[t[5:-1]]\n        return str(len(v['nodes']))+'\\n'+str(v['root'])+'\\n'+''.join(plain_format(node[f['name']], 'int' if f['type']=='ref' else 'int[]' if f['type']=='ref[]' else f['type']) for node in v['nodes'] for f in fields)");
            }return plain+"\n"+runner;
        }
        if(language.equals("cpp")){
            var generator=new CppHarnessGenerator();String runner=generator.generate(spec);
            runner=replaceRequired(runner,"#include \"nodes.hpp\"",HarnessGeneratorRegistry.resource("nodes.hpp").replace("#pragma once",""));
            runner=replaceRequired(runner,"#include \"solution.hpp\"",source);
            StringBuilder input=new StringBuilder("json input=json::object();\n");for(var p:spec.getParameters())input.append("input[").append(gson.toJson(p.getName())).append("]=plain_read(").append(gson.toJson(p.getType())).append(");\n");
            runner=replaceRequired(runner,"json input=json::parse(cin); nemo::require(input.is_object());",input.toString());
            runner=replaceRequired(runner,"wire=response.dump();","if(response.at(\"status\")!=\"OK\") return 1; wire=plain_format(response.at(\"actual\"),"+gson.toJson(spec.getReturnType())+");");
            String plain=HarnessGeneratorRegistry.resource("plain-io.cpp");
            if(CustomNodeGenerator.enabled(spec)){
                plain=replaceRequired(plain,"inline json plain_read(const string& t) {","inline json plain_read(const string& t) {\n if(t.rfind(\"node<\",0)==0){string line;if(!getline(cin,line))throw runtime_error(\"nodes\");int n=stoi(line);if(n==-1)return nullptr;if(n<1||n>200)throw runtime_error(\"nodes\");if(!getline(cin,line))throw runtime_error(\"root\");json g=json::object();g[\"root\"]=stoi(line);json a=json::array();for(int i=0;i<n;i++){json o=json::object();for(const auto& f:nemo_custom_fields.at(t))o[f.first]=plain_read(f.second);a.push_back(o);}g[\"nodes\"]=a;return g;}");
                plain=replaceRequired(plain,"inline string plain_format(const json& v,const string& t) {","inline string plain_format(const json& v,const string& t) {\n if(t.rfind(\"node<\",0)==0){if(v.is_null())return \"-1\\n\";string out=to_string(v.at(\"nodes\").size())+\"\\n\"+to_string(v.at(\"root\").get<int>())+\"\\n\";for(const auto& node:v.at(\"nodes\"))for(const auto& f:nemo_custom_fields.at(t))out+=plain_format(node.at(f.first),f.second);return out;}");
            }runner=replaceRequired(runner,"namespace nemo_protocol {",plain+"\nnamespace nemo_protocol {");
            return runner;
        }
        throw new IllegalArgumentException("unsupported language");
    }
}
