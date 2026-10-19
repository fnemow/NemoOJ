class NemoPlainIO {
    static String element(String t) { return t.endsWith("[]") ? t.substring(0,t.length()-2) : t.startsWith("list<") ? t.substring(5,t.length()-1) : null; }
    static JsonElement read(BufferedReader reader,String t) throws IOException {
        String line=reader.readLine(); if(line==null) throw new IllegalArgumentException("missing input");
        String e=element(t); boolean nodes=t.equals("list_node<int>")||t.equals("tree_node<int>");
        if(e!=null||nodes) {
            int n=Integer.parseInt(line); if(nodes&&n==-1) return JsonNull.INSTANCE;
            if(n<0||n>10000) throw new IllegalArgumentException("length");
            JsonArray a=new JsonArray(); for(int i=0;i<n;i++) {
                if(t.equals("tree_node<int>")) { String v=reader.readLine(); a.add("null".equals(v)?JsonNull.INSTANCE:new JsonPrimitive(Integer.parseInt(v))); }
                else a.add(read(reader,e==null?"int":e));
            } return a;
        }
        if(t.equals("string")) return new JsonPrimitive(line);
        if(t.equals("boolean")) { if(!line.equals("true")&&!line.equals("false")) throw new IllegalArgumentException("boolean"); return new JsonPrimitive(Boolean.parseBoolean(line)); }
        if(t.equals("int")) return new JsonPrimitive(Integer.parseInt(line));
        if(t.equals("long")) return new JsonPrimitive(Long.parseLong(line));
        return new JsonPrimitive(Double.parseDouble(line));
    }
    static String format(JsonElement value,String t) {
        String e=element(t); boolean nodes=t.equals("list_node<int>")||t.equals("tree_node<int>");
        if(e!=null||nodes) {
            if(nodes&&value.isJsonNull()) return "-1\n";
            JsonArray a=value.getAsJsonArray(); StringBuilder out=new StringBuilder(a.size()+"\n");
            for(JsonElement v:a) out.append(nodes&&v.isJsonNull()?"null\n":format(v,e==null?"int":e)); return out.toString();
        }
        return (t.equals("double")?String.format(java.util.Locale.ROOT,"%.16e",new java.math.BigDecimal(value.getAsDouble())):value.getAsString())+"\n";
    }
}
