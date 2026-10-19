package com.nemo.OJbackendjudgeservice.judge.harness;
import com.nemo.OJbackendmodel.model.dto.question.*;
import java.util.*;
/** Emits only trusted codec code and validated identifiers; descriptor contains no executable code. */
public final class CustomNodeGenerator {
    private CustomNodeGenerator(){}
    public static boolean enabled(ProblemSpec s){CustomStructureContract.validate(s);return !CustomStructureContract.structures(s).isEmpty();}
    private static String javaField(ParameterSpec f,String name){return switch(f.getType()){case "ref"->name;case "ref[]"->"java.util.List<"+name+">";default->JavaHarnessGenerator.javaType(f.getType());};}
    private static String cppField(ParameterSpec f,String name){return switch(f.getType()){case "ref"->name+"*";case "ref[]"->"std::vector<"+name+"*>";default->CppHarnessGenerator.cppType(f.getType());};}
    public static String javaSource(ProblemSpec s){return javaSource(s,false);}
    public static String javaSource(ProblemSpec s,boolean plain){
        CustomStructureContract.validate(s);StringBuilder code=new StringBuilder("import com.google.gson.*;\nimport java.util.*;\n");
        for(var d:CustomStructureContract.structures(s)){
            String n=d.getName();code.append("class ").append(n).append(" {\n");
            for(var f:d.getFields())code.append("public ").append(javaField(f,n)).append(' ').append(f.getName()).append(f.getType().equals("ref[]")?"=new ArrayList<>();\n":f.getType().equals("string")?"=\"\";\n":";\n");
            code.append("public ").append(n).append("(){}\npublic ").append(n).append('(').append(String.join(",",d.getFields().stream().map(f->javaField(f,n)+" "+f.getName()).toList())).append("){\n");
            for(var f:d.getFields())code.append("this.").append(f.getName()).append('=').append(f.getName()).append(';');code.append("}\n}\n");
        }
        code.append(HarnessGeneratorRegistry.resource("custom-codec.java"));
        StringBuilder reads=new StringBuilder(),writes=new StringBuilder(),methods=new StringBuilder();
        for(var d:CustomStructureContract.structures(s)){
            String n=d.getName();reads.append("if(type.equals(\"node<").append(n).append(">\"))return read_").append(n).append("(v);\n");
            writes.append("if(type.equals(\"node<").append(n).append(">\"))return write_").append(n).append("((").append(n).append(")v);\n");
            methods.append("static ").append(n).append(" read_").append(n).append("(JsonElement v){if(v.isJsonNull())return null;JsonObject g=graph(v);JsonArray a=g.getAsJsonArray(\"nodes\");int r=index(g.get(\"root\"),a.size(),false);").append(n).append("[] ns=new ").append(n).append("[a.size()];for(int i=0;i<ns.length;i++)ns[i]=new ").append(n).append("();for(int i=0;i<ns.length;i++){JsonObject o=a.get(i).getAsJsonObject();check(o,").append(d.getFields().size()).append(");");
            for(var f:d.getFields()){
                String access="ns[i]."+f.getName(),value="o.get(\""+f.getName()+"\")",t=f.getType();
                if(t.equals("ref"))methods.append("{int k=index(").append(value).append(",ns.length,true);").append(access).append("=k<0?null:ns[k];}");
                else if(t.equals("ref[]"))methods.append("for(JsonElement e:refs(").append(value).append(")){int k=index(e,ns.length,true);").append(access).append(".add(k<0?null:ns[k]);}");
                else methods.append(access).append("=(").append(javaField(f,n)).append(")scalar(").append(value).append(",\"").append(t).append("\");");
            }
            methods.append("}if(write_").append(n).append("(ns[r]).getAsJsonObject().getAsJsonArray(\"nodes\").size()!=ns.length)throw new IllegalArgumentException(\"unreachable node\");return ns[r];}\n");
            methods.append("static JsonElement write_").append(n).append('(').append(n).append(" root){if(root==null)return JsonNull.INSTANCE;IdentityHashMap<Object,Integer> ids=new IdentityHashMap<>();ArrayList<Object> queue=new ArrayList<>();discover(root,ids,queue);JsonArray a=new JsonArray();for(int i=0;i<queue.size();i++){").append(n).append(" x=(").append(n).append(")queue.get(i);JsonObject o=new JsonObject();");
            for(var f:d.getFields()){
                String key="\""+f.getName()+"\"",access="x."+f.getName();
                if(f.getType().equals("ref"))methods.append("o.addProperty(").append(key).append(",discover(").append(access).append(",ids,queue));");
                else if(f.getType().equals("ref[]"))methods.append("{if(").append(access).append("==null||").append(access).append(".size()>32)throw new IllegalArgumentException(\"refs\");JsonArray refs=new JsonArray();for(Object ref:").append(access).append(")refs.add(discover(ref,ids,queue));o.add(").append(key).append(",refs);}");
                else methods.append("o.add(").append(key).append(",scalarJson(").append(access).append(",\"").append(f.getType()).append("\"));");
            }
            methods.append("a.add(o);}JsonObject out=new JsonObject();out.addProperty(\"root\",0);out.add(\"nodes\",a);return out;}\n");
        }
        if(plain){
        methods.append("static JsonElement plainRead(java.io.BufferedReader reader,String type)throws java.io.IOException{int count=Integer.parseInt(reader.readLine());if(count==-1)return JsonNull.INSTANCE;if(count<1||count>200)throw new IllegalArgumentException(\"nodes\");JsonObject g=new JsonObject();g.addProperty(\"root\",Integer.parseInt(reader.readLine()));JsonArray a=new JsonArray();for(int i=0;i<count;i++){JsonObject o=new JsonObject();");
        for(var d:CustomStructureContract.structures(s)){methods.append("if(type.equals(\"node<").append(d.getName()).append(">\")){");for(var f:d.getFields())methods.append("o.add(\"").append(f.getName()).append("\",NemoPlainIO.read(reader,\"").append(f.getType().equals("ref")?"int":f.getType().equals("ref[]")?"int[]":f.getType()).append("\"));");methods.append('}');}
        methods.append("a.add(o);}g.add(\"nodes\",a);return g;}\nstatic String plainFormat(JsonElement v,String type){if(v.isJsonNull())return \"-1\\n\";JsonObject g=graph(v);JsonArray a=g.getAsJsonArray(\"nodes\");StringBuilder out=new StringBuilder(a.size()+\"\\n\"+g.get(\"root\").getAsInt()+\"\\n\");for(JsonElement node:a){JsonObject o=node.getAsJsonObject();");
        for(var d:CustomStructureContract.structures(s)){methods.append("if(type.equals(\"node<").append(d.getName()).append(">\")){");for(var f:d.getFields())methods.append("out.append(NemoPlainIO.format(o.get(\"").append(f.getName()).append("\"),\"").append(f.getType().equals("ref")?"int":f.getType().equals("ref[]")?"int[]":f.getType()).append("\"));");methods.append('}');}
        methods.append("}return out.toString();}\n");
        }
        return code.toString().replace("__READS__",reads).replace("__WRITES__",writes).replace("__METHODS__",methods);
    }
    public static String pythonSource(ProblemSpec s){
        CustomStructureContract.validate(s);
        String encoded=Base64.getEncoder().encodeToString(new com.google.gson.Gson().toJson(s.getDataStructures()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return HarnessGeneratorRegistry.resource("custom-codec.py").replace("__STRUCTURES__",encoded);
    }
    public static String cppDefinitions(ProblemSpec s){
        CustomStructureContract.validate(s);StringBuilder out=new StringBuilder();
        for(var d:CustomStructureContract.structures(s)){
            String n=d.getName();out.append("struct ").append(n).append("{\n");
            for(var f:d.getFields())out.append(cppField(f,n)).append(' ').append(f.getName()).append("{};\n");
            out.append(n).append("()=default;\n").append(n).append('(').append(String.join(",",d.getFields().stream().map(f->cppField(f,n)+" "+f.getName()).toList())).append("):").append(String.join(",",d.getFields().stream().map(f->f.getName()+"("+f.getName()+")").toList())).append("{}\n};\n");
        }
        out.append("static const std::map<std::string,std::vector<std::pair<std::string,std::string>>> nemo_custom_fields={");
        for(var d:CustomStructureContract.structures(s))out.append("{\"node<").append(d.getName()).append(">\",{").append(String.join(",",d.getFields().stream().map(f->"{\""+f.getName()+"\",\""+(f.getType().equals("ref")?"int":f.getType().equals("ref[]")?"int[]":f.getType())+"\"}").toList())).append("}},");
        return out.append("};\n").toString();
    }
    public static String cppCodecs(ProblemSpec s){
        StringBuilder out=new StringBuilder("namespace nemo {\n");
        for(var d:CustomStructureContract.structures(s)){
            String n=d.getName();out.append("template<>struct Codec<").append(n).append("*>{\nstatic json write(").append(n).append("* root){if(!root)return nullptr;unordered_map<").append(n).append("*,int> ids;vector<").append(n).append("*> queue;auto discover=[&](").append(n).append("* x){if(!x)return -1;auto it=ids.find(x);if(it!=ids.end())return it->second;require(queue.size()<200);int k=queue.size();ids[x]=k;queue.push_back(x);return k;};discover(root);json a=json::array();for(size_t i=0;i<queue.size();i++){auto x=queue[i];json o={");
            for(var f:d.getFields()){
                String access="x->"+f.getName(),t=f.getType();out.append("{\"").append(f.getName()).append("\",");
                if(t.equals("ref"))out.append("discover(").append(access).append(")");
                else if(t.equals("ref[]"))out.append("[&](){require(").append(access).append(".size()<=32);json refs=json::array();for(auto ref:").append(access).append(")refs.push_back(discover(ref));return refs;}()");
                else {if(t.equals("string"))out.append("[&](){require(").append(access).append(".size()<=800);return ");out.append("Codec<").append(cppField(f,n)).append(">::write(").append(access).append(")");if(t.equals("string"))out.append(";}()");}
                out.append("},");
            }
            out.append("};a.push_back(o);}return json{{\"root\",0},{\"nodes\",a}};}\nstatic ").append(n).append("* read(const json& g){if(g.is_null())return nullptr;require(g.is_object()&&g.size()==2);const auto& a=g.at(\"nodes\");require(a.is_array()&&a.size()>0&&a.size()<=200);int r=Codec<int>::read(g.at(\"root\"));require(r>=0&&r<(int)a.size());vector<").append(n).append("*> ns;for(size_t i=0;i<a.size();i++)ns.push_back(new ").append(n).append("());auto ref=[&](const json& v){int k=Codec<int>::read(v);require(k>=-1&&k<(int)ns.size());return k<0?nullptr:ns[k];};for(size_t i=0;i<a.size();i++){const auto& o=a[i];require(o.is_object()&&o.size()==").append(d.getFields().size()).append(");");
            for(var f:d.getFields()){
                String key="o.at(\""+f.getName()+"\")",access="ns[i]->"+f.getName(),t=f.getType();
                if(t.equals("ref"))out.append(access).append("=ref(").append(key).append(");");
                else if(t.equals("ref[]"))out.append("{const auto& refs=").append(key).append(";require(refs.is_array()&&refs.size()<=32);for(const auto& v:refs)").append(access).append(".push_back(ref(v));}");
                else {out.append(access).append("=Codec<").append(cppField(f,n)).append(">::read(").append(key).append(");");if(t.equals("string"))out.append("require(").append(access).append(".size()<=800);");}
            }
            out.append("}require(write(ns[r]).at(\"nodes\").size()==ns.size());return ns[r];}\n};\n");
        }return out.append("}\n").toString();
    }
}
