"""Audited contracts and authored sources. Programs run only in the existing judge sandbox.

The model selects a contract and a short title label; it cannot change the contract,
data generator or references. Unknown requirements take the existing free-generation path.
"""
import hashlib
import json
import random
from nemo_agent.schemas import CanonicalPrivateDraft
from nemo_agent.private_generation import prepare_candidate
from nemo_agent import trusted_template_extensions as extensions

VERSION = "trusted-v1"
GENERATOR_VERSION = "legal-input-v2"
CATALOG = {
    "prefix_single": ("EASY", "前缀和：整数数组单次闭区间和", "rangeSum", [("nums","int[]"),("left","int"),("right","int")], "int"),
    "prefix_queries": ("MEDIUM", "前缀和：多次静态闭区间和", "rangeSums", [("nums","int[]"),("queries","int[][]")], "int[]"),
    "prefix_matrix": ("HARD", "二维前缀和：多次矩形区域和", "rectangleSums", [("matrix","int[][]"),("queries","int[][]")], "int[]"),
    "binary_first": ("EASY", "二分查找：有序数组中首次出现的目标索引", "firstIndex", [("nums","int[]"),("target","int")], "int"),
    "binary_bounds": ("MEDIUM", "二分查找：有序数组中目标的左右边界", "targetBounds", [("nums","int[]"),("target","int")], "int[]"),
    "binary_matrix_k": ("HARD", "值域二分：行列有序矩阵第k小，重复值逐个计数", "kthSmallest", [("matrix","int[][]"),("k","int")], "int"),
}

RULES = {
    "prefix_single": "返回 nums[left..right] 的整数和，区间两端均包含。0 ≤ left ≤ right < n。",
    "prefix_queries": "每个 queries[i]=[left,right]，按查询顺序返回对应闭区间的和。0 ≤ left ≤ right < n。",
    "prefix_matrix": "每个 queries[i]=[top,left,bottom,right]，返回两端均包含的矩形元素和，结果按查询顺序排列。0 ≤ top ≤ bottom < 行数，0 ≤ left ≤ right < 列数。",
    "binary_first": "返回 target 第一次出现的零起始索引；不存在返回 -1。nums 非递减，允许重复，空数组返回 -1。必须使用 O(log n) 查找。",
    "binary_bounds": "返回 [首次索引,最后索引]；不存在返回 [-1,-1]。nums 非递减，允许重复和空数组。必须使用 O(log n) 查找。",
    "binary_matrix_k": "matrix 是非空矩形，每行及每列非递减。将全部元素按非递减顺序排列，返回第 k 个元素（k 从 1 开始，重复元素逐个计数）。1 ≤ k ≤ 行数×列数。使用值域二分，不能展平排序。",
}

