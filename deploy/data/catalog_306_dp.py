"""Twenty dynamic programming exercises; counts explicitly use modulo 1e9+7."""
from functools import lru_cache
from catalog_306_common import add, RNG, arrays, strings


def knapsack(weights, values, capacity):
    dp=[0]*(capacity+1)
    for w,v in zip(weights,values):
        for c in range(capacity,w-1,-1):dp[c]=max(dp[c],dp[c-w]+v)
    return dp[capacity]


def unlimited_knapsack(weights, values, capacity):
    dp=[0]*(capacity+1)
    for w,v in zip(weights,values):
        for c in range(w,capacity+1):dp[c]=max(dp[c],dp[c-w]+v)
    return dp[capacity]


def bounded_knapsack(weights, values, counts, capacity):
    split_w=[];split_v=[]
    for w,v,count in zip(weights,values,counts):
        power=1
        while count:
            take=min(power,count);split_w.append(w*take);split_v.append(v*take);count-=take;power*=2
    return knapsack(split_w,split_v,capacity)


def equal_partition(nums):
    total=sum(nums)
    if total%2:return False
    bits=1
    for value in nums:bits|=bits<<value
    return bool((bits>>(total//2))&1)


def stone_difference(stones):
    total=sum(stones);bits=1
    for value in stones:bits|=bits<<value
    best=max(i for i in range(total//2+1) if (bits>>i)&1)
    return total-2*best


def target_signs(nums, target):
    ways={0:1}
    for value in nums:
        nxt={}
        for s,count in ways.items():
            for t in (s+value,s-value):nxt[t]=(nxt.get(t,0)+count)%1000000007
        ways=nxt
    return ways.get(target,0)


def coin_ways(coins, amount):
    dp=[0]*(amount+1);dp[0]=1
    for coin in coins:
        for total in range(coin,amount+1):dp[total]=(dp[total]+dp[total-coin])%1000000007
    return dp[amount]


def ordered_sum(nums, target):
    dp=[0]*(target+1);dp[0]=1
    for total in range(1,target+1):dp[total]=sum(dp[total-v] for v in nums if v<=total)%1000000007
    return dp[target]


def robber(nums):
    previous=current=0
    for x in nums:previous,current=current,max(current,previous+x)
    return current


def circular_robber(nums):
    return max(robber(nums[:-1]),robber(nums[1:])) if len(nums)>1 else sum(nums)


def stock_one(prices):
    low=10**9;best=0
    for x in prices:low=min(low,x);best=max(best,x-low)
    return best


def stock_unlimited(prices):return sum(max(0,b-a) for a,b in zip(prices,prices[1:]))


def stock_two(prices):
    buy1=buy2=-10**18;sell1=sell2=0
    for x in prices:buy1=max(buy1,-x);sell1=max(sell1,buy1+x);buy2=max(buy2,sell1-x);sell2=max(sell2,buy2+x)
    return sell2


def stock_cooldown(prices):
    hold=-10**18;sold=-10**18;rest=0
    for x in prices:hold,sold,rest=max(hold,rest-x),hold+x,max(rest,sold)
    return max(rest,sold)


def stock_fee(prices, fee):
    cash=0;hold=-10**18
    for x in prices:cash,hold=max(cash,hold+x-fee),max(hold,cash-x)
    return cash


def distinct_subsequences(s, t):
    dp=[0]*(len(t)+1);dp[0]=1
    for ch in s:
        for j in range(len(t),0,-1):
            if ch==t[j-1]:dp[j]=(dp[j]+dp[j-1])%1000000007
    return dp[-1]


def interleaving(s, t, combined):
    if len(s)+len(t)!=len(combined):return False
    dp=[False]*(len(t)+1);dp[0]=True
    for i in range(len(s)+1):
        for j in range(len(t)+1):
            if i==j==0:continue
            dp[j]=(i>0 and dp[j] and s[i-1]==combined[i+j-1]) or (j>0 and dp[j-1] and t[j-1]==combined[i+j-1])
    return dp[-1]


def wildcard(s, pattern):
    dp=[False]*(len(pattern)+1);dp[0]=True
    for j,ch in enumerate(pattern,1):dp[j]=dp[j-1] and ch=='*'
    for ch in s:
        nxt=[False]*(len(pattern)+1)
        for j,p in enumerate(pattern,1):nxt[j]=(nxt[j-1] or dp[j]) if p=='*' else (dp[j-1] and (p=='?' or p==ch))
        dp=nxt
    return dp[-1]


def palindrome_cuts(s):
    n=len(s)
    if not n:return 0
    pal=[[False]*n for _ in range(n)];dp=list(range(-1,n))
    for end in range(n):
        for start in range(end+1):
            if s[start]==s[end] and (end-start<2 or pal[start+1][end-1]):pal[start][end]=True;dp[end+1]=min(dp[end+1],dp[start]+1)
    return dp[n]


def burst_balloons(nums):
    a=[1]+nums+[1];n=len(a);dp=[[0]*n for _ in range(n)]
    for width in range(2,n):
        for left in range(n-width):
            right=left+width;dp[left][right]=max(dp[left][k]+dp[k][right]+a[left]*a[k]*a[right] for k in range(left+1,right))
    return dp[0][-1]


def register():
    def put(title,diff,method,decl,ret,text,fn,examples,extras,bounds,idea,tags=()):
        add('dynamic_programming',title,diff,['动态规划','数组',*tags],method,decl,ret,text,bounds,idea,fn,examples,extras)
    packs=[]
    for n in range(1,13):packs.append(([RNG.randint(1,9) for _ in range(n)],[RNG.randint(0,25) for _ in range(n)],RNG.randint(0,35)))
    pb='- 两数组等长，`0 ≤ n ≤ 100`，`1 ≤ weights[i] ≤ 100`，`0 ≤ values[i] ≤ 1000`，`0 ≤ capacity ≤ 1000`。'
    put('零一背包最大价值','中等','zeroOnePack','weights:int[],values:int[],capacity:int','int','每件物品只能选择一次，总重量不超过 capacity，返回可获得的最大总价值。允许不选物品。',knapsack,[(([2,3,4],[4,5,7],5),9),(([],[],0),0)],packs,pb,'按容量倒序更新 DP，O(nC)，空间 O(C)。')
    put('完全背包最大价值','中等','completePack','weights:int[],values:int[],capacity:int','int','每种物品可选择任意多件，总重量不超过 capacity，求最大总价值。',unlimited_knapsack,[(([2,3],[4,5],6),12),(([],[],5),0)],packs,pb,'按容量正序更新 DP，O(nC)。')
    put('多重背包最大价值','困难','boundedPack','weights:int[],values:int[],counts:int[],capacity:int','int','每种物品最多选 counts[i] 件，总重量不超过 capacity，求最大总价值。',bounded_knapsack,[(([2,3],[4,5],[1,2],6),10),(([],[],[],0),0)],[(w,v,[RNG.randint(0,6) for _ in w],c) for w,v,c in packs],pb+'\n- counts 与两数组等长，`0 ≤ counts[i] ≤ 100`。','二进制拆分数量后转为零一背包，O(n C log K)。')
    positives=arrays(12,0,15,0,20)
    put('等和子集划分','中等','canPartitionEqual','nums:int[]','boolean','将全部元素分成两个互不相交的子集，判断两个子集的元素和能否相同。允许空子集。',equal_partition,[(([1,5,11,5],),True),(([1,2,5],),False)],[( [],)]+positives,'- `0 ≤ n ≤ 100`，`0 ≤ nums[i] ≤ 100`。','总和为奇数时失败；布尔背包判断半和可达性，O(nS)。')
    put('两堆石头的最小重量差','中等','minimumStoneDifference','stones:int[]','int','将石头全部分入两堆，允许空堆。返回两堆重量和的最小绝对差。',stone_difference,[(([2,7,4,1,8,1],),1),(([],),0)],arrays(12,0,15,1,20),'- `0 ≤ n ≤ 100`，`1 ≤ stones[i] ≤ 100`。','寻找不超过总和一半的最大可达子集和；O(nS)。')
    put('目标和正负号方案数','中等','targetSignWays','nums:int[],target:int','int','给每个元素前分别放 + 或 -，使表达式值等于 target。统计方案数并对 1000000007 取模；两个零符号视为不同选择。空表达式值为 0。',target_signs,[(([1,1,1,1,1],3),5),(([0,0],0),4)],[(a,RNG.randint(-25,25)) for (a,) in positives],'- `0 ≤ n ≤ 100`，`0 ≤ nums[i] ≤ 20`，`-2000 ≤ target ≤ 2000`。','按当前和维护计数，O(nS)。')
    denominations=[(sorted(RNG.sample(range(1,12),RNG.randint(0,6))),RNG.randint(0,40)) for _ in range(14)]
    put('硬币组合方案数','中等','coinCombinationWays','coins:int[],amount:int','int','硬币面额互不相同，每种无限量。求凑成 amount 的组合数（不计排列顺序），对 1000000007 取模；金额 0 有一种空组合。',coin_ways,[(([1,2,5],5),4),(([],0),1)],denominations,'- `0 ≤ n ≤ 30`，面额在 `1..100`，`0 ≤ amount ≤ 1000`。','外层面额、内层金额正序，O(nA)。')
    put('有序整数组合数量','中等','orderedSumWays','nums:int[],target:int','int','nums 为不同正整数，每个可重复取。统计和为 target 的有序序列数，对 1000000007 取模；顺序不同算不同方案，目标为 0 时计空序列。',ordered_sum,[(([1,2,3],4),7),(([],0),1)],denominations,'- `0 ≤ n ≤ 30`，`1 ≤ nums[i] ≤ 100`，`0 ≤ target ≤ 1000`。','外层目标和、内层最后一个元素，O(nT)。')
    amounts=arrays(12,0,20,0,50)+[([1]*10000,)]
    rb='- `0 ≤ n ≤ 10000`，`0 ≤ nums[i] ≤ 10000`。'
    put('直线房屋的最大收益','中等','robLinearHouses','nums:int[]','int','选择一些直线排列房屋，不能同时选择相邻房屋。返回最大金额和，可不选。',robber,[(([2,7,9,3,1],),12),(([],),0)],amounts,rb,'维护选与不选状态，O(n)，空间 O(1)。')
    put('环形房屋的最大收益','中等','robCircularHouses','nums:int[]','int','房屋排列成环，首尾也相邻。选择不相邻房屋的最大金额和是多少？只有一个房屋时可选择它。',circular_robber,[(([2,3,2],),3),(([1],),1)],amounts,rb,'分别排除首房屋或末房屋做线性 DP；O(n)。')
    stocks=arrays(12,0,20,0,50)+[([1,2]*5000,)]
    sb='- `0 ≤ n ≤ 10000`，`0 ≤ prices[i] ≤ 10000`。\n- 必须先买后卖，每次最多持有一股，不做空；允许不交易。'
    put('一次股票交易的最大利润','简单','singleStockProfit','prices:int[]','int','最多完成一笔买入后卖出的交易，返回最大利润。',stock_one,[(([7,1,5,3,6,4],),5),(([7,6,4,3,1],),0)],stocks,sb,'维护此前最低价，O(n)。')
    put('不限次数股票交易的最大利润','中等','unlimitedStockProfit','prices:int[]','int','可进行任意多笔交易；同一天卖出后可以买入。返回最大利润。',stock_unlimited,[(([7,1,5,3,6,4],),7),(([1],),0)],stocks,sb,'累加相邻日的正向涨幅，O(n)。',['贪心'])
    put('至多两次股票交易','困难','twoStockTransactions','prices:int[]','int','最多完成两笔交易，第二笔买入不能早于第一笔卖出；允许同日卖出再买入。求最大利润。',stock_two,[(([3,3,5,0,0,3,1,4],),6),(([1,2,3,4,5],),4)],stocks,sb,'维护两次买入与卖出四个状态，O(n)。')
    put('带冷冻期的股票交易','中等','stockWithCooldown','prices:int[]','int','可任意多次交易，但卖出后下一整天禁止买入。求最大利润。',stock_cooldown,[(([1,2,3,0,2],),3),(([],),0)],stocks,sb,'持有、当天卖出、休息三状态 DP，O(n)。')
    put('带手续费的股票交易','中等','stockWithFee','prices:int[],fee:int','int','每次卖出支付 fee 的手续费，可以任意多次交易。返回扣除手续费后的最大利润。',stock_fee,[(([1,3,2,8,4,9],2),8),(([1],0),0)],[(a,RNG.randint(0,10)) for (a,) in stocks],sb+'\n- `0 ≤ fee ≤ 10000`。','维护持有与现金两状态，O(n)。')
    pairs=[(s,t) for (s,),(t,) in zip(strings(15,'ab',0,14),strings(15,'ab',0,8))]
    put('不同子序列的数量','困难','distinctSubsequenceWays','s:string,t:string','int','从 s 删除任意字符得到 t，按保留字符下标方案计数，对 1000000007 取模。空 t 有一种方案。',distinct_subsequences,[(('rabbbit','rabbit'),3),(('',''),1)],pairs+[('a'*100,'a'*50)],'- 字符为小写英文字母，`0 ≤ |s|,|t| ≤ 300`。','倒序更新 t 的前缀方案数，O(|s||t|)，空间 O(|t|)。',['字符串'])
    triples=[]
    for s,t in pairs:
        combined=''.join(x+y for x,y in zip(s,t))+s[min(len(s),len(t)):]+t[min(len(s),len(t)):]
        triples.append((s,t,combined));triples.append((s,t,combined+'c'))
    put('交错字符串判定','中等','isInterleaving','s:string,t:string,combined:string','boolean','判断 combined 是否由 s、t 交错组成，每个原串的字符顺序必须保持，并且所有字符恰好使用一次。',interleaving,[(('ab','cd','acbd'),True),(('ab','cd','adbc'),False)],triples,'- 小写英文字母，`0 ≤ |s|,|t| ≤ 200`，`0 ≤ |combined| ≤ 400`。','二维前缀 DP 压缩到一行，O(|s||t|)。',['字符串'])
    put('通配符整串匹配','困难','wildcardFullMatch','s:string,pattern:string','boolean','pattern 中 ? 匹配恰好一个字符，* 匹配任意长度（包括空串）的字符序列，其余小写字母按字面匹配。判断能否匹配 s 整串。',wildcard,[(('adceb','*a*b'),True),(('acdcb','a*c?b'),False)],[(s,p) for (s,),(p,) in zip(strings(14,'ab',0,16),strings(14,'ab?*',0,12))],'- `0 ≤ |s|,|pattern| ≤ 500`。','字符串与模式前缀 DP，O(nm)，空间 O(m)。',['字符串'])
    put('回文划分的最少切割','困难','minimumPalindromeCuts','s:string','int','将 s 切成若干非空回文串，返回所需最少切割次数。空串返回 0。',palindrome_cuts,[(('aab',),1),(('',),0)],strings(14,'abc',0,20)+[('a'*300,)],'- 小写英文字母，`0 ≤ |s| ≤ 500`。','先判定回文区间，再计算前缀最少段数，O(n²)。',['字符串'])
    put('戳气球的最大得分','困难','maximumBalloonCoins','nums:int[]','int','每次戳破一个气球，获得当前左右相邻气球的值与该值的乘积，缺少邻居视为 1。戳破全部气球可得到的最大总分是多少？',burst_balloons,[(([3,1,5,8],),167),(([],),0)],arrays(14,0,9,0,6)+[([2]*80,)],'- `0 ≤ n ≤ 100`，`0 ≤ nums[i] ≤ 100`。','区间 DP 枚举最后戳破的气球，O(n³)。')
