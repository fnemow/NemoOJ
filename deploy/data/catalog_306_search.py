"""Twenty search and advanced array exercises."""
from bisect import bisect_left, bisect_right
from catalog_306_common import add, RNG, arrays


def lower_bound_index(nums, target):return bisect_left(nums,target)


def upper_bound_index(nums, target):return bisect_right(nums,target)


def rotated_search(nums, target):
    left=0;right=len(nums)-1
    while left<=right:
        middle=(left+right)//2
        if nums[middle]==target:return middle
        if nums[left]<=nums[middle]:
            if nums[left]<=target<nums[middle]:right=middle-1
            else:left=middle+1
        elif nums[middle]<target<=nums[right]:left=middle+1
        else:right=middle-1
    return -1


def rotated_minimum(nums):
    left=0;right=len(nums)-1
    while left<right:
        middle=(left+right)//2
        if nums[middle]<nums[right]:right=middle
        elif nums[middle]>nums[right]:left=middle+1
        else:right-=1
    return nums[left]


def mountain_peak(nums):
    left=0;right=len(nums)-1
    while left<right:
        middle=(left+right)//2
        if nums[middle]<nums[middle+1]:left=middle+1
        else:right=middle
    return left


def floor_sqrt(n):
    left=0;right=n
    while left<right:
        middle=(left+right+1)//2
        if middle*middle<=n:left=middle
        else:right=middle-1
    return left


def kth_missing_positive(nums,k):
    left=0;right=len(nums)
    while left<right:
        middle=(left+right)//2
        if nums[middle]-middle-1<k:left=middle+1
        else:right=middle
    return left+k


def equal_value_range(nums,target):
    left=bisect_left(nums,target);right=bisect_right(nums,target)
    return [left,right-1] if left<right else [-1,-1]


def duplicated_number(nums):
    slow=nums[0];fast=nums[nums[0]]
    while slow!=fast:slow=nums[slow];fast=nums[nums[fast]]
    slow=0
    while slow!=fast:slow=nums[slow];fast=nums[fast]
    return slow


def first_missing_positive(nums):
    a=nums[:];n=len(a)
    for i in range(n):
        while 1<=a[i]<=n and a[a[i]-1]!=a[i]:j=a[i]-1;a[i],a[j]=a[j],a[i]
    for i,x in enumerate(a,1):
        if x!=i:return i
    return n+1


