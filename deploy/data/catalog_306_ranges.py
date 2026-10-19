"""Twenty prefix, difference and range-query exercises."""
from collections import deque
from bisect import bisect_left, bisect_right
import math
from catalog_306_common import add, RNG, arrays


def range_sums(nums, queries):
    prefix=[0]
    for x in nums:prefix.append(prefix[-1]+x)
    return [prefix[r+1]-prefix[l] for l,r in queries]


def range_xors(nums, queries):
    prefix=[0]
    for x in nums:prefix.append(prefix[-1]^x)
    return [prefix[r+1]^prefix[l] for l,r in queries]


def range_gcds(nums, queries):
    answer=[]
    for l,r in queries:
        g=0
        for x in nums[l:r+1]:g=math.gcd(g,x)
        answer.append(g)
    return answer


def range_minima(nums, queries):
    n=len(nums);table=[nums[:]];power=1
    while power*2<=n:
        previous=table[-1];table.append([min(previous[i],previous[i+power]) for i in range(n-2*power+1)]);power*=2
    return [min(table[(r-l+1).bit_length()-1][l],table[(r-l+1).bit_length()-1][r-(1<<((r-l+1).bit_length()-1))+1]) for l,r in queries]


def range_additions(nums, updates):
    diff=[0]*(len(nums)+1)
    for l,r,x in updates:diff[l]+=x;diff[r+1]-=x
    total=0;result=[]
    for i,x in enumerate(nums):total+=diff[i];result.append(x+total)
    return result


def point_updates(nums, operations):
    n=len(nums);tree=[0]*(n+1);values=nums[:];answer=[]
    def add_value(i,value):
        i+=1
        while i<=n:tree[i]+=value;i+=i&-i
    def prefix(i):
        result=0
        while i>0:result+=tree[i];i-=i&-i
        return result
    for i,x in enumerate(nums):add_value(i,x)
    for kind,a,b in operations:
        if kind==0:add_value(a,b-values[a]);values[a]=b
        else:answer.append(prefix(b+1)-prefix(a))
    return answer


def dynamic_range_add(nums, operations):
    n=len(nums);first=[0]*(n+2);second=[0]*(n+2)
    def update(tree,i,x):
        while i<=n+1:tree[i]+=x;i+=i&-i
    def read(tree,i):
        value=0
        while i:value+=tree[i];i-=i&-i
        return value
    def interval(l,r,x):
        l+=1;r+=1
        update(first,l,x);update(first,r+1,-x);update(second,l,x*(l-1));update(second,r+1,-x*r)
    def prefix(i):return read(first,i)*i-read(second,i)
    for i,x in enumerate(nums):interval(i,i,x)
    answer=[]
    for kind,l,r,x in operations:
        if kind==0:interval(l,r,x)
        else:answer.append(prefix(r+1)-prefix(l))
    return answer


def rectangle_sums(matrix, queries):
    n=len(matrix);m=len(matrix[0]);prefix=[[0]*(m+1) for _ in range(n+1)]
    for i in range(n):
        for j in range(m):prefix[i+1][j+1]=matrix[i][j]+prefix[i][j+1]+prefix[i+1][j]-prefix[i][j]
    return [prefix[c+1][d+1]-prefix[a][d+1]-prefix[c+1][b]+prefix[a][b] for a,b,c,d in queries]


def rectangle_add_flat(rows, cols, updates):
    diff=[[0]*(cols+1) for _ in range(rows+1)]
    for a,b,c,d,x in updates:diff[a][b]+=x;diff[c+1][b]-=x;diff[a][d+1]-=x;diff[c+1][d+1]+=x
    answer=[]
    for i in range(rows):
        for j in range(cols):
            if i:diff[i][j]+=diff[i-1][j]
            if j:diff[i][j]+=diff[i][j-1]
            if i and j:diff[i][j]-=diff[i-1][j-1]
            answer.append(diff[i][j])
    return answer


