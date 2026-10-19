"""Twenty number-theory and combinatorics exercises."""
import math
from catalog_306_common import add, RNG


def gcd_pair(a,b):return math.gcd(a,b)


def lcm_pair(a,b):return a//math.gcd(a,b)*b if a and b else 0


def modular_power(base, exponent, modulus):
    result=1%modulus;base%=modulus
    while exponent:
        if exponent&1:result=result*base%modulus
        base=base*base%modulus;exponent>>=1
    return result


def modular_inverse(a, modulus):
    old_r,r=a,modulus;old_s,s=1,0
    while r:q=old_r//r;old_r,r=r,old_r-q*r;old_s,s=s,old_s-q*s
    return old_s%modulus if old_r==1 else -1


def factorial_zeroes(n):
    answer=0
    while n:n//=5;answer+=n
    return answer


def happy_number(n):
    seen=set()
    while n!=1 and n not in seen:seen.add(n);n=sum(int(ch)**2 for ch in str(n))
    return n==1


def perfect_number(n):
    if n<=1:return False
    total=1
    for d in range(2,math.isqrt(n)+1):
        if n%d==0:total+=d+(n//d if d*d!=n else 0)
    return total==n


def prime_factor_count(n):
    answer=0;d=2
    while d*d<=n:
        while n%d==0:n//=d;answer+=1
        d+=1
    return answer+(n>1)


def divisor_count(n):
    answer=1;d=2
    while d*d<=n:
        exponent=0
        while n%d==0:n//=d;exponent+=1
        answer*=exponent+1;d+=1
    return answer*2 if n>1 else answer


def totient(n):
    result=n;d=2
    while d*d<=n:
        if n%d==0:
            result-=result//d
            while n%d==0:n//=d
        d+=1
    if n>1:result-=result//n
    return result


def interval_primes(left, right):
    marked=[True]*(right+1)
    if right>=0:marked[0]=False
    if right>=1:marked[1]=False
    for d in range(2,math.isqrt(right)+1):
        if marked[d]:
            for j in range(d*d,right+1,d):marked[j]=False
    return sum(marked[left:right+1])


def binomial(n, k):return math.comb(n,k)%1000000007


def catalan(n):return (math.comb(2*n,n)//(n+1))%1000000007


def derangements(n):
    if n==0:return 1
    previous,current=1,0
    for i in range(2,n+1):previous,current=current,(i-1)*(current+previous)%1000000007
    return current


def integer_break(n):
    if n<=3:return n-1
    product=1
    while n>4:product*=3;n-=3
    return product*n


def count_digit_one(n):
    answer=0;place=1
    while place<=n:
        high=n//(place*10);current=n//place%10;low=n%place
        answer+=high*place+(place if current>1 else low+1 if current==1 else 0);place*=10
    return answer


def nth_ugly(n):
    values=[1];a=b=c=0
    while len(values)<n:
        value=min(values[a]*2,values[b]*3,values[c]*5);values.append(value)
        if value==values[a]*2:a+=1
        if value==values[b]*3:b+=1
        if value==values[c]*5:c+=1
    return values[-1]


def josephus(n,k):
    answer=0
    for size in range(2,n+1):answer=(answer+k)%size
    return answer


def floor_quotient_sum(n):
    answer=0;left=1
    while left<=n:q=n//left;right=n//q;answer+=q*(right-left+1);left=right+1
    return answer


def two_congruences(a, m, b, n):
    g=math.gcd(m,n)
    if (b-a)%g:return -1
    reduced=n//g
    multiplier=0 if reduced==1 else ((b-a)//g*modular_inverse(m//g,reduced))%reduced
    return (a+m*multiplier)%(m//g*n)


def register():
    def put(title,diff,method,decl,ret,text,fn,examples,extras,bounds,idea,tags=()):
        add('math',title,diff,['数学',*tags],method,decl,ret,text,bounds,idea,fn,examples,extras)
    pairs=[(RNG.randint(0,10000),RNG.randint(0,10000)) for _ in range(16)]+[(0,0),(0,7),(7,0)]
    put('两个非负整数的最大公约数','简单','greatestCommonDivisor','a:int,b:int','int','返回 a、b 的非负最大公约数，约定 gcd(0,0)=0。',gcd_pair,[((48,18),6),((0,7),7)],pairs,'- `0 ≤ a,b ≤ 10^9`。','欧几里得辗转相除，O(log max(a,b))。')
    put('两个非负整数的最小公倍数','简单','leastCommonMultiple','a:int,b:int','long','返回最小公倍数。任一参数为 0 时返回 0，注意结果可能超过 32 位范围。',lcm_pair,[((12,18),36),((0,7),0)],pairs+[(999999,1000000)],'- `0 ≤ a,b ≤ 10^6`。','先除 gcd 再相乘，避免中间溢出，O(log max(a,b))。')
    powers=[(RNG.randint(0,100000),RNG.randint(0,100000),RNG.randint(1,10000)) for _ in range(16)]
    put('快速幂取模','中等','powerModulo','base:int,exponent:int,modulus:int','int','返回 base 的 exponent 次方对 modulus 取模，约定 0 的 0 次方为 1。',modular_power,[((2,10,1000),24),((0,0,1),0)],powers+[(1000000000,1000000000,999999937)],'- `0 ≤ base,exponent ≤ 10^9`，`1 ≤ modulus ≤ 10^9`。','二进制快速幂，乘法使用 64 位，O(log exponent)。',['位运算'])
    inverse=[(RNG.randint(0,10000),RNG.randint(2,10000)) for _ in range(16)]
    put('整数的模逆元','中等','modularMultiplicativeInverse','a:int,modulus:int','int','寻找最小非负 x，使 a*x 对 modulus 取模为 1。不存在返回 -1。',modular_inverse,[((3,11),4),((2,4),-1)],inverse,'- `0 ≤ a ≤ 10^9`，`2 ≤ modulus ≤ 10^9`。','扩展欧几里得；gcd 不为 1 无逆元，O(log modulus)。')
    put('阶乘末尾零的数量','简单','factorialTrailingZeroes','n:int','int','返回 n! 的十进制表示末尾连续零个数，0!=1。',factorial_zeroes,[((25,),6),((0,),0)],[(n,) for n in [1,2,4,5,10,24,26,100,125,1000,1000000000]],'- `0 ≤ n ≤ 10^9`。','累计 n/5+n/25+…，O(log n)。')
    put('快乐数判定','简单','isHappyNumber','n:int','boolean','反复把正整数变为各十进制数位平方之和。若最终变成 1 则为快乐数，否则不是。',happy_number,[((19,),True),((2,),False)],[(n,) for n in [1,3,4,7,10,11,20,68,100,999,2147483647]],'- `1 ≤ n ≤ 2^31-1`。','集合记录循环状态，或快慢指针检测环。')
    put('完全数判定','简单','isPerfectNumber','n:int','boolean','正整数的全部正真因子（不包括自身）之和等于自身则为完全数。判断 n 是否为完全数。',perfect_number,[((28,),True),((1,),False)],[(n,) for n in [2,3,6,12,24,496,8128,10000,99991,100000000]],'- `1 ≤ n ≤ 10^8`。','枚举到平方根，并成对累计因子，O(√n)。')
    numbers=[(n,) for n in [1,2,3,4,6,12,16,25,36,97,1000,99991,1000000000]]
    put('质因子总数量','简单','primeFactorsWithMultiplicity','n:int','int','把 n 分解为质数乘积，返回质因子个数，重复质因子重复计数。1 的答案为 0。',prime_factor_count,[((12,),3),((1,),0)],numbers,'- `1 ≤ n ≤ 10^9`。','试除并重复消去同一质因子，O(√n)。')
    put('正整数约数数量','简单','positiveDivisorCount','n:int','int','返回 n 的正约数个数，包含 1 与 n。',divisor_count,[((12,),6),((1,),1)],numbers,'- `1 ≤ n ≤ 10^9`。','质因子指数分解，约数数为各 (指数+1) 的乘积，O(√n)。')
    put('欧拉函数值','中等','eulerTotient','n:int','int','统计 1..n 中与 n 互质的整数个数，约定 φ(1)=1。',totient,[((9,),6),((1,),1)],numbers,'- `1 ≤ n ≤ 10^9`。','对每个不同质因子 p 做 answer-=answer/p，O(√n)。')
    ranges=[(left:=RNG.randint(0,5000),left+RNG.randint(0,100)) for _ in range(15)]
    put('闭区间内质数的个数','中等','primeCountInInterval','left:int,right:int','int','返回闭区间 [left,right] 内质数个数。0 和 1 不是质数。',interval_primes,[((1,10),4),((0,1),0)],ranges+[(0,1000000)],'- `0 ≤ left ≤ right ≤ 10^6`。','埃氏筛或分段筛，O(right log log right)。')
    combinations=[(n,RNG.randint(0,n)) for n in range(0,16)]+[(1000,500)]
    put('组合数取模','中等','binomialModulo','n:int,k:int','int','从 n 个不同元素中选 k 个，不计顺序，返回组合数对 1000000007 的余数。',binomial,[((5,2),10),((0,0),1)],combinations,'- `0 ≤ k ≤ n ≤ 1000`。','帕斯卡 DP 或阶乘与逆元，O(nk) 或 O(n+log MOD)。',['动态规划'])
    put('合法括号序列的数量','中等','balancedBracketSequenceCount','n:int','int','使用 n 对圆括号，统计合法括号序列数量，对 1000000007 取模。空序列计一种。',catalan,[((3,),5),((0,),1)],[(n,) for n in list(range(1,16))+[100,500]],'- `0 ≤ n ≤ 500`。','卡特兰数 C(2n,n)/(n+1)，注意模除法需要逆元；也可 DP，O(n²)。',['动态规划'])
    put('错排排列数量','中等','derangementCount','n:int','int','将 0..n-1 排列，要求任何位置 i 的值都不能为 i，统计排列数量并对 1000000007 取模。n=0 时空排列计一种。',derangements,[((3,),2),((0,),1)],[(n,) for n in list(range(1,16))+[10000]],'- `0 ≤ n ≤ 10000`。','D(n)=(n-1)(D(n-1)+D(n-2))，O(n)，空间 O(1)。',['动态规划'])
    put('整数拆分的最大乘积','中等','maximumIntegerSplitProduct','n:int','long','将 n 拆成至少两个正整数，返回它们乘积的最大值。',integer_break,[((10,),36),((2,),1)],[(n,) for n in list(range(3,17))+[58]],'- `2 ≤ n ≤ 58`。','优先拆出 3，余数 1 时改为两个 2；O(n)。',['堆与贪心'])
    put('零到 N 中数字一的总出现次数','困难','countDecimalOnes','n:int','long','统计整数 0..n 的通常十进制表示中数字 1 的总出现次数，不允许前导零。',count_digit_one,[((13,),6),((0,),0)],[(n,) for n in [1,9,10,11,19,20,99,100,101,999,1000000000]],'- `0 ≤ n ≤ 10^9`。','按数位统计高位、当前位、低位贡献，O(log n)。')
    put('第 N 个丑数','中等','nthUglyNumber','n:int','int','只含质因子 2、3、5 的正整数称为丑数，1 也算。返回递增序列中的第 n 个丑数。',nth_ugly,[((10,),12),((1,),1)],[(n,) for n in list(range(2,16))+[1690]],'- `1 ≤ n ≤ 1690`。','三指针生成下一个 2/3/5 倍候选，相同值同时推进，O(n)。',['动态规划'])
    josephus_cases=[(n,RNG.randint(1,100)) for n in range(1,17)]+[(10000,1000000000)]
    put('约瑟夫环最后的编号','中等','josephusSurvivor','n:int,k:int','int','0..n-1 围成圆，从 0 开始计数 1，每次数到 k 的人离开，从下一人重新计数。返回最后一人编号。',josephus,[((5,3),3),((1,100),0)],josephus_cases,'- `1 ≤ n ≤ 10000`，`1 ≤ k ≤ 10^9`。','f(1)=0，f(i)=(f(i-1)+k)%i；O(n)。')
    put('整除商的求和','困难','floorDivisionSum','n:int','long','返回 sum(floor(n/i))，其中 i 遍历 1..n。n=0 时返回 0。',floor_quotient_sum,[((5,),10),((0,),0)],[(n,) for n in [1,2,3,4,6,7,10,99,100,1000000000]],'- `0 ≤ n ≤ 10^9`。','相同商的连续 i 区间合并计算，O(√n)。')
    congruences=[]
    for _ in range(16):m=RNG.randint(1,50);n=RNG.randint(1,50);congruences.append((RNG.randrange(m),m,RNG.randrange(n),n))
    put('两条同余方程的最小解','困难','smallestCongruenceSolution','a:int,m:int,b:int,n:int','long','求最小非负 x，满足 x mod m=a 且 x mod n=b，不保证 m、n 互质。无解返回 -1。',two_congruences,[((2,3,3,5),8),((0,2,1,2),-1)],congruences,'- `1 ≤ m,n ≤ 10^6`，`0 ≤ a < m`，`0 ≤ b < n`。','扩展欧几里得求广义中国剩余定理解，O(log max(m,n))。')
