"""Versioned authored contracts. Models cannot modify algorithms or legal generators."""
import random
from nemo_agent.private_generation import plain_value

CONTRACTS = {
    'string_unique': ('EASY','字符串：首个不重复字符','firstUnique',[('s','string')],'int'),
    'string_distinct': ('MEDIUM','滑动窗口：最长无重复子串','longestDistinct',[('s','string')],'int'),
    'string_window': ('HARD','滑动窗口：最小覆盖子串','minimumWindow',[('s','string'),('t','string')],'string'),
    'dp_climb': ('EASY','动态规划：一阶或二阶爬楼梯','climbWays',[('n','int')],'int'),
    'dp_coins': ('MEDIUM','动态规划：最少硬币数量','minimumCoins',[('coins','int[]'),('amount','int')],'int'),
    'dp_edit': ('HARD','动态规划：字符串编辑距离','editDistance',[('a','string'),('b','string')],'int'),
    'list_reverse': ('EASY','链表：反向输出节点值','reverseValues',[('head','list_node<int>')],'int[]'),
    'list_kth': ('MEDIUM','链表：倒数第 k 个节点值','kthFromEnd',[('head','list_node<int>'),('k','int')],'int'),
    'list_groups': ('HARD','链表：每 k 个节点反转后的值序列','reverseGroups',[('head','list_node<int>'),('k','int')],'int[]'),
    'tree_depth': ('EASY','二叉树：最大深度','maximumDepth',[('root','tree_node<int>')],'int'),
    'tree_levels': ('MEDIUM','二叉树：按层汇总节点值','levelSums',[('root','tree_node<int>')],'int[]'),
    'tree_path': ('HARD','二叉树：最大路径和','maximumPathSum',[('root','tree_node<int>')],'int'),
}
RULES = {
    'string_unique':'s 仅包含小写英文字母。返回第一个仅出现一次字符的零起始索引，无则 -1，包括空串。使用 O(n) 时间。',
    'string_distinct':'s 仅包含小写英文字母。返回最长无重复连续子串长度，空串返回 0。使用 O(n) 滑动窗口。',
    'string_window':'s、t 仅包含小写英文字母。返回覆盖 t 全部字符及其重复次数的最短连续子串；等长时取起始索引最小者，t 为空或无解返回空串。使用 O(|s|+|t|) 时间。',
    'dp_climb':'每次爬 1 或 2 阶。返回从第 0 阶到第 n 阶的不同步序列数量，n=0 返回 1。使用 O(n) 时间、O(1) 额外空间。',
    'dp_coins':'coins 是互不相同的正整数，每种无限使用。返回凑成 amount 的最少硬币数，无解 -1，amount=0 返回 0。使用 O(amount×硬币种数) 时间。',
    'dp_edit':'a、b 为小写英文字母字符串。插入、删除或替换单个字符各计一次，返回将 a 转为 b 的最少操作数。使用 O(|a|×|b|) 时间。',
    'list_reverse':'head 是无环整数单链表，返回从尾到头的节点值数组，空链表返回空数组。使用 O(n) 时间。',
    'list_kth':'head 为非空无环单链表。返回倒数第 k 个节点值，k 从 1 起。使用 O(n) 时间、O(1) 额外空间，不能先把链表变成数组。',
    'list_groups':'head 为无环单链表，逐组原地反转每 k 个节点，末尾不足 k 个保持原顺序；返回反转后的节点值数组。k>=1，空链表返回空数组。要求 O(n) 时间、除返回数组外 O(1) 额外空间。',
    'tree_depth':'root 为整数二叉树，按节点数返回根到叶的最长路径长度，空树返回 0。使用 O(n) 时间。',
    'tree_levels':'root 为整数二叉树，返回从根层开始每层的节点值之和组成的数组，空树返回空数组。使用 O(n) 时间。',
    'tree_path':'路径为相邻父子节点组成且不重复经过节点的非空序列，可以不经过根。返回所有路径最大节点值之和，空树约定为 0。全负树必须选择至少一个节点。使用 O(n) 时间。',
}
PY = {
 'string_unique':'count={}\nfor c in s: count[c]=count.get(c,0)+1\nfor i,c in enumerate(s):\n if count[c]==1: return i\nreturn -1',
 'string_distinct':'last={};left=0;best=0\nfor i,c in enumerate(s):\n left=max(left,last.get(c,-1)+1);last[c]=i;best=max(best,i-left+1)\nreturn best',
 'string_window':'if not t: return ""\nneed={}\nfor c in t: need[c]=need.get(c,0)+1\nmissing=len(t);left=0;best=len(s)+1;start=0\nfor right,c in enumerate(s):\n if need.get(c,0)>0: missing-=1\n need[c]=need.get(c,0)-1\n while missing==0:\n  if right-left+1<best: best=right-left+1;start=left\n  x=s[left];need[x]+=1;left+=1\n  if need[x]>0: missing+=1\nreturn "" if best>len(s) else s[start:start+best]',
 'dp_climb':'x,y=1,1\nfor _ in range(n): x,y=y,x+y\nreturn x',
 'dp_coins':'dp=[0]+[amount+1]*amount\nfor x in range(1,amount+1):\n for c in coins:\n  if c<=x: dp[x]=min(dp[x],dp[x-c]+1)\nreturn dp[amount] if dp[amount]<=amount else -1',
 'dp_edit':'prev=list(range(len(b)+1))\nfor i,x in enumerate(a,1):\n row=[i]+[0]*len(b)\n for j,y in enumerate(b,1): row[j]=min(prev[j]+1,row[j-1]+1,prev[j-1]+(x!=y))\n prev=row\nreturn prev[-1]',
 'list_reverse':'values=[]\nwhile head: values.append(head.val);head=head.next\nvalues.reverse()\nreturn values',
 'list_kth':'fast=slow=head\nfor _ in range(k): fast=fast.next\nwhile fast: fast=fast.next;slow=slow.next\nreturn slow.val',
 'list_groups':'dummy=ListNode(0);dummy.next=head;before=dummy\nwhile True:\n end=before\n for _ in range(k):\n  end=end.next\n  if end is None: break\n if end is None: break\n after=end.next;current=before.next;old=current;prev=after\n while current is not after:\n  following=current.next;current.next=prev;prev=current;current=following\n before.next=end;before=old\nvalues=[];head=dummy.next\nwhile head: values.append(head.val);head=head.next\nreturn values',
 'tree_depth':'if root is None: return 0\nstack=[(root,1)];best=0\nwhile stack:\n node,depth=stack.pop();best=max(best,depth)\n if node.left: stack.append((node.left,depth+1))\n if node.right: stack.append((node.right,depth+1))\nreturn best',
 'tree_levels':'level=[root] if root else [];result=[]\nwhile level:\n result.append(sum(node.val for node in level));level=[child for node in level for child in (node.left,node.right) if child]\nreturn result',
 'tree_path':'if root is None: return 0\nbest=[root.val]\ndef visit(node):\n if node is None: return 0\n left=max(0,visit(node.left));right=max(0,visit(node.right))\n best[0]=max(best[0],node.val+left+right)\n return node.val+max(left,right)\nvisit(root)\nreturn best[0]',
}
ORACLE = {
 'string_unique':'for i,c in enumerate(s):\n if sum(x==c for x in s)==1: return i\nreturn -1',
 'string_distinct':'best=0\nfor i in range(len(s)):\n for j in range(i+1,len(s)+1):\n  if len(set(s[i:j]))==j-i: best=max(best,j-i)\nreturn best',
 'string_window':'if not t: return ""\nrequired={c:t.count(c) for c in set(t)}\nfor size in range(1,len(s)+1):\n for i in range(len(s)-size+1):\n  part=s[i:i+size]\n  if all(part.count(c)>=n for c,n in required.items()): return part\nreturn ""',
 'dp_climb':'def count(x):\n if x==0: return 1\n if x<0: return 0\n return count(x-1)+count(x-2)\nreturn count(n)',
 'dp_coins':'best=[amount+1]\ndef enumerate_counts(i,left,count):\n if i==len(coins):\n  if left==0: best[0]=min(best[0],count)\n  return\n for n in range(left//coins[i]+1): enumerate_counts(i+1,left-n*coins[i],count+n)\nenumerate_counts(0,amount,0)\nreturn best[0] if best[0]<=amount else -1',
 'dp_edit':'def search(i,j):\n if i==len(a): return len(b)-j\n if j==len(b): return len(a)-i\n if a[i]==b[j]: return search(i+1,j+1)\n return 1+min(search(i+1,j),search(i,j+1),search(i+1,j+1))\nreturn search(0,0)',
 'list_reverse':'values=[]\ndef visit(node):\n if node: visit(node.next);values.append(node.val)\nvisit(head)\nreturn values',
 'list_kth':'values=[]\nwhile head: values.append(head.val);head=head.next\nreturn values[-k]',
 'list_groups':'values=[]\nwhile head: values.append(head.val);head=head.next\nfor i in range(0,len(values)-k+1,k): values[i:i+k]=reversed(values[i:i+k])\nreturn values',
 'tree_depth':'def height(node):\n return 0 if node is None else 1+max(height(node.left),height(node.right))\nreturn height(root)',
 'tree_levels':'result=[]\ndef visit(node,depth):\n if node is None: return\n if len(result)<=depth: result.append(0)\n result[depth]+=node.val;visit(node.left,depth+1);visit(node.right,depth+1)\nvisit(root,0)\nreturn result',
 'tree_path':'if root is None: return 0\nvalues=[];edges=[]\ndef build(node,parent):\n if node is None: return\n i=len(values);values.append(node.val);edges.append([])\n if parent>=0: edges[i].append(parent);edges[parent].append(i)\n build(node.left,i);build(node.right,i)\nbuild(root,-1);best=max(values)\nfor start in range(len(values)):\n stack=[(start,-1,0)]\n while stack:\n  node,parent,total=stack.pop();total+=values[node];best=max(best,total)\n  for other in edges[node]:\n   if other!=parent: stack.append((other,node,total))\nreturn best',
}
JAVA = {
 'string_unique':'int[] c=new int[26];for(char x:s.toCharArray())c[x-97]++;for(int i=0;i<s.length();i++)if(c[s.charAt(i)-97]==1)return i;return -1;',
 'string_distinct':'int[] last=new int[26];Arrays.fill(last,-1);int l=0,b=0;for(int i=0;i<s.length();i++){int c=s.charAt(i)-97;l=Math.max(l,last[c]+1);last[c]=i;b=Math.max(b,i-l+1);}return b;',
 'string_window':'if(t.isEmpty())return "";int[] need=new int[26];for(char c:t.toCharArray())need[c-97]++;int missing=t.length(),l=0,b=s.length()+1,start=0;for(int r=0;r<s.length();r++){int c=s.charAt(r)-97;if(need[c]>0)missing--;need[c]--;while(missing==0){if(r-l+1<b){b=r-l+1;start=l;}int x=s.charAt(l++)-97;need[x]++;if(need[x]>0)missing++;}}return b>s.length()?"":s.substring(start,start+b);',
 'dp_climb':'int x=1,y=1;for(int i=0;i<n;i++){int z=x+y;x=y;y=z;}return x;',
 'dp_coins':'int[] d=new int[amount+1];Arrays.fill(d,amount+1);d[0]=0;for(int i=1;i<=amount;i++)for(int c:coins)if(c<=i)d[i]=Math.min(d[i],d[i-c]+1);return d[amount]<=amount?d[amount]:-1;',
 'dp_edit':'int[] p=new int[b.length()+1];for(int j=0;j<p.length;j++)p[j]=j;for(int i=1;i<=a.length();i++){int[] r=new int[p.length];r[0]=i;for(int j=1;j<r.length;j++)r[j]=Math.min(Math.min(p[j]+1,r[j-1]+1),p[j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));p=r;}return p[b.length()];',
 'list_reverse':'ArrayList<Integer> a=new ArrayList<>();for(;head!=null;head=head.next)a.add(head.val);int[] r=new int[a.size()];for(int i=0;i<r.length;i++)r[i]=a.get(r.length-1-i);return r;',
 'list_kth':'ListNode fast=head,slow=head;for(int i=0;i<k;i++)fast=fast.next;while(fast!=null){fast=fast.next;slow=slow.next;}return slow.val;',
 'list_groups':'ListNode d=new ListNode(0);d.next=head;ListNode before=d;while(true){ListNode end=before;for(int i=0;i<k&&end!=null;i++)end=end.next;if(end==null)break;ListNode after=end.next,current=before.next,old=current,prev=after;while(current!=after){ListNode next=current.next;current.next=prev;prev=current;current=next;}before.next=end;before=old;}ArrayList<Integer> a=new ArrayList<>();for(head=d.next;head!=null;head=head.next)a.add(head.val);return a.stream().mapToInt(Integer::intValue).toArray();',
 'tree_depth':'if(root==null)return 0;ArrayDeque<TreeNode> q=new ArrayDeque<>();q.add(root);int depth=0;while(!q.isEmpty()){int size=q.size();depth++;while(size-->0){TreeNode x=q.remove();if(x.left!=null)q.add(x.left);if(x.right!=null)q.add(x.right);}}return depth;',
 'tree_levels':'ArrayList<Integer> a=new ArrayList<>();ArrayDeque<TreeNode> q=new ArrayDeque<>();if(root!=null)q.add(root);while(!q.isEmpty()){int size=q.size(),sum=0;while(size-->0){TreeNode x=q.remove();sum+=x.val;if(x.left!=null)q.add(x.left);if(x.right!=null)q.add(x.right);}a.add(sum);}return a.stream().mapToInt(Integer::intValue).toArray();',
 'tree_path':'if(root==null)return 0;class Visit{int best=root.val;int go(TreeNode x){if(x==null)return 0;int l=Math.max(0,go(x.left)),r=Math.max(0,go(x.right));best=Math.max(best,x.val+l+r);return x.val+Math.max(l,r);}}Visit v=new Visit();v.go(root);return v.best;',
}
CPP = {
 'string_unique':'int c[26]={};for(char x:s)c[x-97]++;for(int i=0;i<(int)s.size();i++)if(c[s[i]-97]==1)return i;return -1;',
 'string_distinct':'vector<int> last(26,-1);int l=0,b=0;for(int i=0;i<(int)s.size();i++){int c=s[i]-97;l=max(l,last[c]+1);last[c]=i;b=max(b,i-l+1);}return b;',
 'string_window':'if(t.empty())return "";int need[26]={};for(char c:t)need[c-97]++;int missing=t.size(),l=0,b=s.size()+1,start=0;for(int r=0;r<(int)s.size();r++){int c=s[r]-97;if(need[c]>0)missing--;need[c]--;while(missing==0){if(r-l+1<b){b=r-l+1;start=l;}int x=s[l++]-97;need[x]++;if(need[x]>0)missing++;}}return b>(int)s.size()?"":s.substr(start,b);',
 'dp_climb':'int x=1,y=1;for(int i=0;i<n;i++){int z=x+y;x=y;y=z;}return x;',
 'dp_coins':'vector<int>d(amount+1,amount+1);d[0]=0;for(int i=1;i<=amount;i++)for(int c:coins)if(c<=i)d[i]=min(d[i],d[i-c]+1);return d[amount]<=amount?d[amount]:-1;',
 'dp_edit':'vector<int>p(b.size()+1);iota(p.begin(),p.end(),0);for(int i=1;i<=(int)a.size();i++){vector<int>r(p.size());r[0]=i;for(int j=1;j<(int)r.size();j++)r[j]=min({p[j]+1,r[j-1]+1,p[j-1]+(a[i-1]!=b[j-1])});p=r;}return p[b.size()];',
 'list_reverse':'vector<int>a;for(;head;head=head->next)a.push_back(head->val);reverse(a.begin(),a.end());return a;',
 'list_kth':'ListNode *fast=head,*slow=head;for(int i=0;i<k;i++)fast=fast->next;while(fast){fast=fast->next;slow=slow->next;}return slow->val;',
 'list_groups':'ListNode dummy(0);dummy.next=head;ListNode*before=&dummy;while(true){ListNode*end=before;for(int i=0;i<k&&end;i++)end=end->next;if(!end)break;ListNode*after=end->next,*current=before->next,*old=current,*prev=after;while(current!=after){ListNode*next=current->next;current->next=prev;prev=current;current=next;}before->next=end;before=old;}vector<int>a;for(head=dummy.next;head;head=head->next)a.push_back(head->val);return a;',
 'tree_depth':'if(!root)return 0;queue<TreeNode*>q;q.push(root);int depth=0;while(!q.empty()){int size=q.size();depth++;while(size--){auto x=q.front();q.pop();if(x->left)q.push(x->left);if(x->right)q.push(x->right);}}return depth;',
 'tree_levels':'vector<int>a;queue<TreeNode*>q;if(root)q.push(root);while(!q.empty()){int size=q.size(),sum=0;while(size--){auto x=q.front();q.pop();sum+=x->val;if(x->left)q.push(x->left);if(x->right)q.push(x->right);}a.push_back(sum);}return a;',
 'tree_path':'if(!root)return 0;int best=root->val;function<int(TreeNode*)>visit=[&](TreeNode*x){if(!x)return 0;int l=max(0,visit(x->left)),r=max(0,visit(x->right));best=max(best,x->val+l+r);return x->val+max(l,r);};visit(root);return best;',
}

