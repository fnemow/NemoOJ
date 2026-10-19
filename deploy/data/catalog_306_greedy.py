"""Twenty scheduling, greedy and heap exercises."""
from collections import Counter
import heapq
from catalog_306_common import add, RNG, arrays


def assign_cookies(children, cookies):
    children=sorted(children);cookies=sorted(cookies);i=0
    for cookie in cookies:
        if i<len(children) and cookie>=children[i]:i+=1
    return i


def can_jump(nums):
    reach=0
    for i,x in enumerate(nums):
        if i>reach:return False
        reach=max(reach,i+x)
    return True


def minimum_jumps(nums):
    end=reach=steps=0
    for i in range(len(nums)-1):
        if i>reach:return -1
        reach=max(reach,i+nums[i])
        if i==end:
            if reach==end:return -1
            steps+=1;end=reach
    return steps if end>=len(nums)-1 else -1


def gas_start(gas, cost):
    if sum(gas)<sum(cost):return -1
    # Prefix minima give the first valid start even when multiple starts exist.
    n=len(gas);diff=[a-b for a,b in zip(gas,cost)];prefix=[0]
    for x in diff+diff:prefix.append(prefix[-1]+x)
    from collections import deque
    q=deque();best=-1
    for i in range(1,2*n+1):
        while q and prefix[q[-1]]>=prefix[i]:q.pop()
        q.append(i)
        if i>=n:
            start=i-n
            while q and q[0]<=start:q.popleft()
            if start<n and prefix[q[0]]>=prefix[start]:best=start;break
    return best


def candy(ratings):
    n=len(ratings);left=[1]*n
    for i in range(1,n):
        if ratings[i]>ratings[i-1]:left[i]=left[i-1]+1
    right=1;answer=0
    for i in range(n-1,-1,-1):
        right=right+1 if i+1<n and ratings[i]>ratings[i+1] else 1
        answer+=max(left[i],right)
    return answer


def lemonade(bills):
    five=ten=0
    for bill in bills:
        if bill==5:five+=1
        elif bill==10:five-=1;ten+=1
        elif ten>0:ten-=1;five-=1
        else:five-=3
        if five<0:return False
    return True


def task_time(tasks, cooldown):
    if not tasks:return 0
    counts=Counter(tasks);high=max(counts.values());same=sum(v==high for v in counts.values())
    return max(len(tasks),(high-1)*(cooldown+1)+same)


def meeting_rooms(intervals):
    heap=[];answer=0
    for start,end in sorted(intervals):
        while heap and heap[0]<=start:heapq.heappop(heap)
        heapq.heappush(heap,end);answer=max(answer,len(heap))
    return answer


def attend_events(events):
    events=sorted(events);heap=[];i=day=answer=0
    while i<len(events) or heap:
        if not heap:day=max(day,events[i][0])
        while i<len(events) and events[i][0]<=day:heapq.heappush(heap,events[i][1]);i+=1
        while heap and heap[0]<day:heapq.heappop(heap)
        if heap:heapq.heappop(heap);answer+=1;day+=1
    return answer


def maximize_capital(capital, profits, initial, k):
    projects=sorted(zip(capital,profits));heap=[];i=0
    for _ in range(k):
        while i<len(projects) and projects[i][0]<=initial:heapq.heappush(heap,-projects[i][1]);i+=1
        if not heap:break
        initial-=heapq.heappop(heap)
    return initial


def schedule_courses(courses):
    heap=[];time=0
    for duration,deadline in sorted(courses,key=lambda row:row[1]):
        heapq.heappush(heap,-duration);time+=duration
        if time>deadline:time+=heapq.heappop(heap)
    return len(heap)


def refuel_stops(target, initial, stations):
    heap=[];reach=initial;i=answer=0
    while reach<target:
        while i<len(stations) and stations[i][0]<=reach:heapq.heappush(heap,-stations[i][1]);i+=1
        if not heap:return -1
        reach-=heapq.heappop(heap);answer+=1
    return answer


def merge_cost(lengths):
    heap=list(lengths);heapq.heapify(heap);total=0
    while len(heap)>1:
        value=heapq.heappop(heap)+heapq.heappop(heap);total+=value;heapq.heappush(heap,value)
    return total


def smash_stones(stones):
    heap=[-x for x in stones];heapq.heapify(heap)
    while len(heap)>1:
        a=-heapq.heappop(heap);b=-heapq.heappop(heap)
        if a>b:heapq.heappush(heap,b-a)
    return -heap[0] if heap else 0


def smallest_k(nums, k):return heapq.nsmallest(k,nums)


def largest_kth(nums, k):return heapq.nlargest(k,nums)[-1]


