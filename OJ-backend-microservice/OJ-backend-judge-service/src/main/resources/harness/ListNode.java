import com.google.gson.*;
import java.util.*;
public class ListNode {
    public int val;
    public ListNode next;
    public ListNode() {}
    public ListNode(int val) { this.val=val; }
    public ListNode(int val,ListNode next) { this.val=val;this.next=next; }
    public static ListNode fromJson(JsonArray values) {
        if(values==null || values.size()==0) return null;
        if(values.size()>10000) throw new IllegalArgumentException("Node limit");
        ListNode dummy=new ListNode(),tail=dummy;
        for(JsonElement value:values) {
            tail.next=new ListNode(value.getAsBigDecimal().intValueExact());tail=tail.next;
        }
        return dummy.next;
    }
    public JsonArray toJson() {
        JsonArray result=new JsonArray();
        Set<ListNode> seen=Collections.newSetFromMap(new IdentityHashMap<ListNode,Boolean>());
        for(ListNode node=this;node!=null;node=node.next) {
            if(seen.size()>=10000 || !seen.add(node)) throw new IllegalStateException("Cycle or node limit");
            result.add(node.val);
        }
        return result;
    }
}