# Bodies are authored algorithm templates, not model-generated executable host code.
PYTHON = {
    "prefix_single": "p=[0]\nfor x in nums: p.append(p[-1]+x)\nreturn p[right+1]-p[left]",
    "prefix_queries": "p=[0]\nfor x in nums: p.append(p[-1]+x)\nreturn [p[r+1]-p[l] for l,r in queries]",
    "prefix_matrix": "m,n=len(matrix),len(matrix[0])\np=[[0]*(n+1) for _ in range(m+1)]\nfor i in range(m):\n for j in range(n): p[i+1][j+1]=matrix[i][j]+p[i][j+1]+p[i+1][j]-p[i][j]\nreturn [p[b+1][r+1]-p[t][r+1]-p[b+1][l]+p[t][l] for t,l,b,r in queries]",
    "binary_first": "l,r=0,len(nums)\nwhile l<r:\n mid=(l+r)//2\n if nums[mid]<target: l=mid+1\n else: r=mid\nreturn l if l<len(nums) and nums[l]==target else -1",
    "binary_bounds": "def bound(strict):\n l,r=0,len(nums)\n while l<r:\n  mid=(l+r)//2\n  if nums[mid]<target or (strict and nums[mid]==target): l=mid+1\n  else: r=mid\n return l\nl=bound(False)\nreturn [l,bound(True)-1] if l<len(nums) and nums[l]==target else [-1,-1]",
    "binary_matrix_k": "m,n=len(matrix),len(matrix[0])\nl,r=matrix[0][0],matrix[-1][-1]\nwhile l<r:\n mid=(l+r)//2\n count=0\n j=n-1\n for row in matrix:\n  while j>=0 and row[j]>mid: j-=1\n  count+=j+1\n if count<k: l=mid+1\n else: r=mid\nreturn l",
}
ORACLE = {
    "prefix_single": "total=0\nfor i in range(left,right+1): total+=nums[i]\nreturn total",
    "prefix_queries": "result=[]\nfor l,r in queries:\n total=0\n for i in range(l,r+1): total+=nums[i]\n result.append(total)\nreturn result",
    "prefix_matrix": "result=[]\nfor t,l,b,r in queries:\n total=0\n for i in range(t,b+1):\n  for j in range(l,r+1): total+=matrix[i][j]\n result.append(total)\nreturn result",
    "binary_first": "for i,x in enumerate(nums):\n if x==target: return i\nreturn -1",
    "binary_bounds": "indices=[i for i,x in enumerate(nums) if x==target]\nreturn [indices[0],indices[-1]] if indices else [-1,-1]",
    "binary_matrix_k": "values=[]\nfor row in matrix:\n for x in row: values.append(x)\nvalues.sort()\nreturn values[k-1]",
}
JAVA = {
    "prefix_single": "int[] p=new int[nums.length+1];for(int i=0;i<nums.length;i++)p[i+1]=p[i]+nums[i];return p[right+1]-p[left];",
    "prefix_queries": "int[] p=new int[nums.length+1],a=new int[queries.length];for(int i=0;i<nums.length;i++)p[i+1]=p[i]+nums[i];for(int i=0;i<a.length;i++)a[i]=p[queries[i][1]+1]-p[queries[i][0]];return a;",
    "prefix_matrix": "int m=matrix.length,n=matrix[0].length;int[][] p=new int[m+1][n+1];for(int i=0;i<m;i++)for(int j=0;j<n;j++)p[i+1][j+1]=matrix[i][j]+p[i][j+1]+p[i+1][j]-p[i][j];int[] a=new int[queries.length];for(int i=0;i<a.length;i++){int[] q=queries[i];a[i]=p[q[2]+1][q[3]+1]-p[q[0]][q[3]+1]-p[q[2]+1][q[1]]+p[q[0]][q[1]];}return a;",
    "binary_first": "int l=0,r=nums.length;while(l<r){int mid=l+(r-l)/2;if(nums[mid]<target)l=mid+1;else r=mid;}return l<nums.length&&nums[l]==target?l:-1;",
    "binary_bounds": "int l=0,r=nums.length;while(l<r){int m=l+(r-l)/2;if(nums[m]<target)l=m+1;else r=m;}int first=l;if(l==nums.length||nums[l]!=target)return new int[]{-1,-1};r=nums.length;while(l<r){int m=l+(r-l)/2;if(nums[m]<=target)l=m+1;else r=m;}return new int[]{first,l-1};",
    "binary_matrix_k": "int m=matrix.length,n=matrix[0].length,l=matrix[0][0],r=matrix[m-1][n-1];while(l<r){int mid=l+(r-l)/2,count=0,j=n-1;for(int i=0;i<m;i++){while(j>=0&&matrix[i][j]>mid)j--;count+=j+1;}if(count<k)l=mid+1;else r=mid;}return l;",
}
CPP = {
    "prefix_single": "vector<int> p(nums.size()+1);for(int i=0;i<(int)nums.size();i++)p[i+1]=p[i]+nums[i];return p[right+1]-p[left];",
    "prefix_queries": "vector<int> p(nums.size()+1),a;for(int i=0;i<(int)nums.size();i++)p[i+1]=p[i]+nums[i];for(auto q:queries)a.push_back(p[q[1]+1]-p[q[0]]);return a;",
    "prefix_matrix": "int m=matrix.size(),n=matrix[0].size();vector<vector<int>> p(m+1,vector<int>(n+1));for(int i=0;i<m;i++)for(int j=0;j<n;j++)p[i+1][j+1]=matrix[i][j]+p[i][j+1]+p[i+1][j]-p[i][j];vector<int>a;for(auto q:queries)a.push_back(p[q[2]+1][q[3]+1]-p[q[0]][q[3]+1]-p[q[2]+1][q[1]]+p[q[0]][q[1]]);return a;",
    "binary_first": "int l=0,r=nums.size();while(l<r){int mid=l+(r-l)/2;if(nums[mid]<target)l=mid+1;else r=mid;}return l<(int)nums.size()&&nums[l]==target?l:-1;",
    "binary_bounds": "int l=0,r=nums.size();while(l<r){int mid=l+(r-l)/2;if(nums[mid]<target)l=mid+1;else r=mid;}int first=l;if(l==(int)nums.size()||nums[l]!=target)return {-1,-1};r=nums.size();while(l<r){int mid=l+(r-l)/2;if(nums[mid]<=target)l=mid+1;else r=mid;}return {first,l-1};",
    "binary_matrix_k": "int m=matrix.size(),n=matrix[0].size(),l=matrix[0][0],r=matrix[m-1][n-1];while(l<r){int mid=l+(r-l)/2,count=0,j=n-1;for(int i=0;i<m;i++){while(j>=0&&matrix[i][j]>mid)j--;count+=j+1;}if(count<k)l=mid+1;else r=mid;}return l;",
}