def divisible_subarrays(nums, k):
    count={0:1};s=answer=0
    for x in nums:s=(s+x)%k;answer+=count.get(s,0);count[s]=count.get(s,0)+1
    return answer


def longest_target_sum(nums, target):
    first={0:-1};s=best=0
    for i,x in enumerate(nums):
        s+=x
        if s-target in first:best=max(best,i-first[s-target])
        first.setdefault(s,i)
    return best


def balanced_binary(nums):return longest_target_sum([1 if x else -1 for x in nums],0)


def pivot_indices(nums):
    total=sum(nums);left=0;answer=[]
    for i,x in enumerate(nums):
        if left==total-left-x:answer.append(i)
        left+=x
    return answer


def products_except_self(nums):
    answer=[1]*len(nums);value=1
    for i,x in enumerate(nums):answer[i]=value;value*=x
    value=1
    for i in range(len(nums)-1,-1,-1):answer[i]*=value;value*=nums[i]
    return answer


def count_smaller_right(nums):
    values=sorted(set(nums));tree=[0]*(len(values)+1);answer=[]
    for x in reversed(nums):
        index=bisect_left(values,x);i=index;total=0
        while i:total+=tree[i];i-=i&-i
        answer.append(total);i=index+1
        while i<len(tree):tree[i]+=1;i+=i&-i
    return answer[::-1]


def count_range_sums(nums, lower, upper):
    prefix=[0]
    for x in nums:prefix.append(prefix[-1]+x)
    values=sorted(set(prefix));tree=[0]*(len(values)+1);answer=0
    def read(i):
        total=0
        while i:total+=tree[i];i-=i&-i
        return total
    for s in prefix:
        answer+=read(bisect_right(values,s-lower))-read(bisect_left(values,s-upper))
        i=bisect_left(values,s)+1
        while i<len(tree):tree[i]+=1;i+=i&-i
    return answer


def shortest_signed_subarray(nums, target):
    prefix=[0]
    for x in nums:prefix.append(prefix[-1]+x)
    queue=deque();answer=len(nums)+1
    for i,s in enumerate(prefix):
        while queue and s-prefix[queue[0]]>=target:answer=min(answer,i-queue.popleft())
        while queue and prefix[queue[-1]]>=s:queue.pop()
        queue.append(i)
    return -1 if answer>len(nums) else answer


def maximum_rectangle_sum(matrix):
    n=len(matrix);m=len(matrix[0]);best=-10**18
    for top in range(n):
        sums=[0]*m
        for bottom in range(top,n):
            current=0
            for j in range(m):sums[j]+=matrix[bottom][j];current=max(sums[j],current+sums[j]);best=max(best,current)
    return best


def submatrix_target_count(matrix, target):
    n=len(matrix);m=len(matrix[0]);answer=0
    for top in range(n):
        sums=[0]*m
        for bottom in range(top,n):
            frequency={0:1};s=0
            for j in range(m):sums[j]+=matrix[bottom][j];s+=sums[j];answer+=frequency.get(s-target,0);frequency[s]=frequency.get(s,0)+1
    return answer


def maximum_integer_coverage(intervals):
    events={}
    for left,right in intervals:events[left]=events.get(left,0)+1;events[right+1]=events.get(right+1,0)-1
    count=answer=0
    for position in sorted(events):count+=events[position];answer=max(answer,count)
    return answer


