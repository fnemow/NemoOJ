package com.nemo.OJbackendjudgeservice.judge.harness;

import com.google.gson.*;
import com.nemo.OJbackendmodel.model.dto.question.ProblemSpec;
import java.math.BigInteger;

/** Validate the untrusted Runner result against the stored problem signature. */
public final class FunctionResultValidator {
    private FunctionResultValidator() {}
    public static boolean valid(ProblemSpec spec, String actual) {
        try {
            JsonElement value=JsonParser.parseString(actual);
            String source=spec.getResultSource();
            if(source==null || source.equals("return")) return type(value,spec.getReturnType(),spec);
            if(source.startsWith("parameter:")) return type(value,spec.getParameters().get(Integer.parseInt(source.substring(10))).getType(),spec);
            if(source.startsWith("parameters:")) {
                JsonArray indexes=JsonParser.parseString(source.substring(11)).getAsJsonArray();
                if(!value.isJsonArray() || value.getAsJsonArray().size()!=indexes.size()) return false;
                for(int i=0;i<indexes.size();i++) if(!type(value.getAsJsonArray().get(i),spec.getParameters().get(indexes.get(i).getAsInt()).getType(),spec)) return false;
                return true;
            }
            return false;
        } catch(RuntimeException error) { return false; }
    }
    private static boolean type(JsonElement value,String type,ProblemSpec spec){
        if(com.nemo.OJbackendmodel.model.dto.question.CustomStructureContract.isNode(type)){
            com.nemo.OJbackendmodel.model.dto.question.CustomStructureContract.validate(spec);
            com.nemo.OJbackendmodel.model.dto.question.CustomStructureContract.graph(value,com.nemo.OJbackendmodel.model.dto.question.CustomStructureContract.definition(spec,type));return true;
        }return type(value,type);
    }
    private static boolean type(JsonElement value,String type) {
        if(type.equals("void")) return value.isJsonNull();
        if(type.equals("list_node<int>") || type.equals("tree_node<int>")) {
            if(value.isJsonNull()) return true;
            if(!value.isJsonArray() || value.getAsJsonArray().size()>20001) return false;
            int nodes=0;
            for(JsonElement item:value.getAsJsonArray()) {
                if(type.equals("tree_node<int>") && item.isJsonNull()) continue;
                if(++nodes>10000 || !type(item,"int")) return false;
            }
            return true;
        }
        if(type.endsWith("[]") || type.equals("list<int>")) {
            if(!value.isJsonArray()) return false;
            String element=type.equals("list<int>") ? "int" : type.substring(0,type.length()-2);
            for(JsonElement item:value.getAsJsonArray()) if(!type(item,element)) return false;
            return true;
        }
        if(!value.isJsonPrimitive()) return false;
        JsonPrimitive scalar=value.getAsJsonPrimitive();
        return switch(type) {
            case "boolean" -> scalar.isBoolean();
            case "string" -> scalar.isString();
            case "double" -> scalar.isNumber() && Double.isFinite(scalar.getAsDouble());
            case "int", "long" -> {
                if(!scalar.isNumber() || !scalar.getAsString().matches("-?(0|[1-9][0-9]*)")) yield false;
                BigInteger number=scalar.getAsBigInteger();
                int bits=type.equals("int")?31:63;
                yield number.compareTo(BigInteger.ONE.shiftLeft(bits).negate())>=0 && number.compareTo(BigInteger.ONE.shiftLeft(bits))<0;
            }
            default -> false;
        };
    }
}