CATALOG.update(extensions.CONTRACTS)
RULES.update(extensions.RULES)
PYTHON.update(extensions.PY)
ORACLE.update(extensions.ORACLE)
JAVA.update(extensions.JAVA)
CPP.update(extensions.CPP)

def catalog(difficulty):
    return [{"templateId":key,"description":value[1],"returnRule":RULES[key]} for key,value in CATALOG.items() if value[0]==difficulty]


def generate_inputs(key, seed):
    if key in extensions.CONTRACTS: return extensions.inputs(key,seed)
    rng=random.Random(seed);cases=[]
    for i in range(20):
        n=[1,1,2,4,8,20][i%6];nums=[rng.randint(-100,100) for _ in range(n)]
        if i==0:nums=[0]
        if i==1:nums=[-2,5,-3,4,-1,6,-7,8,-9]
        if key.startswith("prefix_"):
            if key=="prefix_matrix":
                rows,cols=(i%5)+1,((i*3)%6)+1
                matrix=[[rng.randint(-100,100) for _ in range(cols)] for _ in range(rows)]
                queries=[[0,0,rows-1,cols-1],[0,0,0,0]]
                for _ in range(5):
                    t,b=sorted((rng.randrange(rows),rng.randrange(rows)));l,r=sorted((rng.randrange(cols),rng.randrange(cols)));queries.append([t,l,b,r])
                case={"matrix":matrix,"queries":queries}
            else:
                n=len(nums);l,r=sorted((rng.randrange(n),rng.randrange(n)))
                case={"nums":nums,"left":l,"right":r} if key=="prefix_single" else {"nums":nums,"queries":[[0,n-1],[0,0],[n-1,n-1],[l,r]]}
        elif key=="binary_matrix_k":
            rows,cols=(i%5)+1,((i*3)%6)+1
            # Row/column order does not imply that row-major flattening is sorted.
            # Include interleaved rows so an invalid flattened binary search fails.
            base=rng.randint(-40,40)
            matrix=[[base for _ in range(cols)] for _ in range(rows)]
            for r in range(rows):
                for c in range(cols):
                    matrix[r][c]=max(matrix[r-1][c] if r else base,
                                     matrix[r][c-1] if c else base)+rng.randint(0,5)
            if i==1:matrix=[[1,3],[2,4]];rows=cols=2
            if i==2:matrix=[[0,0],[0,0]];rows=cols=2
            case={"matrix":matrix,"k":3 if i==1 else 1 if i%3==0 else rows*cols if i%3==1 else rng.randint(1,rows*cols)}
        else:
            nums=sorted(nums)
            if i%5==0:nums=[]
            if i%5==1:nums=[0,0,0,0]
            target=nums[len(nums)//2] if nums and i%3 else 101
            case={"nums":nums,"target":target}
        validate_input(key,case);cases.append(case)
    return cases


def validate_input(key, args):
    if key in extensions.CONTRACTS: return extensions.check_input(key,args)
    expected={name for name,_ in CATALOG[key][3]}
    if set(args)!=expected:raise ValueError("contract parameter mismatch")
    def integer(x):
        if type(x) is not int or not -100<=x<=100:raise ValueError("contract element range")
    if "matrix" in args:
        matrix=args["matrix"]
        if not matrix or len(matrix)>10 or not matrix[0] or len(matrix[0])>10 or any(len(row)!=len(matrix[0]) for row in matrix):raise ValueError("nonempty rectangular matrix required")
        for row in matrix:
            for x in row:integer(x)
        m,n=len(matrix),len(matrix[0])
        if key=="binary_matrix_k":
            if type(args["k"]) is not int or not 1<=args["k"]<=m*n:raise ValueError("invalid k")
            if any(row!=sorted(row) for row in matrix) or any(matrix[i][j]>matrix[i+1][j] for i in range(m-1) for j in range(n)):raise ValueError("matrix must be row/column sorted")
        else:
            if not 1<=len(args["queries"])<=20:raise ValueError("query count")
            for q in args["queries"]:
                if len(q)!=4 or any(type(v) is not int for v in q) or not (0<=q[0]<=q[2]<m and 0<=q[1]<=q[3]<n):raise ValueError("invalid rectangle bounds")
    else:
        nums=args["nums"]
        if len(nums)>200:raise ValueError("array length")
        for x in nums:integer(x)
        if key.startswith("binary_"):
            if nums!=sorted(nums):raise ValueError("array must be sorted")
            if type(args["target"]) is not int or not -101<=args["target"]<=101:raise ValueError("target range")
        else:
            if not nums:raise ValueError("nonempty array required")
            queries=[[args["left"],args["right"]]] if key=="prefix_single" else args["queries"]
            if not 1<=len(queries)<=20:raise ValueError("query count")
            for q in queries:
                if len(q)!=2 or any(type(v) is not int for v in q) or not 0<=q[0]<=q[1]<len(nums):raise ValueError("invalid interval bounds")


def python_source(key, oracle=False):
    _,_,method,params,_=CATALOG[key];body=(ORACLE if oracle else PYTHON)[key]
    return "class Solution:\n    def "+method+"(self, "+", ".join(n for n,_ in params)+"):\n"+"\n".join("        "+line for line in body.splitlines())+"\n"


def build_template(key, request, task_id, label=""):
    difficulty,title,method,params,ret=CATALOG[key]
    if request.get("difficulty")!=difficulty:raise ValueError("template difficulty mismatch")
    version='trusted-v2' if key in extensions.CONTRACTS else VERSION
    generator='legal-input-v3' if key in extensions.CONTRACTS else GENERATOR_VERSION
    # Audited fixed suites can be prevalidated. Private ownership/title remain per task.
    generator += '-bank-v1'
    seed=int.from_bytes(hashlib.sha256((version+generator+key).encode()).digest()[:8],"big")
    cases=generate_inputs(key,seed)
    type_java={"int":"int","int[]":"int[]","int[][]":"int[][]","string":"String","list_node<int>":"ListNode","tree_node<int>":"TreeNode"};type_cpp={"int":"int","int[]":"vector<int>","int[][]":"vector<vector<int>>","string":"string","list_node<int>":"ListNode*","tree_node<int>":"TreeNode*"}
    java="class Solution { public "+type_java[ret]+" "+method+"("+",".join(type_java[t]+" "+n for n,t in params)+") {"+JAVA[key]+"} }"
    cpp="class Solution { public: "+type_cpp[ret]+" "+method+"("+",".join(type_cpp[t]+" "+n for n,t in params)+") {"+CPP[key]+"} };"
    if key in extensions.CONTRACTS:
        bounds=extensions.bounds(key)
    elif "nums" in dict(params):
        bounds=("数组长度为0至200" if key.startswith("binary_") else "数组长度为1至200")+"，元素在[-100,100]。"
        if key.startswith("binary_"):bounds+="target 在[-101,101]。"
    else:bounds="矩阵行列均为1至10，元素在[-100,100]。"
    if "queries" in dict(params):bounds+="每份输入查询数为1至20。"
    statement="## 题目描述\n\n"+RULES[key]+"\n\n## 数据范围\n\n"+bounds+"\n\n核心代码模式实现指定 Solution 方法，无需处理输入输出。所有结果都是确定的，不允许返回任意一个可行答案。\n"
    proposal=CanonicalPrivateDraft(title=(label.strip()+" · " if label.strip() else "")+title,tags=extensions.tags(key) if key in extensions.CONTRACTS else ["前缀和" if key.startswith("prefix") else "二分查找","数组" if "nums" in dict(params) else "矩阵"],difficulty=difficulty,contentMarkdown=statement,
        signature={"methodName":method,"parameters":[{"name":n,"type":t} for n,t in params],"returnType":ret},inputs=[json.dumps(c,ensure_ascii=False) for c in cases],
        pythonSolution=python_source(key),pythonOracle=python_source(key,True),javaSolution=java,cppSolution=cpp,
        solutionStrategy="使用已审核的优化算法模板满足固定语义与复杂度要求。",oracleStrategy="使用独立穷举、递归、线性扫描或逐项累加对合法小输入核对结果。")
    candidate=prepare_candidate(proposal)
    candidate["generation"].update({"templateId":key,"templateVersion":version,"inputGeneratorVersion":generator,"inputSeed":str(seed),"returnRule":RULES[key]})
    return candidate