def multiplication_kth(rows, cols, k):
    left=1;right=rows*cols
    while left<right:
        middle=(left+right)//2
        if sum(min(cols,middle//i) for i in range(1,rows+1))>=k:right=middle
        else:left=middle+1
    return left


def shipping_capacity(weights, days):
    def needed(capacity):
        count=1;load=0
        for x in weights:
            if load+x>capacity:count+=1;load=0
            load+=x
        return count
    left=max(weights);right=sum(weights)
    while left<right:
        middle=(left+right)//2
        if needed(middle)<=days:right=middle
        else:left=middle+1
    return left


def bouquet_day(bloom, bouquets, size):
    if bouquets*size>len(bloom):return -1
    def possible(day):
        consecutive=count=0
        for x in bloom:
            consecutive=consecutive+1 if x<=day else 0
            if consecutive==size:count+=1;consecutive=0
        return count>=bouquets
    left=min(bloom);right=max(bloom)
    while left<right:
        middle=(left+right)//2
        if possible(middle):right=middle
        else:left=middle+1
    return left


def banana_speed(piles,hours):
    left=1;right=max(piles)
    while left<right:
        middle=(left+right)//2
        if sum((x+middle-1)//middle for x in piles)<=hours:right=middle
        else:left=middle+1
    return left


def split_largest_sum(nums,k):
    return shipping_capacity(nums,k)


def maximum_min_distance(positions,k):
    a=sorted(positions)
    def possible(distance):
        count=1;previous=a[0]
        for x in a[1:]:
            if x-previous>=distance:count+=1;previous=x
        return count>=k
    left=0;right=a[-1]-a[0]
    while left<right:
        middle=(left+right+1)//2
        if possible(middle):left=middle
        else:right=middle-1
    return left


def kth_pair_distance(nums,k):
    a=sorted(nums);left=0;right=a[-1]-a[0]
    def count(distance):
        start=total=0
        for end in range(len(a)):
            while a[end]-a[start]>distance:start+=1
            total+=end-start
        return total
    while left<right:
        middle=(left+right)//2
        if count(middle)>=k:right=middle
        else:left=middle+1
    return left


def important_reverse_pairs(nums):
    def sort_count(a):
        if len(a)<2:return a,0
        middle=len(a)//2;left,x=sort_count(a[:middle]);right,y=sort_count(a[middle:]);answer=x+y;j=0
        for value in left:
            while j<len(right) and value>2*right[j]:j+=1
            answer+=j
        merged=[];i=j=0
        while i<len(left) and j<len(right):
            if left[i]<=right[j]:merged.append(left[i]);i+=1
            else:merged.append(right[j]);j+=1
        return merged+left[i:]+right[j:],answer
    return sort_count(nums)[1]


def maximum_product(nums):
    high=low=best=nums[0]
    for x in nums[1:]:high,low=max(x,high*x,low*x),min(x,high*x,low*x);best=max(best,high)
    return best


def circular_maximum_sum(nums):
    high=low=best=worst=total=nums[0]
    for x in nums[1:]:high=max(x,high+x);low=min(x,low+x);best=max(best,high);worst=min(worst,low);total+=x
    return best if best<0 else max(best,total-worst)


def register():
    def put(title,diff,method,decl,ret,text,fn,examples,extras,bounds,idea,tags=()):
        add('search_arrays',title,diff,['数组',*tags],method,decl,ret,text,bounds,idea,fn,examples,extras)
    sortedcases=[(sorted(a),RNG.randint(-10,10)) for (a,) in arrays(16,0,20,-10,10)]
    sb='- nums 非递减，`0 ≤ n ≤ 10000`，元素与 target 在 ±10^6。'
    put('有序数组下界位置','简单','lowerBoundPosition','nums:int[],target:int','int','返回第一个 nums[i]≥target 的下标，不存在返回 n。',lower_bound_index,[(([1,2,2,4],2),1),(([],3),0)],sortedcases,sb,'二分半开区间，O(log n)。',['二分查找'])
    put('有序数组上界位置','简单','upperBoundPosition','nums:int[],target:int','int','返回第一个 nums[i]>target 的下标，不存在返回 n。',upper_bound_index,[(([1,2,2,4],2),3),(([],3),0)],sortedcases,sb,'二分寻找严格大于目标的起点，O(log n)。',['二分查找'])
    rotated=[];rotatedduplicates=[];mountains=[]
    for n in range(1,17):
        a=sorted(RNG.sample(range(-50,51),n));k=RNG.randrange(n);rotated.append((a[k:]+a[:k],RNG.choice(a+[100])))
        b=sorted(RNG.randint(-8,8) for _ in range(n));rotatedduplicates.append((b[k:]+b[:k],))
        up=RNG.randint(1,10);down=RNG.randint(1,10);mountains.append((list(range(up+1))+list(range(up-1,up-down-1,-1)),))
    put('搜索旋转后的不同值数组','中等','searchDistinctRotated','nums:int[],target:int','int','严格递增数组经过一次任意左旋，返回 target 的下标；不存在返回 -1。',rotated_search,[(([4,5,6,7,0,1,2],0),4),(([],1),-1)],rotated,'- `0 ≤ n ≤ 10000`，元素互不相同，元素与 target 在 ±10^6。','每次识别有序半边，O(log n)。',['二分查找'])
    put('含重复值旋转数组最小值','中等','minimumRotatedWithDuplicates','nums:int[]','int','非递减数组经过任意左旋，返回最小值，允许重复元素。',rotated_minimum,[(([2,2,2,0,1],),0),(([1],),1)],rotatedduplicates,'- `1 ≤ n ≤ 10000`，元素在 ±10^6。','比较中点与右端，相等时收缩右端；平均 O(log n)，最坏 O(n)。',['二分查找'])
    put('严格山脉数组的峰顶下标','简单','strictMountainPeak','nums:int[]','int','数组先严格递增再严格递减，峰顶不在两端。返回峰顶下标。',mountain_peak,[(([0,2,1,0],),1),(([1,3,5,4,2],),2)],mountains,'- `3 ≤ n ≤ 10000`，元素在 ±10^6，保证是严格山脉。','根据中点坡向二分，O(log n)。',['二分查找'])
    put('整数平方根向下取整','简单','integerSquareRoot','n:int','int','返回非负整数 n 的平方根向下取整，不使用库平方根。',floor_sqrt,[((8,),2),((0,),0)],[(n,) for n in [1,2,3,4,9,15,16,17,100,2147483647]],'- `0 ≤ n ≤ 2^31-1`。','二分查找，乘法用 64 位避免溢出，O(log n)。',['二分查找','数学'])
    missing=[(sorted(RNG.sample(range(1,70),n)),RNG.randint(1,30)) for n in range(0,16)]
    put('第 K 个缺失正整数','简单','kthAbsentPositive','nums:int[],k:int','int','nums 严格递增且为正数。返回从 1 开始、没有出现在 nums 中的第 k 个正整数。',kth_missing_positive,[(([2,3,4,7,11],5),9),(([],3),3)],missing,'- `0 ≤ n ≤ 10000`，`1 ≤ nums[i],k ≤ 10^6`。','下标 i 之前缺失数量为 nums[i]-i-1，二分缺失数边界，O(log n)。',['二分查找'])
    put('有序数组目标值首尾位置','中等','targetValueRange','nums:int[],target:int','int[]','返回 target 的首次和最后一次出现下标，不存在返回 [-1,-1]。',equal_value_range,[(([5,7,7,8,8,10],8),[3,4]),(([],1),[-1,-1])],sortedcases,sb,'两次二分查找上下界，O(log n)。',['二分查找'])
    duplicates=[]
    for n in range(1,17):a=list(range(1,n+1))+[RNG.randint(1,n)];RNG.shuffle(a);duplicates.append((a,))
    put('寻找唯一重复的正整数','中等','findOnlyDuplicate','nums:int[]','int','长度 n+1 的数组中所有值在 1..n，恰好一种值重复，其余不同。返回重复值；要求不修改数组且额外空间 O(1)。',duplicated_number,[(([1,3,4,2,2],),2),(([1,1],),1)],duplicates,'- `1 ≤ n ≤ 10000`，重复值可以出现两次以上。','把值视为下一下标，Floyd 判环并求环入口，O(n)。',['双指针'])
    put('最小缺失正整数','困难','firstAbsentPositive','nums:int[]','int','返回没有出现在 nums 中的最小正整数。要求 O(n) 时间、O(1) 额外空间，可修改输入数组。',first_missing_positive,[(([3,4,-1,1],),2),(([],),1)],arrays(16,0,25,-10,20),'- `0 ≤ n ≤ 10000`，元素在 ±10^6。','原地交换把值 x 放到位置 x-1，随后找第一个错位，O(n)。')
    tables=[(n,RNG.randint(1,15),1) for n in range(1,17)]
    tables=[(n,m,RNG.randint(1,n*m)) for n,m,_ in tables]
    put('乘法表中的第 K 小数','困难','kthMultiplicationTable','rows:int,cols:int,k:int','int','表格第 i 行第 j 列的值为 i*j，i、j 从 1 开始。返回所有格子值排序后的第 k 个，重复值分别计数。',multiplication_kth,[((3,3,5),3),((1,3,3),3)],tables,'- `1 ≤ rows,cols ≤ 1000`，`1 ≤ k ≤ rows*cols`。','二分答案，用每行 min(cols,答案/i) 统计数量，O(rows log(rows*cols))。',['二分查找','数学'])
    positives=arrays(16,1,20,1,30)
    put('按顺序运输包裹的最小载重','中等','minimumShippingCapacity','weights:int[],days:int','int','每天按数组顺序运走一段连续包裹，不能拆包、不能重排。要求在 days 天内全部运完，求船的最小载重，允许某天空闲。',shipping_capacity,[(([1,2,3,4,5,6,7,8,9,10],5),15),(([3],2),3)],[(a,RNG.randint(1,len(a)+3)) for (a,) in positives],'- `1 ≤ n ≤ 10000`，重量在 1..10000，`1 ≤ days ≤ 10000`。','二分载重，贪心模拟所需天数，O(n log 总重)。',['二分查找'])
    put('相邻花朵制作花束的最早日期','中等','earliestBouquetDay','bloom:int[],bouquets:int,size:int','int','第 i 朵花在 bloom[i] 天开放。每束需 size 朵相邻且已开放的花，花不能重复使用。返回制作 bouquets 束的最早天数，无法完成为 -1。',bouquet_day,[(([1,10,3,10,2],3,1),3),(([1,10,3,10,2],3,2),-1)],[(a,RNG.randint(1,10),RNG.randint(1,5)) for (a,) in positives],'- `1 ≤ n ≤ 10000`，开放日期 1..10^9，`1 ≤ bouquets,size ≤ 10000`。','二分日期，连续开放段累计可制作花束数，O(n log 最大日期)。',['二分查找'])
    put('吃完香蕉的最低速度','中等','minimumBananaSpeed','piles:int[],hours:int','int','每小时选择一堆香蕉，吃最多 speed 根；不足 speed 时该小时不吃其他堆。求在 hours 小时内吃完的最小正整数速度。',banana_speed,[(([3,6,7,11],8),4),(([30,11,23,4,20],5),30)],[(a,RNG.randint(len(a),len(a)*5)) for (a,) in positives],'- `1 ≤ n ≤ 10000`，堆大小 1..10^9，`n ≤ hours ≤ 10^9`。','二分速度，累计向上取整的小时数，O(n log 最大堆)。',['二分查找'])
    put('分割数组的最小最大段和','困难','minimumLargestSplitSum','nums:int[],k:int','int','将非负数组分成恰好 k 个非空连续段，使各段和的最大值最小，返回该最小值。',split_largest_sum,[(([7,2,5,10,8],2),18),(([0,0],2),0)],[(a,RNG.randint(1,len(a))) for (a,) in arrays(16,1,20,0,30)],'- `1 ≤ n ≤ 10000`，元素在 0..10000，`1 ≤ k ≤ n`。','二分最大段和，贪心计最少段数；非负元素可继续分段到恰好 k，O(n log 总和)。',['二分查找'])
    placements=[(sorted(RNG.sample(range(0,200),n)),RNG.randint(2,n)) for n in range(2,18)]
    put('放置 K 个点的最大最小间距','中等','maximizeMinimumPlacement','positions:int[],k:int','int','从不同整数坐标中选择 k 个，使相邻选中点之间最小距离尽量大，返回该最大距离。',maximum_min_distance,[(([1,2,4,8,9],3),3),(([0,10],2),10)],placements,'- `2 ≤ n ≤ 10000`，坐标互不相同且在 0..10^9，`2 ≤ k ≤ n`。','排序坐标、二分距离、贪心放置，O(n log 坐标范围)。',['二分查找','排序'])
    distances=[(a,RNG.randint(1,len(a)*(len(a)-1)//2)) for (a,) in arrays(16,2,20,-30,30)]
    put('第 K 小的数对绝对差','困难','kthSmallestPairDistance','nums:int[],k:int','int','所有下标对 i<j 的 |nums[i]-nums[j]| 排序，返回第 k 个。重复值和距离都按下标对重复计数。',kth_pair_distance,[(([1,3,1],1),0),(([1,6,1],3),5)],distances,'- `2 ≤ n ≤ 10000`，元素在 ±10^6，`1 ≤ k ≤ n(n-1)/2`。','排序后二分距离，用双指针统计不超过距离的数对，O(n log 值域)。',['二分查找','双指针'])
    put('重要翻转对数量','困难','importantReversePairCount','nums:int[]','long','统计 i<j 且 nums[i]>2*nums[j] 的下标对数量。注意负数和乘法溢出。',important_reverse_pairs,[(([1,3,2,3,1],),2),(([2,4,3,5,1],),3)],arrays(16)+[([2147483647,-2147483648],)],'- `0 ≤ n ≤ 10000`，元素在有符号 32 位范围。','归并排序中用双指针跨两半统计；比较乘法用 64 位，O(n log n)。',['排序','双指针'])
    put('连续子数组最大乘积','中等','maximumSubarrayProduct','nums:int[]','long','选择非空连续子数组，返回其元素乘积最大值。',maximum_product,[(([2,3,-2,4],),6),(([-2,0,-1],),0)],arrays(16,1,12,-3,3)+[([0]*10000,)],'- `1 ≤ n ≤ 10000`，元素在 -3..3；保证所有连续子数组乘积均在有符号 64 位范围。','同时维护以当前位置结束的最大、最小乘积，O(n)。',['动态规划'])
    put('环形数组最大连续和','中等','maximumCircularSubarraySum','nums:int[]','long','数组首尾相接，选择长度 1..n 的连续段（可跨末尾，每个位置最多一次），返回最大元素和。',circular_maximum_sum,[(([5,-3,5],),10),(([-3,-2,-3],),-2)],arrays(16,1,20,-20,20)+[([1]*10000,)],'- `1 ≤ n ≤ 10000`，元素在 ±10000。','最大线性段与总和减最小段比较；全负时单独处理，O(n)。',['动态规划'])
