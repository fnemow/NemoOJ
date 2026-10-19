"""Twenty additive string exercises: explicit ASCII rules, hand examples and edge cases."""
from collections import Counter
from functools import lru_cache
import random
from catalog_306_common import add, PROBLEMS


def common_prefix(words):
    if not words:return ''
    a,b=min(words),max(words);i=0
    while i<min(len(a),len(b)) and a[i]==b[i]:i+=1
    return a[:i]


def first_unique(s):
    counts=Counter(s)
    return next((i for i,c in enumerate(s) if counts[c]==1),-1)


def cleaned_palindrome(s):
    t=''.join(c.lower() for c in s if c.isalnum())
    return t==t[::-1]


def isomorphic(s,t):
    if len(s)!=len(t):return False
    forward,backward={},{}
    for a,b in zip(s,t):
        if (a in forward and forward[a]!=b) or (b in backward and backward[b]!=a):return False
        forward[a]=b;backward[b]=a
    return True


def word_pattern(pattern,text):
    words=text.split()
    if len(pattern)!=len(words):return False
    forward,backward={},{}
    for a,b in zip(pattern,words):
        if (a in forward and forward[a]!=b) or (b in backward and backward[b]!=a):return False
        forward[a]=b;backward[b]=a
    return True


def add_decimal(a,b):
    i,j=len(a)-1,len(b)-1;carry=0;out=[]
    while i>=0 or j>=0 or carry:
        carry+=(ord(a[i])-48 if i>=0 else 0)+(ord(b[j])-48 if j>=0 else 0)
        out.append(str(carry%10));carry//=10;i-=1;j-=1
    return ''.join(out[::-1]).lstrip('0') or '0'


def multiply_decimal(a,b):
    a=a.lstrip('0') or '0';b=b.lstrip('0') or '0';digits=[0]*(len(a)+len(b))
    for i in range(len(a)-1,-1,-1):
        for j in range(len(b)-1,-1,-1):digits[i+j+1]+=(ord(a[i])-48)*(ord(b[j])-48)
    for i in range(len(digits)-1,0,-1):digits[i-1]+=digits[i]//10;digits[i]%=10
    return ''.join(map(str,digits)).lstrip('0') or '0'


def compare_versions(a,b):
    left=a.split('.');right=b.split('.')
    for i in range(max(len(left),len(right))):
        x=(left[i] if i<len(left) else '0').lstrip('0') or '0'
        y=(right[i] if i<len(right) else '0').lstrip('0') or '0'
        if (len(x),x)!=(len(y),y):return 1 if (len(x),x)>(len(y),y) else -1
    return 0


def text_integer(s):
    i=0
    while i<len(s) and s[i]==' ':i+=1
    sign=1
    if i<len(s) and s[i] in '+-':sign=-1 if s[i]=='-' else 1;i+=1
    value=0;limit=2**31 if sign<0 else 2**31-1
    while i<len(s) and '0'<=s[i]<='9':
        value=value*10+ord(s[i])-48;i+=1
        if value>=limit:return sign*limit
    return sign*value


def longest_valid_parentheses(s):
    stack=[-1];best=0
    for i,c in enumerate(s):
        if c=='(':stack.append(i)
        else:
            stack.pop()
            if not stack:stack.append(i)
            else:best=max(best,i-stack[-1])
    return best


def parentheses_insertions(s):
    opens=missing=0
    for c in s:
        if c=='(':opens+=1
        elif opens:opens-=1
        else:missing+=1
    return opens+missing


def exact_distinct_substrings(s,k):
    def at_most(bound):
        if bound<0:return 0
        counts={};left=total=0
        for right,c in enumerate(s):
            counts[c]=counts.get(c,0)+1
            while len(counts)>bound:
                old=s[left];counts[old]-=1;left+=1
                if not counts[old]:del counts[old]
            total+=right-left+1
        return total
    return at_most(k)-at_most(k-1)


def suffix_states(s):
    nexts=[{}];length=[0];link=[-1];occurs=[0];last=0
    for c in s:
        cur=len(nexts);nexts.append({});length.append(length[last]+1);link.append(0);occurs.append(1)
        p=last
        while p>=0 and c not in nexts[p]:nexts[p][c]=cur;p=link[p]
        if p>=0:
            q=nexts[p][c]
            if length[p]+1==length[q]:link[cur]=q
            else:
                clone=len(nexts);nexts.append(nexts[q].copy());length.append(length[p]+1);link.append(link[q]);occurs.append(0)
                while p>=0 and nexts[p].get(c)==q:nexts[p][c]=clone;p=link[p]
                link[q]=link[cur]=clone
        last=cur
    return nexts,length,link,occurs


