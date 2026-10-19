class NemoCustomCodec {
    static void check(JsonObject o,int count){if(o.size()!=count)throw new IllegalArgumentException("node fields");}
    static JsonObject graph(JsonElement v){JsonObject g=v.getAsJsonObject();check(g,2);JsonArray a=g.getAsJsonArray("nodes");if(a==null||a.size()<1||a.size()>200)throw new IllegalArgumentException("nodes");return g;}
    static JsonArray refs(JsonElement v){JsonArray a=v.getAsJsonArray();if(a.size()>32)throw new IllegalArgumentException("refs");return a;}
    static int index(JsonElement v,int size,boolean nil){int i=(Integer)scalar(v,"int");if(i<(nil?-1:0)||i>=size)throw new IllegalArgumentException("ref index");return i;}
    static Object scalar(JsonElement v,String t){
        if(v==null||!v.isJsonPrimitive())throw new IllegalArgumentException("scalar");JsonPrimitive p=v.getAsJsonPrimitive();
        if(t.equals("string")){if(!p.isString()||p.getAsString().codePointCount(0,p.getAsString().length())>200||p.getAsString().contains("\n")||p.getAsString().contains("\r"))throw new IllegalArgumentException("string");return p.getAsString();}
        if(t.equals("boolean")){if(!p.isBoolean())throw new IllegalArgumentException("boolean");return p.getAsBoolean();}
        if(t.equals("double")){double n=p.getAsDouble();if(!p.isNumber()||!Double.isFinite(n))throw new IllegalArgumentException("double");return n;}
        if(!p.isNumber()||!p.getAsString().matches("-?(0|[1-9][0-9]*)"))throw new IllegalArgumentException("integer");java.math.BigInteger n=p.getAsBigInteger();int bits=t.equals("int")?31:63;
        if(n.compareTo(java.math.BigInteger.ONE.shiftLeft(bits).negate())<0||n.compareTo(java.math.BigInteger.ONE.shiftLeft(bits))>=0)throw new IllegalArgumentException("range");if(t.equals("int"))return n.intValue();return n.longValue();
    }
    static JsonElement scalarJson(Object value,String t){JsonElement json=new Gson().toJsonTree(value);scalar(json,t);return json;}
    static int discover(Object v,IdentityHashMap<Object,Integer> ids,ArrayList<Object> queue){if(v==null)return -1;Integer id=ids.get(v);if(id!=null)return id;if(queue.size()>=200)throw new IllegalArgumentException("node limit");int next=queue.size();ids.put(v,next);queue.add(v);return next;}
    static Object read(JsonElement v,String type){__READS__ throw new IllegalArgumentException("node type");}
    static JsonElement write(Object v,String type){__WRITES__ throw new IllegalArgumentException("node type");}
    __METHODS__
}
