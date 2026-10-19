"""Authored broad-topic exercises. All programs still need normal six-combination proof.

No privileged templateId or compile-cache scope is claimed for these candidates.
Explicit operation requests deliberately stay on the model generation path.
"""
import hashlib
import json
import random
import re
from nemo_agent.private_generation import prepare_candidate
from nemo_agent.schemas import CanonicalPrivateDraft


def matches_broad_goal(goal):
    normalized=re.sub(r'[\s_-]+','',str(goal).casefold()).strip('。.!！?？')
    return normalized in {'红黑树','学习红黑树','练习红黑树','红黑树学习','红黑树训练',
                          'redblacktree','learnredblacktree','redblacktrees'}


def inputs(seed):
    rng=random.Random(seed)
    cases=[]
    def add(keys,colors,left,right):
        cases.append(dict(root=0 if keys else -1,keys=keys,colors=colors,left=left,right=right))
    add([],[],[],[])
    add([0],[0],[-1],[-1])
    add([0],[1],[-1],[-1])
    add([0,-1,1],[0,1,1],[1,-1,-1],[2,-1,-1])
    add([0,-1,1],[0,0,0],[1,-1,-1],[2,-1,-1])
    add([0,-1,1],[0,1,0],[1,-1,-1],[2,-1,-1])
    add([0,1,-1],[0,1,1],[1,-1,-1],[2,-1,-1])
    add([0,0,1],[0,1,1],[1,-1,-1],[2,-1,-1])
    add([0,-1,1,-2],[0,1,1,1],[1,3,-1,-1],[2,-1,-1,-1])
    while len(cases)<20:
        n=rng.randint(1,12);left=[-1]*n;right=[-1]*n
        slots=[(0,left),(0,right)]
        for child in range(1,n):
            at=rng.randrange(len(slots));parent,side=slots.pop(at);side[parent]=child
            slots.extend(((child,left),(child,right)))
        order=[]
        def walk(node):
            if node<0:return
            walk(left[node]);order.append(node);walk(right[node])
        walk(0);values=sorted(rng.sample(range(-100,101),n));keys=[0]*n
        for node,value in zip(order,values):keys[node]=value
        if len(cases)%3==0 and n>1:keys[0]=keys[1]
        add(keys,[rng.randrange(2) for _ in range(n)],left,right)
    return cases


def authored_sources(difficulty):
    method={'EASY':'countRedEdges','MEDIUM':'hasEqualBlackHeight','HARD':'isRedBlackTree'}[difficulty]
    if difficulty=='EASY':
        body='total=0\nfor i in range(len(keys)):\n if colors[i]==1:\n  for child in (left[i],right[i]):\n   if child>=0 and colors[child]==1: total+=1\nreturn total'
        oracle='total=0\nfor a in range(len(keys)):\n for b in range(len(keys)):\n  if colors[a]==colors[b]==1 and (left[a]==b or right[a]==b): total+=1\nreturn total'
        java='int total=0;for(int i=0;i<keys.length;i++)if(colors[i]==1){if(left[i]>=0&&colors[left[i]]==1)total++;if(right[i]>=0&&colors[right[i]]==1)total++;}return total;'
        cpp='int total=0;for(int i=0;i<(int)keys.size();i++)if(colors[i]==1){if(left[i]>=0&&colors[left[i]]==1)total++;if(right[i]>=0&&colors[right[i]]==1)total++;}return total;'
        java_helper=cpp_helper='';ret='int'
    else:
        strict=difficulty=='HARD'
        guard='if root>=0 and colors[root]!=0: return False\n' if strict else ''
        constraints=' if not low<keys[node]<high: return -1\n if colors[node]==1 and any(c>=0 and colors[c]==1 for c in (left[node],right[node])): return -1\n' if strict else ''
        body=guard+'def visit(node,low,high):\n if node<0: return 1\n'+constraints+' a=visit(left[node],low,keys[node]);b=visit(right[node],keys[node],high)\n if a<0 or b<0 or a!=b: return -1\n return a+(colors[node]==0)\nreturn visit(root,-2**63,2**63-1)>=0'
        # Independently enumerate NIL paths and check ancestor constraints per node.
        oracle='if root<0: return True\n'
        if strict:
            oracle+='if colors[root]!=0: return False\nparent={}\nfor a in range(len(keys)):\n for child,is_left in ((left[a],True),(right[a],False)):\n  if child>=0: parent[child]=(a,is_left)\nfor node in range(len(keys)):\n current=node\n while current in parent:\n  ancestor,is_left=parent[current]\n  if is_left and keys[node]>=keys[ancestor]: return False\n  if not is_left and keys[node]<=keys[ancestor]: return False\n  current=ancestor\nfor node in range(len(keys)):\n if colors[node]==1:\n  for child in (left[node],right[node]):\n   if child>=0 and colors[child]==1: return False\n'
        oracle+='heights=[]\nstack=[(root,0)]\nwhile stack:\n node,black=stack.pop()\n if node<0: heights.append(black+1)\n else:\n  black+=colors[node]==0\n  stack.append((left[node],black));stack.append((right[node],black))\nreturn len(set(heights))<=1'
        java_constraints='if(keys[node]<=low||keys[node]>=high)return -1;if(colors[node]==1&&((left[node]>=0&&colors[left[node]]==1)||(right[node]>=0&&colors[right[node]]==1)))return -1;' if strict else ''
        cpp_constraints=java_constraints
        java_helper='private int visit(int node,int[] keys,int[] colors,int[] left,int[] right,long low,long high){if(node<0)return 1;'+java_constraints+'int a=visit(left[node],keys,colors,left,right,low,keys[node]),b=visit(right[node],keys,colors,left,right,keys[node],high);if(a<0||b<0||a!=b)return -1;return a+(colors[node]==0?1:0);}'
        cpp_helper='int visit(int node,const std::vector<int>& keys,const std::vector<int>& colors,const std::vector<int>& left,const std::vector<int>& right,long long low,long long high){if(node<0)return 1;'+cpp_constraints+'int a=visit(left[node],keys,colors,left,right,low,keys[node]),b=visit(right[node],keys,colors,left,right,keys[node],high);if(a<0||b<0||a!=b)return -1;return a+(colors[node]==0?1:0);}'
        java=('if(root>=0&&colors[root]!=0)return false;' if strict else '')+'return visit(root,keys,colors,left,right,Long.MIN_VALUE,Long.MAX_VALUE)>=0;'
        cpp=('if(root>=0&&colors[root]!=0)return false;' if strict else '')+'return visit(root,keys,colors,left,right,(-9223372036854775807LL-1),9223372036854775807LL)>=0;'
        ret='boolean'
    def py(content):return 'class Solution:\n def '+method+'(self,root,keys,colors,left,right):\n'+'\n'.join('  '+line for line in content.splitlines())+'\n'
    j='class Solution {'+java_helper+'public '+ret+' '+method+'(int root,int[] keys,int[] colors,int[] left,int[] right){'+java+'}}'
    c='class Solution {'+cpp_helper+'public: '+('bool' if ret=='boolean' else ret)+' '+method+'(int root,std::vector<int> keys,std::vector<int> colors,std::vector<int> left,std::vector<int> right){'+cpp+'}};'
    return method,ret,py(body),py(oracle),j,c


