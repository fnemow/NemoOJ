package com.nemo.OJbackendjudgeservice.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nemo.OJbackendmodel.model.dto.question.ProblemSpec;
import java.util.*;

/** Trusted small-input semantic checks; never evaluates model-provided code. */
final class TrustedTemplateV2Properties {
    private static final ObjectMapper JSON=new ObjectMapper();
    record Contract(String method,String result,List<String> names,List<String> types){}
    private static Contract c(String method,String result,String... namesAndTypes){
        List<String> names=new ArrayList<>(),types=new ArrayList<>();
        for(int i=0;i<namesAndTypes.length;i+=2){names.add(namesAndTypes[i]);types.add(namesAndTypes[i+1]);}
        return new Contract(method,result,names,types);
    }
    private static final Map<String,Contract> CONTRACTS=Map.ofEntries(
        Map.entry("string_unique",c("firstUnique","int","s","string")),
        Map.entry("string_distinct",c("longestDistinct","int","s","string")),
        Map.entry("string_window",c("minimumWindow","string","s","string","t","string")),
        Map.entry("dp_climb",c("climbWays","int","n","int")),
        Map.entry("dp_coins",c("minimumCoins","int","coins","int[]","amount","int")),
        Map.entry("dp_edit",c("editDistance","int","a","string","b","string")),
        Map.entry("list_reverse",c("reverseValues","int[]","head","list_node<int>")),
        Map.entry("list_kth",c("kthFromEnd","int","head","list_node<int>","k","int")),
        Map.entry("list_groups",c("reverseGroups","int[]","head","list_node<int>","k","int")),
        Map.entry("tree_depth",c("maximumDepth","int","root","tree_node<int>")),
        Map.entry("tree_levels",c("levelSums","int[]","root","tree_node<int>")),
        Map.entry("tree_path",c("maximumPathSum","int","root","tree_node<int>")));

