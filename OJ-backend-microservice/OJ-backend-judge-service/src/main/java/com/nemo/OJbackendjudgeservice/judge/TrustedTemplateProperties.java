package com.nemo.OJbackendjudgeservice.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nemo.OJbackendmodel.model.dto.question.ProblemSpec;
import java.util.*;

/** Platform-authored semantic checks, independent of both sandbox programs. */
final class TrustedTemplateProperties {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Map<String,String> METHODS=Map.of("prefix_single","rangeSum","prefix_queries","rangeSums","prefix_matrix","rectangleSums","binary_first","firstIndex","binary_bounds","targetBounds","binary_matrix_k","kthSmallest");
    static void signature(String key,String version,ProblemSpec spec){
        if("trusted-v2".equals(version)){TrustedTemplateV2Properties.signature(key,spec);return;}
        require("trusted-v1".equals(version)&&METHODS.containsKey(key),"unknown template");
        require(METHODS.get(key).equals(spec.getMethodName()),"template method mismatch");
        List<String> names=switch(key){case "prefix_single"->List.of("nums","left","right");case "prefix_queries"->List.of("nums","queries");case "prefix_matrix"->List.of("matrix","queries");case "binary_matrix_k"->List.of("matrix","k");default->List.of("nums","target");};
        require(spec.getParameters().stream().map(p->p.getName()).toList().equals(names),"template parameters mismatch");
        for(var p:spec.getParameters())require(("nums".equals(p.getName())?"int[]":Set.of("matrix","queries").contains(p.getName())?"int[][]":"int").equals(p.getType()),"template type mismatch");
        require((Set.of("prefix_queries","prefix_matrix","binary_bounds").contains(key)?"int[]":"int").equals(spec.getReturnType()),"template result mismatch");
    }
    static JsonNode expected(String key,JsonNode args){
        if(TrustedTemplateV2Properties.supports(key))return TrustedTemplateV2Properties.expected(key,args);
        if(key.equals("prefix_matrix")||key.equals("binary_matrix_k")){
            var matrix=args.path("matrix");require(matrix.isArray()&&matrix.size()>=1&&matrix.size()<=10,"matrix rows");
            int m=matrix.size(),n=matrix.get(0).size();require(n>=1&&n<=10,"matrix columns");
            List<Integer> flat=new ArrayList<>();
            for(var row:matrix){require(row.isArray()&&row.size()==n,"nonrectangular matrix");for(var x:row)flat.add(element(x));}
            if(key.equals("binary_matrix_k")){
                for(int i=0;i<m;i++)for(int j=0;j<n;j++){if(i>0)require(matrix.get(i-1).get(j).intValue()<=matrix.get(i).get(j).intValue(),"matrix column order");if(j>0)require(matrix.get(i).get(j-1).intValue()<=matrix.get(i).get(j).intValue(),"matrix row order");}
                int k=index(args.path("k"));require(k>=1&&k<=m*n,"invalid k");Collections.sort(flat);return JSON.valueToTree(flat.get(k-1));
            }
            var result=new ArrayList<Integer>();var queries=args.path("queries");queries(queries);
            for(var q:queries){require(q.isArray()&&q.size()==4,"rectangle shape");int t=index(q.get(0)),l=index(q.get(1)),b=index(q.get(2)),r=index(q.get(3));require(0<=t&&t<=b&&b<m&&0<=l&&l<=r&&r<n,"rectangle bounds");int total=0;for(int i=t;i<=b;i++)for(int j=l;j<=r;j++)total+=matrix.get(i).get(j).intValue();result.add(total);}
            return JSON.valueToTree(result);
        }
        var nums=args.path("nums");require(nums.isArray()&&nums.size()<=200,"array length");var values=new ArrayList<Integer>();for(var x:nums)values.add(element(x));
        if(key.startsWith("binary_")){
            for(int i=1;i<values.size();i++)require(values.get(i-1)<=values.get(i),"array order");
            int target=index(args.path("target"));require(-101<=target&&target<=101,"target range");int first=values.indexOf(target),last=values.lastIndexOf(target);
            return key.equals("binary_first")?JSON.valueToTree(first):JSON.valueToTree(List.of(first,last));
        }
        require(!values.isEmpty(),"empty interval array");
        if(key.equals("prefix_single"))return JSON.valueToTree(sum(values,index(args.path("left")),index(args.path("right"))));
        var queries=args.path("queries");queries(queries);var result=new ArrayList<Integer>();
        for(var q:queries){require(q.isArray()&&q.size()==2,"interval shape");result.add(sum(values,index(q.get(0)),index(q.get(1))));}return JSON.valueToTree(result);
    }
    private static int sum(List<Integer> a,int l,int r){require(0<=l&&l<=r&&r<a.size(),"interval bounds");return a.subList(l,r+1).stream().mapToInt(Integer::intValue).sum();}
    private static int element(JsonNode x){int v=index(x);require(-100<=v&&v<=100,"element range");return v;}
    private static int index(JsonNode x){require(x!=null&&x.isIntegralNumber()&&x.canConvertToInt(),"integer required");return x.intValue();}
    private static void queries(JsonNode q){require(q.isArray()&&q.size()>=1&&q.size()<=20,"query count");}
    private static void require(boolean valid,String why){if(!valid)throw new IllegalArgumentException(why);}
}