def repeated_substring_length(s):
    _,length,link,occurs=suffix_states(s)
    for i in sorted(range(1,len(length)),key=length.__getitem__,reverse=True):occurs[link[i]]+=occurs[i]
    return max((length[i] for i in range(1,len(length)) if occurs[i]>=2),default=0)


def distinct_substring_count(s):
    _,length,link,_=suffix_states(s)
    return sum(length[i]-length[link[i]] for i in range(1,len(length)))


def lexicographic_rotation(s):
    n=len(s)
    if not n:return ''
    t=s+s;i=0;j=1;k=0
    while i<n and j<n and k<n:
        if t[i+k]==t[j+k]:k+=1;continue
        if t[i+k]>t[j+k]:i=i+k+1;i+=i==j
        else:j=j+k+1;j+=i==j
        k=0
    start=min(i,j);return t[start:start+n]


def minimum_delete_cost(a,b):
    dp=[0]
    for c in b:dp.append(dp[-1]+ord(c))
    for x in a:
        previous=dp[0];dp[0]+=ord(x)
        for j,y in enumerate(b,1):
            old=dp[j];dp[j]=previous if x==y else min(old+ord(x),dp[j-1]+ord(y));previous=old
    return dp[-1]


def digit_decodings(s):
    if not s:return 0
    before=1;current=int(s[0]!='0')
    for i in range(1,len(s)):
        value=(current if s[i]!='0' else 0)+(before if 10<=int(s[i-1:i+1])<=26 else 0)
        before,current=current,value%1000000007
    return current


def restore_ipv4(s):
    answer=[]
    def visit(start,parts):
        if len(parts)==4:
            if start==len(s):answer.append('.'.join(parts))
            return
        remaining=4-len(parts)
        if not remaining<=len(s)-start<=remaining*3:return
        for end in range(start+1,min(start+3,len(s))+1):
            part=s[start:end]
            if len(part)>1 and part[0]=='0':break
            if int(part)<=255:visit(end,parts+[part])
    visit(0,[]);return sorted(answer)


def scrambled_equal(a,b):
    if len(a)!=len(b):return False
    @lru_cache(None)
    def match(x,y):
        if x==y:return True
        if Counter(x)!=Counter(y):return False
        return any((match(x[:k],y[:k]) and match(x[k:],y[k:])) or
                   (match(x[:k],y[-k:]) and match(x[k:],y[:-k])) for k in range(1,len(x)))
    return match(a,b)


def concatenated_word_positions(s,words):
    if not words:return []
    size=len(words[0]);total=size*len(words);need=Counter(words);answer=[]
    for offset in range(size):
        left=offset;used=0;counts=Counter()
        for right in range(offset,len(s)-size+1,size):
            word=s[right:right+size]
            if word not in need:counts.clear();used=0;left=right+size;continue
            counts[word]+=1;used+=1
            while counts[word]>need[word]:counts[s[left:left+size]]-=1;left+=size;used-=1
            if used==len(words):answer.append(left);counts[s[left:left+size]]-=1;left+=size;used-=1
    return sorted(answer)