    static boolean supports(String key){return CONTRACTS.containsKey(key);}
    static void signature(String key,ProblemSpec spec){
        Contract c=CONTRACTS.get(key);require(c!=null,"unknown v2 template");
        require(c.method.equals(spec.getMethodName())&&c.result.equals(spec.getReturnType()),"template method/result mismatch");
        require(spec.getParameters()!=null&&spec.getParameters().stream().map(p->p.getName()).toList().equals(c.names),"template parameter mismatch");
        require(spec.getParameters().stream().map(p->p.getType()).toList().equals(c.types),"template type mismatch");
    }
    static JsonNode expected(String key,JsonNode a){
        Contract c=CONTRACTS.get(key);require(c!=null&&a.isObject(),"unknown template/input");
        Set<String> fields=new HashSet<>();a.fieldNames().forEachRemaining(fields::add);
        require(fields.equals(new HashSet<>(c.names)),"contract parameters");
        Object answer;
        if(key.startsWith("string_")){
            String s=text(a.get("s"),24);
            answer=switch(key){
                case "string_unique"->firstUnique(s);
                case "string_distinct"->distinct(s);
                default->window(s,text(a.get("t"),24));
            };
        }else if(key.equals("dp_climb")){
            int n=integer(a.get("n"),0,15);answer=climb(n);
        }else if(key.equals("dp_coins")){
            List<Integer> coins=values(a.get("coins"),4,1,9,false);require(new HashSet<>(coins).size()==coins.size(),"duplicate coins");
            int amount=integer(a.get("amount"),0,16),best=coinCounts(coins,0,amount);
            answer=best>amount?-1:best;
        }else if(key.equals("dp_edit")){
            String s=text(a.get("a"),4),t=text(a.get("b"),4);int[][] d=new int[s.length()+1][t.length()+1];
            for(int i=0;i<=s.length();i++)d[i][0]=i;for(int j=0;j<=t.length();j++)d[0][j]=j;
            for(int i=1;i<=s.length();i++)for(int j=1;j<=t.length();j++)d[i][j]=Math.min(Math.min(d[i-1][j]+1,d[i][j-1]+1),d[i-1][j-1]+(s.charAt(i-1)==t.charAt(j-1)?0:1));
            answer=d[s.length()][t.length()];
        }else if(key.startsWith("list_")){
            List<Integer> v=values(a.get("head"),16,-9,9,true);
            if(key.equals("list_reverse")){Collections.reverse(v);answer=v;}
            else{
                int k=integer(a.get("k"),1,16);
                if(key.equals("list_kth")){require(k<=v.size(),"k bounds");answer=v.get(v.size()-k);}
                else{for(int i=0;i+k<=v.size();i+=k)Collections.reverse(v.subList(i,i+k));answer=v;}
            }
        }else{
            Node root=tree(a.get("root"));
            if(key.equals("tree_depth"))answer=depth(root);
            else if(key.equals("tree_levels")){List<Integer> sums=new ArrayList<>();levels(root,0,sums);answer=sums;}
            else answer=path(root);
        }
        return JSON.valueToTree(answer);
    }
    private static int firstUnique(String s){for(int i=0;i<s.length();i++){int count=0;for(int j=0;j<s.length();j++)if(s.charAt(j)==s.charAt(i))count++;if(count==1)return i;}return -1;}
    private static int distinct(String s){int best=0;for(int i=0;i<s.length();i++){Set<Character> seen=new HashSet<>();for(int j=i;j<s.length()&&seen.add(s.charAt(j));j++)best=Math.max(best,j-i+1);}return best;}
    private static String window(String s,String t){
        if(t.isEmpty())return "";int[] need=new int[26];for(char x:t.toCharArray())need[x-97]++;
        for(int size=1;size<=s.length();size++)for(int i=0;i+size<=s.length();i++){
            int[] count=new int[26];for(int j=i;j<i+size;j++)count[s.charAt(j)-97]++;
            boolean valid=true;for(int j=0;j<26;j++)if(count[j]<need[j])valid=false;
            if(valid)return s.substring(i,i+size);
        }return "";
    }
    private static int climb(int n){return n==0?1:n<0?0:climb(n-1)+climb(n-2);}
    private static int coinCounts(List<Integer> coins,int i,int left){
        if(i==coins.size())return left==0?0:100;int best=100;
        for(int n=0;n*coins.get(i)<=left;n++)best=Math.min(best,n+coinCounts(coins,i+1,left-n*coins.get(i)));return best;
    }
    static final class Node{final int value;Node left,right;Node(int value){this.value=value;}}
    private static Node tree(JsonNode raw){
        if(raw==null||raw.isNull())return null;require(raw.isArray()&&raw.size()<=15,"tree bounds");
        if(raw.isEmpty())return null;
        for(var value:raw)if(!value.isNull())integer(value,-9,9);
        if(raw.get(0).isNull()){for(var v:raw)require(v.isNull(),"unreachable node");return null;}
        Node root=new Node(integer(raw.get(0),-9,9));Deque<Node> q=new ArrayDeque<>();q.add(root);int i=1;
        while(!q.isEmpty()&&i<raw.size()){
            Node parent=q.remove();for(int side=0;side<2&&i<raw.size();side++,i++)if(!raw.get(i).isNull()){
                Node child=new Node(integer(raw.get(i),-9,9));if(side==0)parent.left=child;else parent.right=child;q.add(child);
            }
        }
        while(i<raw.size())require(raw.get(i++).isNull(),"unreachable node");return root;
    }
    private static int depth(Node n){return n==null?0:1+Math.max(depth(n.left),depth(n.right));}
    private static void levels(Node n,int d,List<Integer> out){if(n==null)return;if(out.size()<=d)out.add(0);out.set(d,out.get(d)+n.value);levels(n.left,d+1,out);levels(n.right,d+1,out);}
    private static int path(Node root){
        if(root==null)return 0;List<Node> nodes=new ArrayList<>();Map<Node,List<Node>> graph=new IdentityHashMap<>();connect(root,null,nodes,graph);int best=Integer.MIN_VALUE;
        for(Node start:nodes)best=Math.max(best,paths(start,null,0,graph));return best;
    }
    private static void connect(Node n,Node parent,List<Node> nodes,Map<Node,List<Node>> graph){if(n==null)return;nodes.add(n);graph.put(n,new ArrayList<>());if(parent!=null){graph.get(n).add(parent);graph.get(parent).add(n);}connect(n.left,n,nodes,graph);connect(n.right,n,nodes,graph);}
    private static int paths(Node n,Node parent,int sum,Map<Node,List<Node>> graph){sum+=n.value;int best=sum;for(Node child:graph.get(n))if(child!=parent)best=Math.max(best,paths(child,n,sum,graph));return best;}
    private static String text(JsonNode x,int limit){require(x!=null&&x.isTextual()&&x.textValue().length()<=limit&&x.textValue().matches("[a-z]*"),"string bounds");return x.textValue();}
    private static List<Integer> values(JsonNode x,int limit,int min,int max,boolean nullable){List<Integer> v=new ArrayList<>();if(nullable&&x!=null&&x.isNull())return v;require(x!=null&&x.isArray()&&x.size()<=limit,"array bounds");for(var a:x)v.add(integer(a,min,max));return v;}
    private static int integer(JsonNode x,int min,int max){require(x!=null&&x.isIntegralNumber()&&x.canConvertToInt()&&x.intValue()>=min&&x.intValue()<=max,"integer bounds");return x.intValue();}
    private static void require(boolean value,String message){if(!value)throw new IllegalArgumentException(message);}
}
