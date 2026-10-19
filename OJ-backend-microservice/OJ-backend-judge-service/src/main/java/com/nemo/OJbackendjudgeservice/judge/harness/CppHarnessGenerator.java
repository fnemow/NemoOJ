package com.nemo.OJbackendjudgeservice.judge.harness;
import com.google.gson.Gson;
import com.nemo.OJbackendmodel.model.dto.question.ProblemSpec;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.util.*;
import java.util.stream.Collectors;
@Component
public class CppHarnessGenerator implements HarnessGenerator {
    @Value("${JUDGE_CPP_SPLIT_PROTOCOL_ENABLED:false}")
    private boolean splitProtocolEnabled;
    public String language() { return "cpp"; }
    public static String cppType(String type) {
        if(com.nemo.OJbackendmodel.model.dto.question.CustomStructureContract.isNode(type))return com.nemo.OJbackendmodel.model.dto.question.CustomStructureContract.name(type)+"*";
        if (type.endsWith("[]")) return "std::vector<" + cppType(type.substring(0,type.length()-2)) + ">";
        return switch(type) {
            case "int" -> "int32_t"; case "long" -> "int64_t"; case "double" -> "double";
            case "boolean" -> "bool"; case "string" -> "std::string";
            case "list<int>" -> "std::vector<int32_t>";
            case "list_node<int>" -> "ListNode*"; case "tree_node<int>" -> "TreeNode*";
            case "void" -> "void"; default -> throw new IllegalArgumentException("Unsupported type: " + type);
        };
    }
    public String generate(ProblemSpec spec) {
        if (!spec.getMethodName().matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid method");
        StringBuilder body = new StringBuilder();
        List<String> names = new ArrayList<>();
        for(int i=0;i<spec.getParameters().size();i++) {
            var parameter=spec.getParameters().get(i); String name="arg"+i; names.add(name);
            body.append("        auto ").append(name).append(" = nemo::Codec<").append(cppType(parameter.getType()))
                .append(">::read(input.at(").append(new Gson().toJson(parameter.getName())).append("));\n");
        }
        body.append("        auto started=chrono::steady_clock::now();\n        ");
        if (!"void".equals(spec.getReturnType())) body.append("auto result = ");
        body.append("solution.").append(spec.getMethodName()).append("(").append(String.join(",",names)).append(");\n");
        if (java.util.Set.of("int", "long", "double", "boolean", "string").contains(spec.getReturnType())) {
            String value="result";
            if(java.util.Set.of("int","long").contains(spec.getReturnType())) {
                body.append("        static_assert(std::is_integral<decltype(result)>::value && !std::is_same<decltype(result),bool>::value, \"integer return required\");\n");
                value="static_cast<int64_t>(result)";
            }
            body.append("        nemo::Codec<").append(cppType(spec.getReturnType())).append(">::read(json(").append(value).append("));\n");
        }
        body.append("        auto elapsed=chrono::duration_cast<chrono::milliseconds>(chrono::steady_clock::now()-started).count();\n");
        String origin=spec.getResultSource(); String actual;
        if (origin == null || "return".equals(origin)) actual="void".equals(spec.getReturnType()) ? "json(nullptr)" : "nemo::Codec<"+cppType(spec.getReturnType())+">::write(result)";
        else if(origin.startsWith("parameter:")) actual=serializeArg(spec,Integer.parseInt(origin.substring(10)));
        else if(origin.startsWith("parameters:")) {
            Integer[] indexes=new Gson().fromJson(origin.substring(11),Integer[].class);
            actual="json::array({"+Arrays.stream(indexes).map(i->serializeArg(spec,i)).collect(Collectors.joining(","))+"})";
        } else throw new IllegalArgumentException("Invalid resultSource");
        body.append("        response={{\"status\",\"OK\"},{\"actual\",").append(actual).append("},{\"timeMs\",elapsed}};\n");
        String runner = splitProtocolEnabled ? "runner-split.cpp" : "runner.cpp";
        String code=HarnessGeneratorRegistry.resource(runner).replace("__BODY__",body.toString());
        if(CustomNodeGenerator.enabled(spec))code=code.replace("#include \"nodes.hpp\"","#include \"nodes.hpp\"\n"+CustomNodeGenerator.cppDefinitions(spec)).replace("int main() {",CustomNodeGenerator.cppCodecs(spec)+"\nint main() {");
        return code;
    }
    private String serializeArg(ProblemSpec spec,int i) {
        return "nemo::Codec<"+cppType(spec.getParameters().get(i).getType())+">::write(arg"+i+")";
    }
}
