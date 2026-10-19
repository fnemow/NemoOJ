package com.nemo.OJbackendmodel.model.dto.question;
import com.google.gson.*;
import java.util.*;
import java.math.BigInteger;
public final class CustomStructureContract {
    private CustomStructureContract(){}
    public static final int MAX_NODES=200,MAX_REFS=32;
    private static final Set<String> FIELDS=Set.of("int","long","double","boolean","string","ref","ref[]");
    private static final Set<String> RESERVED=new HashSet<>(Arrays.asList(("JsonObject JsonArray JsonElement JsonNull JsonPrimitive JsonParser Gson ArrayList IdentityHashMap ArrayDeque HashMap HashSet Arrays Collections NemoPlainIO Codec Result base64 json math custom_class custom_convert CUSTOM_DESCRIPTORS class public private protected static abstract return int long double boolean string bool new null true false import package using namespace std main Main Solution Runner ListNode TreeNode NemoCustomCodec NemoCustomNodes Object String System Math List Map Set type super self def lambda pass from None True False __dict__ constructor delete operator virtual template struct union enum break continue for while try catch throw throws finally switch case default void const volatile inline auto register extern signed unsigned if else do instanceof extends implements interface final synchronized native transient assert yield global nonlocal async await with as in is not and or raise except print fields read write scalar discover queue ids nodes root json result input protocol module loader spec convert CUSTOM_NODE_CLASSES" ).split(" ")));
    public static boolean isNode(String type){return type!=null&&type.matches("node<[A-Z][A-Za-z0-9]{0,39}>");}
    public static String name(String type){if(!isNode(type))throw new IllegalArgumentException("Invalid custom node type");return type.substring(5,type.length()-1);}
    public static boolean identifier(String name){return name!=null&&name.matches("[A-Za-z][A-Za-z0-9_]{0,39}")&&!RESERVED.contains(name)&&!name.startsWith("nemo");}
    public static List<CustomStructureSpec> structures(ProblemSpec spec){return spec.getDataStructures()==null?List.of():spec.getDataStructures();}
    public static CustomStructureSpec definition(ProblemSpec spec,String type){String name=name(type);return structures(spec).stream().filter(d->name.equals(d.getName())).findFirst().orElseThrow(()->new IllegalArgumentException("Undeclared node type"));}
    public static void validate(ProblemSpec spec){
        if(spec==null)throw new IllegalArgumentException("Missing function specification");
        if(structures(spec).size()>4)throw new IllegalArgumentException("At most four custom structures");Set<String> names=new HashSet<>();
        for(var d:structures(spec)){
            if(d==null||!identifier(d.getName())||!d.getName().matches("[A-Z][A-Za-z0-9]{0,39}")||!names.add(d.getName())||d.getFields()==null||d.getFields().isEmpty()||d.getFields().size()>12)throw new IllegalArgumentException("Invalid custom structure");
            Set<String> fields=new HashSet<>();for(var f:d.getFields())if(f==null||!identifier(f.getName())||!fields.add(f.getName())||!FIELDS.contains(f.getType()))throw new IllegalArgumentException("Invalid custom field");
        }
        if(spec.getParameters()!=null)for(var p:spec.getParameters())if(isNode(p.getType()))definition(spec,p.getType());
        if(isNode(spec.getReturnType()))definition(spec,spec.getReturnType());
    }
    public static void graph(JsonElement value,CustomStructureSpec d){
        if(value.isJsonNull())return;
        if(!value.isJsonObject()||!value.getAsJsonObject().keySet().equals(Set.of("root","nodes")))throw new IllegalArgumentException("Node graph object required");
        var object=value.getAsJsonObject();var raw=object.get("nodes");if(!raw.isJsonArray()||raw.getAsJsonArray().size()==0||raw.getAsJsonArray().size()>MAX_NODES)throw new IllegalArgumentException("Node count");
        JsonArray nodes=raw.getAsJsonArray();int root=index(object.get("root"),nodes.size(),false);Set<String> keys=new HashSet<>();d.getFields().forEach(f->keys.add(f.getName()));
        List<List<Integer>> edges=new ArrayList<>();
        for(var node:nodes){
            if(!node.isJsonObject()||!node.getAsJsonObject().keySet().equals(keys))throw new IllegalArgumentException("Exact node fields required");var references=new ArrayList<Integer>();
            for(var f:d.getFields()){
                JsonElement v=node.getAsJsonObject().get(f.getName());String t=f.getType();
                if(t.equals("ref")){int id=index(v,nodes.size(),true);if(id>=0)references.add(id);}
                else if(t.equals("ref[]")){if(!v.isJsonArray()||v.getAsJsonArray().size()>MAX_REFS)throw new IllegalArgumentException("Reference list");for(var ref:v.getAsJsonArray()){int id=index(ref,nodes.size(),true);if(id>=0)references.add(id);}}
                else scalar(v,t);
            }edges.add(references);
        }
        Set<Integer> seen=new HashSet<>();ArrayDeque<Integer> pending=new ArrayDeque<>();pending.add(root);
        while(!pending.isEmpty()){int i=pending.removeFirst();if(seen.add(i))pending.addAll(edges.get(i));}
        if(seen.size()!=nodes.size())throw new IllegalArgumentException("Unreachable custom node");
    }
    private static int index(JsonElement value,int size,boolean nil){scalar(value,"int");int i=value.getAsInt();if(i<(nil?-1:0)||i>=size)throw new IllegalArgumentException("Invalid node reference");return i;}
    private static void scalar(JsonElement v,String type){
        if(v==null||!v.isJsonPrimitive())throw new IllegalArgumentException("Scalar field required");JsonPrimitive p=v.getAsJsonPrimitive();
        if(type.equals("string")){if(!p.isString()||p.getAsString().codePointCount(0,p.getAsString().length())>200||p.getAsString().contains("\n")||p.getAsString().contains("\r"))throw new IllegalArgumentException("String field");return;}
        if(type.equals("boolean")){if(!p.isBoolean())throw new IllegalArgumentException("Boolean field");return;}
        if(type.equals("double")){if(!p.isNumber()||!Double.isFinite(p.getAsDouble()))throw new IllegalArgumentException("Double field");return;}
        if(!p.isNumber()||!p.getAsString().matches("-?(0|[1-9][0-9]*)"))throw new IllegalArgumentException("Integer field");int bits=type.equals("int")?31:63;BigInteger n=p.getAsBigInteger();
        if(n.compareTo(BigInteger.ONE.shiftLeft(bits).negate())<0||n.compareTo(BigInteger.ONE.shiftLeft(bits))>=0)throw new IllegalArgumentException("Integer range");
    }
}