def top_frequencies(nums, k):
    frequency=Counter(nums)
    return sorted(frequency,key=lambda x:(-frequency[x],x))[:k]


def twice_stream_medians(nums):
    left=[];right=[];result=[]
    for x in nums:
        heapq.heappush(left,-x);heapq.heappush(right,-heapq.heappop(left))
        if len(right)>len(left):heapq.heappush(left,-heapq.heappop(right))
        result.append(-2*left[0] if len(left)>len(right) else -left[0]+right[0])
    return result


def sorted_matrix_kth(matrix, k):
    heap=[(row[0],i,0) for i,row in enumerate(matrix)];heapq.heapify(heap)
    for _ in range(k):
        value,i,j=heapq.heappop(heap)
        if j+1<len(matrix[i]):heapq.heappush(heap,(matrix[i][j+1],i,j+1))
    return value


def weighted_jobs(jobs):
    from bisect import bisect_right
    jobs=sorted(jobs,key=lambda row:row[1]);ends=[];dp=[0]
    for start,end,profit in jobs:
        compatible=bisect_right(ends,start);dp.append(max(dp[-1],dp[compatible]+profit));ends.append(end)
    return dp[-1]


def register():
    def put(title,diff,method,decl,ret,text,fn,examples,extras,bounds,idea,tags=()):
        add('heap_greedy',title,diff,['堆与贪心','数组',*tags],method,decl,ret,text,bounds,idea,fn,examples,extras)
    positive=arrays(14,0,20,0,20)
    put('分配饼干的最多满足人数','简单','maximumSatisfiedChildren','children:int[],cookies:int[]','int','每个孩子至少需要 children[i] 大小的一个饼干。每个饼干至多给一人，返回最多能满足的孩子人数。',assign_cookies,[(([1,2,3],[1,1]),1),(([],[1]),0)],[(a,b) for (a,),(b,) in zip(positive,arrays(14,0,20,0,20))],'- 两数组长度≤10000，元素在 0..10000。','排序后用双指针匹配，O(n log n+m log m)。',['排序','双指针'])
    jumps=arrays(14,1,25,0,5)+[([0],),([1]*10000,)]
    jb='- `1 ≤ n ≤ 10000`，`0 ≤ nums[i] ≤ 10000`。'
    put('跳跃游戏可达性','中等','canReachLastIndex','nums:int[]','boolean','从下标 0 出发，在 i 可向右跳 1..nums[i] 格，也可停留。判断是否能到达末尾。',can_jump,[(([2,3,1,1,4],),True),(([3,2,1,0,4],),False)],jumps,jb,'维护可达最远位置，O(n)。')
    put('跳跃游戏最少步数','中等','minimumJumpCount','nums:int[]','int','从下标 0 出发，每步可向右跳 1..nums[i] 格。返回到达末尾的最少步数；不可达返回 -1。',minimum_jumps,[(([2,3,1,1,4],),2),(([0,1],),-1)],jumps,jb,'分层贪心扩展最远边界，O(n)。')
    fuels=[(a,[RNG.randint(0,20) for _ in a]) for (a,) in arrays(14,1,20,0,20)]
    put('环形加油站的最小起点','中等','minimumGasStationStart','gas:int[],cost:int[]','int','汽车从空油箱出发，在站 i 加 gas[i] 单位油，行驶到下一站耗 cost[i]。油箱无限大，返回能绕环一周的最小站点下标；无解为 -1。',gas_start,[(([1,2,3,4,5],[3,4,5,1,2]),3),(([0,0],[0,0]),0)],fuels,'- 两数组等长，`1 ≤ n ≤ 10000`，元素在 0..10000。','对双倍净油量前缀和做滑窗最小值，逐个检查起点，O(n)。',['单调栈'])
    put('按评分分糖果的最小总量','困难','minimumCandyTotal','ratings:int[]','long','每人至少得到一颗糖，评分高于相邻人时糖也必须更多。返回最少糖果总数。',candy,[(([1,0,2],),5),(([1,2,2],),4)],positive+[([i for i in range(10000)],)],'- `0 ≤ n ≤ 10000`，评分在 0..10000。','分别从左右扫描，取两方向需求最大值，O(n)。')
    bills=[([RNG.choice([5,10,20]) for _ in range(n)],) for n in range(1,15)]
    put('柠檬水找零','简单','canMakeLemonadeChange','bills:int[]','boolean','饮品每杯 5 元，顾客依次支付 5/10/20 元，每人买一杯。开始没有零钱，判断能否只用此前收款为每人找零。',lemonade,[(([5,5,5,10,20],),True),(([5,5,10,10,20],),False)],[( [],)]+bills,'- `0 ≤ n ≤ 10000`，面额仅为 5、10、20。','20 元优先用一张 10 加一张 5，保留更多 5 元；O(n)。')
    taskcases=[([RNG.randint(0,4) for _ in range(n)],RNG.randint(0,6)) for n in range(1,15)]
    put('带冷却间隔的任务调度','中等','minimumTaskIntervals','tasks:int[],cooldown:int','int','相同编号任务之间至少隔 cooldown 个时间单位。每单位完成一个任务或空闲，可任意重排。返回完成全部任务的最短总时间。',task_time,[(([0,0,0,1,1,1],2),8),(([],3),0)],taskcases,'- `0 ≤ n ≤ 10000`，任务编号 0..25，`0 ≤ cooldown ≤ 100`。','利用最高频率构建框架，答案 max(n,(最高频率-1)(冷却+1)+最高频种类数)，O(n)。',['哈希表'])
    intervals=[([[s:=RNG.randint(0,25),s+RNG.randint(1,8)] for _ in range(n)],) for n in range(1,15)]
    ib='- `0 ≤ n ≤ 1000`，每行 [start,end]，`0 ≤ start < end ≤ 10000`。'
    put('会议室最少数量','中等','minimumMeetingRooms','intervals:int[][]','int','会议使用半开区间 [start,end)，同一会议室内不能重叠。返回举行全部会议所需的最少会议室数。',meeting_rooms,[(([[0,30],[5,10],[15,20]],),2),(([[1,2],[2,3]],),1)],[( [],)]+intervals,ib,'排序起点，用最小堆回收已结束会议室，O(n log n)。',['排序'])
    put('最多参加一天活动的数量','中等','maximumDailyEvents','events:int[][]','int','每场活动在闭区间 [start,end] 内任选一天参加，每天最多参加一场。返回最多参加活动数。',attend_events,[(([[1,2],[2,3],[3,4]],),3),(([[1,1],[1,1]],),1)],[( [],)]+intervals,ib.replace('start < end','start ≤ end'),'按天扫描，可选活动中优先参加最早结束的；O(n log n)。',['排序'])
    projectcases=[([RNG.randint(0,20) for _ in a],a,RNG.randint(0,15),RNG.randint(0,8)) for (a,) in positive]
    put('选择项目的最大最终资本','困难','maximumFinalCapital','capital:int[],profits:int[],initial:int,k:int','long','项目启动需要当前资本至少 capital[i]，完成后增加 profits[i]，启动资本不扣除。每个项目至多一次，最多做 k 个，返回最大最终资本。',maximize_capital,[(([0,1,1],[1,2,3],0,2),4),(([],[],5,3),5)],projectcases,'- 两数组等长且长度≤1000，要求和利润在 0..10000，`0 ≤ initial ≤ 10000`，`0 ≤ k ≤ 1000`。','按要求排序，最大堆选择当前可用项目的最大利润，O(n log n+k log n)。',['排序'])
    courses=[([[RNG.randint(1,20),RNG.randint(1,60)] for _ in range(n)],) for n in range(1,15)]
    put('限截止时间的最多课程','困难','maximumScheduledCourses','courses:int[][]','int','每行 [duration,deadline]。从时刻 0 开始顺序学习，不重叠、不暂停；完成时刻不得超过截止时间。可自由重排，返回最多能完成多少门。',schedule_courses,[(([[100,200],[200,1300],[1000,1250],[2000,3200]],),3),(([],),0)],courses,'- `0 ≤ n ≤ 1000`，duration、deadline 在 1..10000。','按截止时间排序，若超时则移除已选最长课程，O(n log n)。',['排序'])
    stations=[(100,RNG.randint(0,60),[[i*10,RNG.randint(1,35)] for i in range(1,n+1)]) for n in range(0,10)]
    put('到达终点的最少加油次数','困难','minimumRefuelStops','target:int,initial:int,stations:int[][]','int','从位置 0 向 target 行驶，每单位距离耗油 1。起始油 initial，油箱无限大，站点 [position,fuel] 按位置递增。到达站可选择加全部油，求最少加油次数，无解为 -1。',refuel_stops,[((100,10,[[10,60],[20,30],[30,30],[60,40]]),2),((1,1,[]),0)],stations,'- `1 ≤ target ≤ 10^6`，`0 ≤ initial ≤ 10^6`，最多 1000 个站点，`0 < position < target`，fuel 在 1..10^6。','最大堆保存已到达站点的油量，无法前进时取最大者，O(n log n)。')
    put('合并绳子的最小费用','中等','minimumRopeMergeCost','lengths:int[]','long','每次选择两根绳子，付两者长度和的费用并合并为一根。求合并全部绳子的最小总费用，零或一根时为 0。',merge_cost,[(([2,3,4],),14),(([1],),0)],positive,'- `0 ≤ n ≤ 10000`，长度在 0..10^6。','每次合并最短两根，最小堆实现 Huffman 贪心，O(n log n)。')
    put('碰撞石头的最后重量','简单','lastStoneWeight','stones:int[]','int','每次取最重两块 x≥y；相等则两块消失，否则留下 x-y。返回最终重量，无石头为 0。',smash_stones,[(([2,7,4,1,8,1],),1),(([],),0)],positive,'- `0 ≤ n ≤ 10000`，重量在 0..10000。','最大堆重复取两块，O(n log n)。')
    put('数组中最小的 K 个元素','简单','smallestKValues','nums:int[],k:int','int[]','返回数组中最小的 k 个元素，包含重复值，结果升序排列。',smallest_k,[(([3,1,2,1],2),[1,1]),(([],0),[])],[(a,RNG.randint(0,len(a))) for (a,) in arrays(14)],'- `0 ≤ n ≤ 10000`，元素在 ±10^6，`0 ≤ k ≤ n`。','大小为 k 的最大堆，O(n log(k+1))，或排序。',['排序'])
    nonempty=arrays(14,1,20,-20,20)
    put('数组中的第 K 大元素','中等','kthLargestValue','nums:int[],k:int','int','返回降序排序后的第 k 个元素，重复值按各自位置计数，k 从 1 开始。',largest_kth,[(([3,2,1,5,6,4],2),5),(([1,1],2),1)],[(a,RNG.randint(1,len(a))) for (a,) in nonempty],'- `1 ≤ n ≤ 10000`，元素在 ±10^6，`1 ≤ k ≤ n`。','大小为 k 的最小堆，O(n log k)。')
    put('出现频率最高的 K 个值','中等','topKFrequentValues','nums:int[],k:int','int[]','选出现次数最高的 k 个不同值，按频次降序输出；频次相同按值升序。',top_frequencies,[(([1,1,1,2,2,3],2),[1,2]),(([2,1],2),[1,2])],[(a,RNG.randint(0,len(set(a)))) for (a,) in arrays(14)],'- `0 ≤ n ≤ 10000`，元素在 ±10^6，`0 ≤ k ≤ 不同值数量`。','频率哈希表后用堆或排序选取，O(n+u log u)。',['哈希表','排序'])
    put('数据流中位数的两倍','困难','twicePrefixMedians','nums:int[]','int[]','按顺序插入 nums。返回每次插入后当前所有数中位数的两倍；偶数数量时为中间两个数之和，避免浮点误差。',twice_stream_medians,[(([1,2,3],),[2,3,4]),(([-3,-1],),[-6,-4])],arrays(14)+[([1]*10000,)],'- `0 ≤ n ≤ 10000`，元素在 ±10^6。','最大堆维护较小一半、最小堆维护较大一半，O(n log n)。')
    matrices=[]
    for n in range(1,14):
        m=RNG.randint(1,6);matrix=[sorted(RNG.randint(-20,20) for _ in range(m)) for _ in range(n)];matrices.append((matrix,RNG.randint(1,n*m)))
    put('行有序矩阵的第 K 小元素','中等','kthSmallestRowSorted','matrix:int[][],k:int','int','矩阵每行非递减，不要求列有序。将所有元素合并排序，返回第 k 小元素，重复值分别计数。',sorted_matrix_kth,[(([[1,4],[2,3]],3),3),(([[5]],1),5)],matrices,'- `1 ≤ 行数,列数 ≤ 100`，元素在 ±10^6，`1 ≤ k ≤ 元素总数`。','最小堆多路归并有序行，O((行数+k)log 行数)。',['矩阵'])
    jobs=[([[s:=RNG.randint(0,30),s+RNG.randint(1,10),RNG.randint(0,30)] for _ in range(n)],) for n in range(0,15)]
    put('带利润任务的最优排程','困难','maximumJobProfit','jobs:int[][]','int','任务为 [start,end,profit]，占用半开区间，选择不重叠任务使利润和最大。允许不选，端点相接合法。',weighted_jobs,[(([[1,3,50],[2,4,10],[3,5,40],[3,6,70]],),120),(([],),0)],jobs,'- `0 ≤ n ≤ 1000`，`0 ≤ start < end ≤ 10000`，profit 在 0..10000。','按结束时刻排序，用二分找到前一个兼容任务并 DP，O(n log n)。',['动态规划','二分查找','排序'])