BOUNDS = {
 'string':'字符串仅含小写英文字母，长度 0 至 24。',
 'dp_climb':'0 ≤ n ≤ 15，结果在 int 范围内。',
 'dp_coins':'0 至 4 种不重复硬币，面额 1 至 9，amount 在 0 至 16。',
 'dp_edit':'a、b 仅含小写英文字母，长度 0 至 4。',
 'list':'链表长度 0 至 16，节点值在 [-9,9]，无环。k 在 [1,16]，倒数查找时 k 不超过链表长度。',
 'tree':'树层序序列长度 0 至 15，空节点使用 null，所有节点可从根到达，节点值在 [-9,9]。',
}


def bounds(key):
    return BOUNDS[key] if key.startswith('dp_') else BOUNDS[key.split('_')[0]]


def tags(key):
    return {'string':['字符串','滑动窗口' if key!='string_unique' else '哈希表'],
            'dp':['动态规划'], 'list':['链表'], 'tree':['二叉树','深度优先搜索']}[key.split('_')[0]]


def check_input(key, args):
    params = CONTRACTS[key][3]
    if set(args) != {n for n,_ in params}: raise ValueError('contract parameter mismatch')
    for name, kind in params:
        value = args[name]; plain_value(value, kind)
        if kind == 'string' and (len(value)>(4 if key=='dp_edit' else 24) or any(not 'a'<=c<='z' for c in value)):
            raise ValueError('lowercase bounded string required')
        if kind in ('list_node<int>', 'tree_node<int>'):
            if value is not None and (len(value)>(15 if name=='root' else 16) or any(v is not None and (type(v) is not int or not -9<=v<=9) for v in value)):
                raise ValueError('node bounds')
    if key=='dp_climb' and not 0<=args['n']<=15: raise ValueError('n bounds')
    if key=='dp_coins' and (not 0<=args['amount']<=16 or len(args['coins'])>4 or len(set(args['coins']))!=len(args['coins']) or any(not 1<=c<=9 for c in args['coins'])):
        raise ValueError('coin bounds')
    if 'k' in args and (not 1<=args['k']<=16 or (key=='list_kth' and args['k']>len(args['head'] or []))): raise ValueError('k bounds')


