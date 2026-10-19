import com.google.gson.*;
import java.util.*;
public class TreeNode {
    public int val;
    public TreeNode left,right;
    public TreeNode() {}
    public TreeNode(int val) {this.val=val;}
    public TreeNode(int val,TreeNode left,TreeNode right) {this.val=val;this.left=left;this.right=right;}
    public static TreeNode fromLevelOrder(JsonArray values) {
        if(values==null || values.size()==0) return null;
        if(values.size()>20001) throw new IllegalArgumentException("Node limit");
        if(values.get(0).isJsonNull()) {
            for(JsonElement item:values) if(!item.isJsonNull()) throw new IllegalArgumentException("Unreachable node");
            return null;
        }
        TreeNode root=new TreeNode(values.get(0).getAsBigDecimal().intValueExact());
        Queue<TreeNode> queue=new ArrayDeque<>();Queue<Integer> levels=new ArrayDeque<>();
        queue.add(root);levels.add(1);int index=1,count=1;
        while(!queue.isEmpty() && index<values.size()) {
            TreeNode node=queue.remove();int depth=levels.remove();
            for(int side=0;side<2 && index<values.size();side++) {
                JsonElement item=values.get(index++);
                if(item.isJsonNull()) continue;
                if(++count>10000 || depth>=1000) throw new IllegalArgumentException("Node/depth limit");
                TreeNode child=new TreeNode(item.getAsBigDecimal().intValueExact());
                if(side==0)node.left=child;else node.right=child;
                queue.add(child);levels.add(depth+1);
            }
        }
        while(index<values.size()) if(!values.get(index++).isJsonNull()) throw new IllegalArgumentException("Unreachable node");
        return root;
    }
    public JsonArray toJson() {
        JsonArray result=new JsonArray();
        Queue<TreeNode> queue=new LinkedList<>();Queue<Integer> levels=new LinkedList<>();
        Set<TreeNode> seen=Collections.newSetFromMap(new IdentityHashMap<TreeNode,Boolean>());
        queue.add(this);levels.add(1);
        while(!queue.isEmpty()) {
            TreeNode node=queue.remove();int depth=levels.remove();
            if(node==null) {result.add(JsonNull.INSTANCE);continue;}
            if(depth>1000 || seen.size()>=10000 || !seen.add(node)) throw new IllegalStateException("Cycle or node/depth limit");
            result.add(node.val);
            queue.add(node.left);levels.add(depth+1);queue.add(node.right);levels.add(depth+1);
        }
        while(result.size()>0 && result.get(result.size()-1).isJsonNull()) result.remove(result.size()-1);
        return result;
    }
}