def build_redblack_candidate(request,task_id):
    difficulty=request['difficulty'];method,ret,optimized,oracle,java,cpp=authored_sources(difficulty)
    rules={
        'EASY':'返回父节点和子节点均为红色的边数。只统计已有父子边，不统计 NIL；不验证其他红黑树性质。',
        'MEDIUM':'返回从根到每个 NIL 的路径黑色节点数量是否一致。每条路径包含根和终点 NIL，NIL 为黑色。此题不检查根颜色、红色相邻或 BST 次序。',
        'HARD':'返回输入树是否同时满足严格二叉搜索树次序、根为黑色、红色节点没有红色孩子、每条根到 NIL 路径黑高相同。严格次序要求每个节点整个左子树键小于该节点、整个右子树键大于该节点，因此重复键判非法。NIL 为黑色，空树合法。',
    }
    statement='## 题目描述\n\n'+rules[difficulty]+'\n\n## 节点表示\n\nroot 是根编号。keys、colors、left、right 四个数组等长，编号为 0..n-1；colors 的 0 表示黑色，1 表示红色，子节点编号 -1 表示 NIL。空树 root=-1 且数组为空。非空输入保证无环、无共享子节点，所有节点从根可达。颜色性质与键次序不保证正确，必须按本题规则判断。\n\n## 数据范围\n\n0≤n≤200，键在[-100,100]，颜色只有0或1。实现 O(n) 算法；返回唯一整数或布尔值，不返回非唯一树形。\n'
    seed=int.from_bytes(hashlib.sha256(('redblack-bank-v1:'+difficulty).encode()).digest()[:8],'big')
    proposal=CanonicalPrivateDraft(title={'EASY':'红黑树：红色父子边计数','MEDIUM':'红黑树：黑高一致性','HARD':'红黑树：完整性质验证'}[difficulty],tags=['红黑树','二叉树'],difficulty=difficulty,contentMarkdown=statement,
        signature={'methodName':method,'parameters':[{'name':name,'type':'int' if name=='root' else 'int[]'} for name in ('root','keys','colors','left','right')],'returnType':ret},
        inputs=[json.dumps(case) for case in inputs(seed)],pythonSolution=optimized,pythonOracle=oracle,javaSolution=java,cppSolution=cpp,
        solutionStrategy='使用一次遍历汇总相邻红色边或子树黑高，并在困难题递归传播严格键值上下界。',
        oracleStrategy='独立枚举所有可能相邻节点对或所有到NIL的路径，逐节点沿祖先检查严格键序约束。')
    return prepare_candidate(proposal)