def inputs(key, seed):
    rng=random.Random(seed); result=[]
    for i in range(20):
        if key.startswith('string_'):
            samples=['','a','aaaa','abba','abcabc','aabbc','abxxab','cababac']
            s=samples[i] if i<len(samples) else ''.join(rng.choice('abcxyz') for _ in range(rng.randrange(25)))
            args={'s':s}
            if key=='string_window': args['t']=['','a','aa','ab','z','bc','ab','aac'][i%8]
        elif key=='dp_climb': args={'n':i%16}
        elif key=='dp_coins': args={'coins':[[],[1],[2],[2,4],[1,3,5],[2,5,9]][i%6], 'amount':i%17}
        elif key=='dp_edit':
            args={'a':['','a','ab','abcd','aaaa'][i%5], 'b':['','b','ba','dcba','bbbb'][i%5]} if i<5 else {n:''.join(rng.choice('abc') for _ in range(rng.randrange(5))) for n in ('a','b')}
        elif key.startswith('list_'):
            values=[] if i==0 else [rng.randint(-9,9) for _ in range(1+i%16)]
            if key=='list_kth' and not values: values=[0]
            args={'head':values}
            if key!='list_reverse': args['k']=1 if i%3==0 else len(values) if key=='list_kth' and i%3==1 else rng.randint(1,len(values) if key=='list_kth' else 16)
        else:
            trees=[[],[0],[-3,-2,-8],[1,None,2,3],[1,2,3,None,4],[1,2,None,3,None,4]]
            values=trees[i] if i<len(trees) else [rng.randint(-9,9) for _ in range(1+i%15)]
            args={'root':values}
        check_input(key,args); result.append(args)
    return result