def register():
    def put(title,diff,method,decl,ret,text,fn,examples,extras,bounds,idea,tags=()):
        add('ranges',title,diff,['前缀和与区间查询','数组',*tags],method,decl,ret,text,bounds,idea,fn,examples,extras)
    datasets=[]
    for (a,) in arrays(15,1,20,-20,20):
        q=[]
        for _ in range(8):l=RNG.randrange(len(a));q.append([l,RNG.randrange(l,len(a))])
        datasets.append((a,q))
    qb='- `1 ≤ n ≤ 1000`，元素在 ±10000，最多 1000 次查询，每行 [l,r] 为合法闭区间，0≤l≤r<n。空查询集合合法。'
    put('静态数组区间求和','简单','staticRangeSums','nums:int[],queries:int[][]','long[]','按查询顺序返回 nums[l..r] 的元素和，下标从 0 开始。',range_sums,[(([1,2,3],[[0,2],[1,1]]),[6,2]),(([5],[]),[])],datasets,qb,'前缀和，预处理 O(n)、每次查询 O(1)。')
    put('静态数组区间异或','简单','staticRangeXors','nums:int[],queries:int[][]','int[]','按查询顺序返回闭区间 nums[l..r] 的按位异或。',range_xors,[(([1,3,4,8],[[0,1],[1,2]]),[2,7]),(([0],[]),[])],[([abs(x) for x in a],q) for a,q in datasets],qb.replace('±10000','0..10000'),'异或前缀，区间异或等于两端前缀异或，O(n+q)。',['位运算'])
    put('静态数组区间最大公约数','中等','staticRangeGcds','nums:int[],queries:int[][]','int[]','返回每个闭区间所有元素的非负最大公约数，gcd(0,0)=0。',range_gcds,[(([12,18,24],[[0,2],[1,1]]),[6,18]),(([0],[[0,0]]),[0])],datasets,qb,'稀疏表利用 gcd 幂等性，O(n log n+q)。',['数学'])
    put('静态数组区间最小值','中等','staticRangeMinima','nums:int[],queries:int[][]','int[]','返回每个闭区间的最小元素值。',range_minima,[(([3,-1,2],[[0,2],[2,2]]),[-1,2]),(([7],[]),[])],datasets,qb,'稀疏表存储 2^k 区间最小值，O(n log n+q)。')
    updates=[(a,[row+[RNG.randint(-10,10)] for row in q]) for a,q in datasets]
    put('批量区间加法后的数组','中等','batchRangeAdd','nums:int[],updates:int[][]','int[]','每行 [l,r,delta] 表示给闭区间所有元素加 delta，按全部更新后的顺序返回数组。',range_additions,[(([1,2,3],[[0,1,5],[1,2,-1]]),[6,6,2]),(([0],[]),[0])],updates,qb.replace('查询，每行 [l,r]','更新，每行 [l,r,delta]')+'\n- delta 在 ±10000。','差分数组记录更新端点，最后前缀还原；O(n+q)。')
    operations=[(a,[[1,l,r] if i%2 else [0,l,RNG.randint(-30,30)] for i,(l,r) in enumerate(q)]) for a,q in datasets]
    put('单点赋值与区间求和','中等','pointAssignmentRangeSum','nums:int[],operations:int[][]','long[]','操作 [0,index,value] 将单点赋为 value；[1,l,r] 查询闭区间和。依序执行，仅输出所有查询的答案。',point_updates,[(([1,2,3],[[1,0,2],[0,1,5],[1,1,2]]),[6,8]),(([0],[]),[])],operations,qb+'\n- operations 至多 1000 行，操作格式如描述，赋值在 ±10000。','树状数组记录单点差值，O((n+q)log n)。')
    dynamic=[(a,[[i%2,l,r,RNG.randint(-10,10) if i%2==0 else 0] for i,(l,r) in enumerate(q)]) for a,q in datasets]
    put('动态区间加法与区间求和','困难','rangeAdditionRangeSum','nums:int[],operations:int[][]','long[]','操作 [0,l,r,delta] 给闭区间加 delta；[1,l,r,0] 查询闭区间和。依序执行，输出全部查询答案。',dynamic_range_add,[(([1,2,3],[[0,0,1,2],[1,0,2,0]]),[10]),(([0],[[1,0,0,0]]),[0])],dynamic,qb+'\n- 操作至多 1000 行，delta 在 ±10000。','两棵树状数组维护差分及加权差分，O((n+q)log n)。')
    matrices=[];rectqueries=[];rectupdates=[]
    for n in range(1,14):
        m=RNG.randint(1,7);mat=[[RNG.randint(-8,8) for _ in range(m)] for _ in range(n)];queries=[]
        for _ in range(6):a=RNG.randrange(n);b=RNG.randrange(m);queries.append([a,b,RNG.randrange(a,n),RNG.randrange(b,m)])
        matrices.append((mat,));rectqueries.append((mat,queries));rectupdates.append((n,m,[q+[RNG.randint(-5,5)] for q in queries]))
    mb='- `1 ≤ 行数,列数 ≤ 50`，元素在 ±1000，矩阵为矩形。'
    put('二维矩阵矩形区域求和','中等','matrixRectangleSums','matrix:int[][],queries:int[][]','long[]','查询 [top,left,bottom,right] 表示包含边界的子矩形，返回每个区域的元素和。坐标均从 0 开始。',rectangle_sums,[(([[1,2],[3,4]],[[0,0,1,1],[1,0,1,1]]),[10,7]),(([[5]],[]),[])],rectqueries,mb+'\n- 查询至多 1000 个，坐标合法且 top≤bottom、left≤right。','二维前缀和容斥，O(nm+q)。',['矩阵'])
    put('二维矩形增量更新','中等','rectangleIncrementFlat','rows:int,cols:int,updates:int[][]','int[]','初始 rows×cols 矩阵全为 0。更新 [top,left,bottom,right,delta] 为闭矩形各格加 delta。返回最终矩阵按行从上到下、每行从左到右展开的一维数组。',rectangle_add_flat,[((2,2,[[0,0,1,1,2],[0,1,0,1,3]]),[2,5,2,2]),((1,1,[]),[0])],rectupdates,'- `1 ≤ rows,cols ≤ 50`，最多 1000 次合法闭矩形更新，delta 在 ±1000。','四角差分后二维前缀恢复，O(nm+q)。',['矩阵'])
    nums=arrays(14,0,25,-10,10)+[([0]*10000,)]
    put('和能被 K 整除的子数组数','中等','divisibleSubarrayCount','nums:int[],k:int','long','统计非空连续子数组中元素和能被正整数 k 整除的数量。',divisible_subarrays,[(([4,5,0,-2,-3,1],5),7),(([],3),0)],[(a,RNG.randint(1,8)) for (a,) in nums],'- `0 ≤ n ≤ 10000`，元素在 ±10000，`1 ≤ k ≤ 10000`。','前缀余数频次，负余数需规范到 0..k-1，O(n+k)。',['哈希表'])
    put('目标和的最长连续子数组','中等','longestSubarrayWithSum','nums:int[],target:int','int','返回和为 target 的最长非空连续子数组长度；不存在返回 0。可含负数。',longest_target_sum,[(([1,-1,5,-2,3],3),4),(([],0),0)],[(a,RNG.randint(-20,20)) for (a,) in nums],'- `0 ≤ n ≤ 10000`，元素在 ±10000，target 在 ±10^8。','记录每种前缀和的最早下标，O(n)。',['哈希表'])
    put('零一数量相等的最长子数组','中等','longestBalancedBinary','nums:int[]','int','0/1 数组中，返回 0 与 1 数量相等的最长连续子数组长度。不存在返回 0。',balanced_binary,[(([0,1,0],),2),(([1,1],),0)],arrays(14,0,25,0,1)+[([0,1]*5000,)],'- `0 ≤ n ≤ 10000`，元素仅为 0 或 1。','把 0 视为 -1，寻找最长零和区间，O(n)。',['哈希表'])
    put('全部数组平衡下标','简单','allPivotIndices','nums:int[]','int[]','下标 i 左边元素和等于右边元素和则为平衡下标，不包含 nums[i]。按升序返回全部平衡下标。',pivot_indices,[(([1,7,3,6,5,6],),[3]),(([0,0],),[0,1])],[(a[:1000],) for (a,) in nums],'- `0 ≤ n ≤ 10000`，元素在 ±10000。','总和与已扫描前缀和比较，O(n)。')
    put('除自身以外的数组乘积','中等','productWithoutSelf','nums:int[]','long[]','结果第 i 项为所有其他元素的乘积，不允许使用除法。空数组返回空数组；长度为 1 时返回 [1]。',products_except_self,[(([1,2,3,4],),[24,12,8,6]),(([0,2,0],),[0,0,0])],arrays(16,0,12,-3,3),'- `0 ≤ n ≤ 10000`，元素在 -3..3；保证所有前缀、后缀及除自身乘积都在有符号 64 位范围。','左右乘积两遍扫描，O(n)，除返回结果之外 O(1) 空间。')
    put('每个元素右侧较小值数量','困难','countSmallerToRight','nums:int[]','int[]','返回每个位置右侧严格小于该元素的元素个数；重复元素分别计数，但相等不算较小。',count_smaller_right,[(([5,2,6,1],),[2,1,1,0]),(([2,2],),[0,0])],nums,'- `0 ≤ n ≤ 10000`，元素在 ±10000。','离散化后从右往左用树状数组计频次，O(n log n)。',['二分查找'])
    range_cases=[]
    for (a,) in nums:l=RNG.randint(-20,10);range_cases.append((a,l,RNG.randint(l,30)))
    put('指定和区间的子数组数量','困难','countSubarraySumRange','nums:int[],lower:int,upper:int','long','统计和位于闭区间 [lower,upper] 的非空连续子数组数。',count_range_sums,[(([-2,5,-1],-2,2),3),(([],0,0),0)],range_cases,'- `0 ≤ n ≤ 10000`，元素在 ±10000，`-10^8 ≤ lower ≤ upper ≤ 10^8`。','前缀和离散化后用树状数组查区间频次，O(n log n)。',['二分查找'])
    put('含负数的最短达标子数组','困难','shortestSubarrayAtLeast','nums:int[],target:int','int','返回和至少为正整数 target 的最短非空连续子数组长度，数组可有负数；不存在返回 -1。',shortest_signed_subarray,[(([2,-1,2],3),3),(([1,2],4),-1)],[(a,RNG.randint(1,30)) for (a,) in nums],'- `0 ≤ n ≤ 10000`，元素在 ±10000，`1 ≤ target ≤ 10^8`。','前缀和加单调双端队列，O(n)。',['单调栈'])
    put('二维矩阵最大子矩形和','困难','maximumSubrectangleSum','matrix:int[][]','long','选择至少一个格子的连续子矩形，返回最大的元素和；全负矩阵也必须选非空矩形。',maximum_rectangle_sum,[(([[1,-2],[-3,4]],),4),(([[-5]],),-5)],matrices,mb,'枚举上下边界，列压缩后用 Kadane，O(n²m)。',['矩阵','动态规划'])
    put('二维矩阵目标和子矩形数','困难','countTargetSubmatrices','matrix:int[][],target:int','long','统计元素和等于 target 的非空连续子矩形数量。',submatrix_target_count,[(([[0,1,0],[1,1,1],[0,1,0]],0),4),(([[0]],0),1)],[(m,RNG.randint(-10,10)) for (m,) in matrices],mb+'\n- target 在 ±10^6。','枚举上下边界，列压缩后统计目标和子数组，O(n²m)。',['矩阵','哈希表'])
    intervals=[([[l:=RNG.randint(-20,20),l+RNG.randint(0,8)] for _ in range(n)],) for n in range(0,15)]
    put('整数区间的最大覆盖次数','中等','maximumIntegerOverlap','intervals:int[][]','int','每个闭区间 [l,r] 覆盖所有整数坐标 l..r，返回某个整数被最多区间同时覆盖的次数；无区间返回 0。',maximum_integer_coverage,[(([[1,2],[2,3]],),2),(([[0,0],[1,1]],),1)],intervals,'- `0 ≤ n ≤ 10000`，`-10^8 ≤ l ≤ r ≤ 10^8`。','在 l 加 1，在 r+1 减 1，排序事件并累计，O(n log n)。',['排序'])