def register():
    rng=random.Random(32620261011)
    def texts(alphabet='abc',n=18,maximum=10):
        return [('',),('a' if 'a' in alphabet else alphabet[0],)]+[(''.join(rng.choice(alphabet) for _ in range(rng.randrange(maximum+1))),) for _ in range(n)]
    pairs=[(s,t) for (s,),(t,) in zip(texts(),texts())]
    def put(title,diff,method,decl,ret,text,fn,examples,extras,bounds,idea,tags=()):
        add('string_extension',title,diff,['字符串',*tags],method,decl,ret,text,
            '- 所有字符均为 ASCII，字符串不含换行和回车。\n'+bounds,idea,fn,examples,extras)
    put('字符串数组的最长公共前缀','简单','longestArrayCommonPrefix','words:string[]','string','返回所有字符串共有的最长前缀。数组为空或没有公共前缀时返回空字符串，数组中的字符串也可以为空。',common_prefix,[((['flower','flow','flight'],),'fl'),((['dog','racecar','car'],),'')],
        [([],),([''],),(['alone'],),(['same','same'],),(['','abc'],)]+[([s for (s,) in texts(n=5)],) for _ in range(12)],'- `0 ≤ words.length ≤ 1000`，小写字母，总字符数≤100000。','比较字典序最小和最大的字符串，其共同前缀就是全体的共同前缀，O(总字符数)。',['数组'])
    put('首个不重复字符的下标','简单','firstUniqueCharacterIndex','s:string','int','返回 s 中只出现一次的第一个字符的下标（从 0 开始）；没有这样的字符时返回 -1。',first_unique,[(('leetcode',),0),(('aabb',),-1)],texts()+[('a'*10000+'z',)],'- 小写字母，`0 ≤ |s| ≤ 100000`。','先计数，再按原顺序寻找频数为 1 的字符，O(n)。',['哈希表'])
    put('忽略非字母数字的回文判定','简单','isAlphanumericPalindrome','s:string','boolean','只保留英文字母与数字，并将大写字母转为小写，判断所得字符串是否为回文。过滤后为空串也返回 true。',cleaned_palindrome,[(('A man, a plan, a canal: Panama',),True),(('race a car',),False)],texts('aAbB01 ,:!')+[('.,! ',),('0P',),('a'*10000,)],'- 可打印 ASCII 字符，`0 ≤ |s| ≤ 100000`。','左右双指针跳过非字母数字，忽略字母大小写，O(n)。',['双指针'])
    put('两个字符串的同构判定','简单','areStringsIsomorphic','s:string,t:string','boolean','能否把 s 中每一种字符一致替换成 t 中一种字符，从而得到 t？不同字符不能映射到同一字符，字符可映射到自己；长度不同返回 false。',isomorphic,[(('egg','add'),True),(('foo','bar'),False)],pairs+[('ab','aa'),('paper','title'),('a'*10000,'b'*10000)],'- 小写字母，`0 ≤ |s|,|t| ≤ 100000`。','维护两个方向的映射，避免多对一，O(n+m)。',['哈希表'])
    put('单词与字符的映射规律','简单','matchesWordPattern','pattern:string,text:string','boolean','将 text 按空格拆成非空单词，忽略首尾及重复空格。判断单词序列和 pattern 是否形成双射：相同字符对应相同单词，不同字符对应不同单词。',word_pattern,[(('abba','dog cat cat dog'),True),(('abba','dog cat cat fish'),False)],
        [('', ''),('','   '),('a','dog'),('ab','dog dog'),('aa',' dog  dog '),('ab','dog cat'),('aaa','cat cat cat'),('a','cat dog'),('abc','red green blue'),('abb','red green green'),('abc','red green red'),('ab','red')],'- pattern 仅小写字母；text 为小写字母与空格；各自长度≤10000。','分词后验证双向映射，O(总输入长度)。',['哈希表'])
    decimals=[(str(rng.randrange(10**12)),str(rng.randrange(10**12))) for _ in range(16)]
    db='- a、b 为非空十进制数字串，可含前导零；结果不得包含多余的前导零，零统一输出 `0`。'
    put('大整数十进制字符串相加','简单','addDecimalStrings','a:string,b:string','string','计算两个非负整数的和，返回规范十进制字符串。请按位处理，不依赖把整个输入转换为任意精度整数。',add_decimal,[(('999','1'),'1000'),(('000','0'),'0')],decimals+[('0','0'),('1','0'),('00012','00008'),('9'*1000,'1')],db+'\n- `1 ≤ |a|,|b| ≤ 10000`。','从低位到高位模拟加法和进位，O(n+m)。',['数学'])
    put('大整数十进制字符串相乘','中等','multiplyDecimalStrings','a:string,b:string','string','计算两个非负整数的乘积，返回规范十进制字符串。请用按位乘法实现，不依赖任意精度整数库。',multiply_decimal,[(('123','456'),'56088'),(('000','123'),'0')],decimals+[('0','0'),('1','0'),('00012','00008'),('9'*200,'9'*200)],db+'\n- `1 ≤ |a|,|b| ≤ 200`。','把每一对数字的乘积累加到结果位，统一进位，O(nm)。',['数学'])
    versions=[('.'.join(str(rng.randrange(100)) for _ in range(rng.randrange(1,5))),'.'.join(str(rng.randrange(100)) for _ in range(rng.randrange(1,5)))) for _ in range(16)]
    put('版本号的数值比较','中等','compareVersionNumbers','a:string,b:string','int','版本号由用点分隔的非空十进制数字段组成。逐段按数值比较，忽略段内前导零；缺失的末尾段视为 0。a 小于、等于、大于 b 时分别返回 -1、0、1。',compare_versions,[(('1.01','1.001'),0),(('1.0','1.0.1'),-1)],versions+[('0','0'),('01.0','1'),('9'*100,'1'+'0'*99)],'- 每段仅数字且长度≤100；整个非空版本号长度≤1000。','去前导零后按长度和字典序比较，避免整数溢出，O(n+m)。',['双指针'])
    put('文本转换为32位有符号整数','中等','parseSignedInteger','s:string','int','先跳过开头的半角空格，读取一个可选的 + 或 -，再连续读取数字直到遇到其他字符。没有读到数字时返回 0。超出32位有符号整数范围时截断到 -2147483648 或 2147483647；数字后的内容忽略。',text_integer,[(('   -42abc',),-42),(('91283472332',),2147483647)],
        [('',),(' ',),('+',),('-',),('0',),('1',),('-2147483648',),('2147483647',),('-2147483649',),('2147483648',),('words 42',),('+-12',),('  +00012 rest',),('  -00000x',),('9'*1000,)],'- 可打印 ASCII 字符，`0 ≤ |s| ≤ 10000`。','按状态解析空格、符号和数字，累加前检查边界，O(n)。')
    bracket=texts('()',maximum=24)
    put('最长连续合法括号子串','困难','longestValidParenthesesLength','s:string','int','返回 s 中最长连续合法括号子串的长度。合法括号必须逐一匹配，空串答案为 0；要求连续，不能跳过中间字符。',longest_valid_parentheses,[((')()())',),4),(('(()',),2)],bracket+[('('*1000+')'*1000,)],'- 仅包含 ( 和 )，`0 ≤ |s| ≤ 100000`。','栈保存未匹配下标和区间分界，O(n)。',['栈','动态规划'])
    put('括号串补齐的最少插入次数','中等','minimumParenthesesInsertions','s:string','int','可以在任意位置插入 ( 或 )，但不能删除或重排原字符。返回使整个字符串成为合法括号串所需的最少插入次数。',parentheses_insertions,[(('())',),1),(('))((',),4)],bracket+[('(',),(')',),('('*1000,)],'- 仅包含 ( 和 )，`0 ≤ |s| ≤ 100000`。','统计当前未匹配左括号和必须补充的左括号，O(n)。',['栈'])
    put('恰好K种字符的子串数量','中等','countExactlyKDistinctSubstrings','s:string,k:int','long','统计包含恰好 k 种不同字符的非空连续子串数量。不同起止位置分别计数；k 为 0 时答案为 0。答案可能超过32位整数。',exact_distinct_substrings,[(('pqpqs',2),7),(('a',1),1)],
        [(s,rng.randrange(5)) for (s,) in texts()]+[('',0),('a',0),('a',2),('a'*10000,1)],'- 小写字母，`0 ≤ |s| ≤ 100000`，`0 ≤ k ≤ 26`。','至多 k 种的子串数量减去至多 k-1 种，双滑动窗口 O(n)。',['滑动窗口','哈希表'])
    put('最长重复子串的长度','困难','longestRepeatedSubstringLength','s:string','int','返回 s 中出现至少两次的最长连续子串长度。两次出现允许重叠，没有重复非空子串时返回 0。',repeated_substring_length,[(('banana',),3),(('aaaa',),3)],texts()+[('abc'*3000,)],'- 小写字母，`0 ≤ |s| ≤ 100000`。','后缀自动机统计各状态的结束位置出现次数，出现至少两次的状态中取最长长度。采用长度排序传播时 O(n log n)，可计数排序优化到 O(n)。')
    put('所有不同连续子串的数量','困难','countDistinctSubstrings','s:string','long','统计 s 中内容不同的非空连续子串数量。相同内容只计一次，无论出现多少次；空串答案为 0。答案可能超过32位整数。',distinct_substring_count,[(('ababa',),9),(('aaa',),3)],texts()+[('abc'*3000,),('a'*10000,)],'- 小写字母，`0 ≤ |s| ≤ 100000`。','后缀自动机每个状态贡献 maxLen[state]-maxLen[link[state]]，总和即答案，O(n)。')
    put('字典序最小的循环移位字符串','中等','minimumLexicographicRotation','s:string','string','从 s[k:]+s[:k]（0≤k<|s|）的全部左循环移位中返回字典序最小的字符串。多个位置相同也只返回字符串；空输入返回空字符串。',lexicographic_rotation,[(('baca',),'abac'),(('aaaa',),'aaaa')],texts()+[('b'*9999+'a',)],'- 小写字母，`0 ≤ |s| ≤ 100000`。','Booth 双候选比较算法跳过一定较差的起点，O(n)。',['双指针'])
    put('两字符串相等的最小字符删除代价','中等','minimumAsciiDeletionCost','a:string,b:string','int','可分别从 a、b 删除任意字符而不改变剩余顺序，使剩余字符串相同。删除一个字符的代价为该字符的 ASCII 编码值，返回总删除代价的最小值。',minimum_delete_cost,[(('sea','eat'),231),(('delete','leet'),403)],pairs+[('a',''),('','b'),('a'*500,'b'*500)],'- 小写字母，`0 ≤ |a|,|b| ≤ 500`。','双串前缀 DP，匹配时保留，不匹配时择优删除一侧；O(nm) 时间、O(m) 空间。',['动态规划'])
    digits=[(''.join(rng.choice('0123456789') for _ in range(rng.randrange(15))),) for _ in range(16)]
    put('数字字符串的字母解码方案数','中等','countDigitDecodings','s:string','int','把 1..26 分别映射为 A..Z，统计将 s 完整拆成合法编码的方案数，对 1000000007 取模。编码不能以 0 开头，单独的 0 不能解码；空输入定义为 0 种。',digit_decodings,[(('226',),3),(('06',),0)],digits+[('',),('0',),('1',),('10',),('100',),('11106',),('1'*1000,)],'- 只包含十进制数字，`0 ≤ |s| ≤ 1000`。','当前位置由合法单字符和合法双字符转移，O(n) 时间、O(1) 额外空间。',['动态规划'])
    put('恢复全部合法IPv4地址','中等','restoreIPv4Addresses','s:string','string[]','在数字串中插入三个点形成四段 IPv4 地址。每段必须在 0..255，除单独的 0 外不允许前导零，必须使用全部字符。返回去重后按字符串字典序升序排列的全部地址，无法形成时返回空数组；ACM 输出地址以一个空格连接。',restore_ipv4,[ (('25525511135',),['255.255.11.135','255.255.111.35']),(('0000',),['0.0.0.0'])],
        [('',),('0',),('1',),('1111',),('010010',),('101023',),('999999999999',),('255255255255',),('256256256256',),('123',),('1234567890123',)]+digits,'- 只包含十进制数字，`0 ≤ |s| ≤ 20`。','回溯枚举每段的 1..3 个字符，剪枝剩余位数与前导零，搜索分支至多 3⁴。',['回溯'])
    short=texts(n=18,maximum=7)
    scramble=[(s,''.join(rng.sample(s,len(s)))) for (s,) in short]+pairs[:8]
    put('递归交换形成的扰乱字符串','困难','areScrambledStrings','a:string,b:string','boolean','允许把非空字符串在任意内部位置分成两段，并任选是否交换两段，然后递归处理每段。判断 a 能否经过这些操作得到 b；两个空串返回 true，长度不同返回 false。',scrambled_equal,[(('great','rgeat'),True),(('abcde','caebd'),False)],scramble+[('a','a'),('a','b'),('a'*25,'a'*25)],'- 小写字母，`0 ≤ |a|,|b| ≤ 25`。','区间状态 (a起点,b起点,长度) 记忆化，枚举分割与交换，字符频次不符提前剪枝，最坏 O(n⁴)。',['动态规划','递归'])
    concat=[(s, ['a','b']) for (s,) in texts(n=16,maximum=18)]
    put('拼接整个词组的全部起始位置','困难','findConcatenatedWordPositions','s:string,words:string[]','int[]','words 中所有单词非空且长度相同，可有重复。在 s 中找出所有起始下标，使从该位置开始的连续片段恰好由 words 中每个单词各使用一次、以任意顺序拼接而成。下标从 0 开始，结果升序；words 为空时返回空数组。',concatenated_word_positions,
        [(('barfoothefoobarman',['foo','bar']),[0,9]),(('wordgoodgoodgoodbestword',['word','good','best','good']),[8])],
        concat+[('',[]),('a',[]),('a',['a']),('aaaaaa',['aa','aa']),('abab',['ab','ba']),('a'*10000,['aa']*10)],
        '- 小写字母，`0 ≤ |s| ≤ 10000`，`0 ≤ words.length ≤ 100`；单词长度为 1..20 且全部相同。','按单词长度分组滑动窗口，维护重复词频，O(n×单词长度)（含子串构造），额外空间 O(词数)。',['滑动窗口','哈希表'])
    assert len(PROBLEMS)==20


def load():
    if not PROBLEMS:register()
    return PROBLEMS
