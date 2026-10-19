"""Twenty string algorithms with explicit tie-breaking and exact comparisons."""
from collections import Counter
from catalog_306_common import add, RNG, strings, arrays


def prefix_function(s):
    pi=[0]*len(s)
    for i in range(1,len(s)):
        j=pi[i-1]
        while j and s[i]!=s[j]:j=pi[j-1]
        if s[i]==s[j]:j+=1
        pi[i]=j
    return pi


def all_matches(s, pattern):
    if not pattern:return list(range(len(s)+1))
    pi=prefix_function(pattern);j=0;answer=[]
    for i,ch in enumerate(s):
        while j and ch!=pattern[j]:j=pi[j-1]
        if ch==pattern[j]:j+=1
        if j==len(pattern):answer.append(i-j+1);j=pi[j-1]
    return answer


def longest_border(s):return prefix_function(s)[-1] if s else 0


def minimum_period(s):
    if not s:return 0
    p=len(s)-prefix_function(s)[-1]
    return p if len(s)%p==0 else len(s)


def rotation_offset(s, t):
    if len(s)!=len(t):return -1
    if not s:return 0
    choices=all_matches(s+s[:-1],t)
    return choices[0] if choices else -1


def shortest_prefix_palindrome(s):
    combined=s+'#'+s[::-1];length=prefix_function(combined)[-1]
    return s[length:][::-1]+s


def longest_palindrome(s):
    best='';start=0
    for center in range(len(s)):
        for left,right in ((center,center),(center,center+1)):
            while left>=0 and right<len(s) and s[left]==s[right]:left-=1;right+=1
            if right-left-1>len(best):best=s[left+1:right];start=left+1
            elif right-left-1==len(best) and left+1<start:best=s[left+1:right];start=left+1
    return best


def palindrome_count(s):
    answer=0
    for center in range(len(s)):
        for left,right in ((center,center),(center,center+1)):
            while left>=0 and right<len(s) and s[left]==s[right]:answer+=1;left-=1;right+=1
    return answer


def palindrome_subsequence(s):
    n=len(s)
    if not n:return 0
    dp=[[0]*n for _ in range(n)]
    for i in range(n-1,-1,-1):
        dp[i][i]=1
        for j in range(i+1,n):dp[i][j]=2+(dp[i+1][j-1] if j>i+1 else 0) if s[i]==s[j] else max(dp[i+1][j],dp[i][j-1])
    return dp[0][-1]


def palindrome_insertions(s):return len(s)-palindrome_subsequence(s)


def common_substring(s, t):
    dp=[0]*(len(t)+1);answer=0
    for a in s:
        for j in range(len(t),0,-1):dp[j]=dp[j-1]+1 if a==t[j-1] else 0;answer=max(answer,dp[j])
    return answer


def supersequence_length(s, t):
    dp=list(range(len(t)+1))
    for i,a in enumerate(s,1):
        nxt=[i]+[0]*len(t)
        for j,b in enumerate(t,1):nxt[j]=dp[j-1]+1 if a==b else min(dp[j],nxt[j-1])+1
        dp=nxt
    return dp[-1]


def run_compress(s):
    result=[];i=0
    while i<len(s):
        j=i+1
        while j<len(s) and s[j]==s[i]:j+=1
        result.append(s[i]+str(j-i));i=j
    return ''.join(result)


def bracket_decode(s):
    stack=[];text='';number=0
    for ch in s:
        if ch.isdigit():number=number*10+int(ch)
        elif ch=='[':stack.append((text,number));text='';number=0
        elif ch==']':prefix,count=stack.pop();text=prefix+text*count
        else:text+=ch
    return text


def remove_k_adjacent(s, k):
    stack=[]
    for ch in s:
        if stack and stack[-1][0]==ch:stack[-1][1]+=1
        else:stack.append([ch,1])
        if stack[-1][1]==k:stack.pop()
    return ''.join(ch*n for ch,n in stack)


def backspace_equal(s, t):
    def process(text):
        stack=[]
        for ch in text:
            if ch=='#':
                if stack:stack.pop()
            else:stack.append(ch)
        return stack
    return process(s)==process(t)


def reverse_words(s):return ' '.join(s.split()[::-1])


def largest_concatenation(nums):
    from functools import cmp_to_key
    a=sorted(map(str,nums),key=cmp_to_key(lambda x,y:(y+x>x+y)-(y+x<x+y)))
    result=''.join(a)
    return '0' if result and result[0]=='0' else result


def anagram_groups(words):return len({''.join(sorted(word)) for word in words})


def word_break(s, words):
    dictionary=set(words);dp=[False]*(len(s)+1);dp[0]=True
    for i in range(1,len(s)+1):dp[i]=any(dp[j] and s[j:i] in dictionary for j in range(i))
    return dp[-1]


def word_break_ways(s, words):
    dictionary=set(words);dp=[0]*(len(s)+1);dp[0]=1
    for i in range(1,len(s)+1):dp[i]=sum(dp[j] for j in range(i) if s[j:i] in dictionary)%1000000007
    return dp[-1]


def register():
    def put(title,diff,method,decl,ret,text,fn,examples,extras,bounds,idea,tags=()):
        add('strings',title,diff,['字符串',*tags],method,decl,ret,text,bounds,idea,fn,examples,extras)
    texts=strings(16,'abc',0,18)
    pairs=[(s,t) for (s,),(t,) in zip(texts,strings(16,'ab',0,6))]
    sb='- 字符仅为小写英文字母，`0 ≤ |s| ≤ 10000`。'
    put('查找全部模式串位置','中等','allPatternPositions','s:string,pattern:string','int[]','按升序返回 pattern 在 s 中所有出现位置的起始下标，允许重叠。下标从 0 开始；空模式在 0..|s| 所有位置匹配。',all_matches,[(('aaaa','aa'),[0,1,2]),(('ab',''),[0,1,2])],pairs+[('a'*10000,'aaa')],sb+'\n- `0 ≤ |pattern| ≤ 10000`，小写字母。','KMP 前缀函数避免回退文本指针，O(n+m)。')
    put('最长相同真前后缀','中等','longestProperBorder','s:string','int','返回 s 的最长相同真前缀与后缀长度；真前缀不允许等于整个字符串，允许前后缀重叠。空串返回 0。',longest_border,[(('ababab',),4),(('abc',),0)],texts+[('a'*10000,)],sb,'KMP 前缀函数最后一项，O(n)。')
    put('字符串的最短循环节','中等','shortestRepeatUnit','s:string','int','寻找最短非空字符串，使重复该字符串若干次恰好得到 s，返回它的长度；空串返回 0。',minimum_period,[(('ababab',),2),(('ababa',),5)],texts+[('ab'*5000,)],sb,'根据最长真前后缀求候选周期并检验整除，O(n)。')
    put('字符串左旋匹配偏移','中等','minimumRotationOffset','s:string,t:string','int','将 s 左旋 k 个字符，即 s[k:]+s[:k]。返回得到 t 的最小 k（0≤k<|s|）；不能得到则返回 -1，两个空串返回 0。',rotation_offset,[(('abc','bca'),1),(('aa','aa'),0)],pairs+[('ab'*100,'ba'*100)],sb+'\n- t 同样为小写字母，长度不超过 10000。','在 s+s 去除最后一个字符的文本上找 t 的最早位置，O(n+m)。')
    put('前补字符形成最短回文','困难','shortestFrontPalindrome','s:string','string','仅允许在 s 前面添加字符，返回使整体成为回文的最短结果。',shortest_prefix_palindrome,[(('abcd',),'dcbabcd'),(('aacecaaa',),'aaacecaaa')],texts+[('ab'*5000,)],sb,'对 s+分隔符+逆串求前缀函数，获得最长回文前缀，O(n)。')
    put('最长回文子串','中等','longestPalindromicSubstring','s:string','string','返回最长连续回文子串；同长度选原串起始下标最小的，空串返回空串。',longest_palindrome,[(('babad',),'bab'),(('cbbd',),'bb')],texts+[('a'*500,)],'- 小写字母，`0 ≤ |s| ≤ 1000`。','中心扩展，最长相同时保留最左答案，O(n²)。',['双指针'])
    put('回文子串总数','中等','countPalindromicSubstrings','s:string','int','统计非空连续回文子串数量；不同下标区间分别计数。',palindrome_count,[(('aaa',),6),(('abc',),3)],texts+[('a'*1000,)],'- 小写字母，`0 ≤ |s| ≤ 2000`。','对奇数与偶数中心扩展，O(n²)。',['双指针'])
    put('最长回文子序列长度','中等','longestPalindromeSubsequence','s:string','int','可删除任意字符但不能改变顺序，返回最长回文子序列长度。',palindrome_subsequence,[(('bbbab',),4),(('cbbd',),2)],texts+[('a'*300,)],'- 小写字母，`0 ≤ |s| ≤ 500`。','区间 DP，根据端点字符是否相等转移，O(n²)。',['动态规划'])
    put('插入字符形成回文的最少次数','困难','minimumPalindromeInsertions','s:string','int','可以在任意位置插入一个字符，返回使 s 成为回文串所需的最少插入次数。',palindrome_insertions,[(('mbadm',),2),(('zzazz',),0)],texts+[('a'*300,)],'- 小写字母，`0 ≤ |s| ≤ 500`。','原长减去最长回文子序列长度，或直接区间 DP，O(n²)。',['动态规划'])
    put('最长公共连续子串长度','中等','longestCommonSubstring','s:string,t:string','int','返回两个字符串公共连续子串的最大长度，注意必须连续。',common_substring,[(('abcd','xbcy'),2),(('abc','def'),0)],pairs+[('a'*300,'a'*200)],'- 小写字母，`0 ≤ |s|,|t| ≤ 500`。','匹配则沿左上延伸，不匹配则清零；O(nm)。',['动态规划'])
    put('最短公共超序列长度','困难','shortestSupersequenceLength','s:string,t:string','int','寻找一个字符串，使 s 与 t 都是其子序列，返回该字符串的最短长度。',supersequence_length,[(('abac','cab'),5),(('','abc'),3)],pairs+[('a'*300,'b'*300)],'- 小写字母，`0 ≤ |s|,|t| ≤ 500`。','前缀 DP：相等时共享一个字符，否则选择两种追加方式中较短的；O(nm)。',['动态规划'])
    put('游程编码字符串','简单','runLengthEncode','s:string','string','将连续相同字符压缩为字符加十进制次数，即使次数为 1 也输出。例如 aaabbc→a3b2c1。',run_compress,[(('aaabbc',),'a3b2c1'),(('',),'')],texts+[('a'*10000,)],sb,'双指针统计每段长度，O(n)。',['双指针'])
    encoded=[('a',),('2[a]',),('3[ab]',),('2[a2[b]]',),('2[ab]c3[d]',),('',),('10[z]',),('1[a]',),('2[3[a]b]',),('abc',),('2[a]2[b]',),('5[xy]',)]
    put('嵌套重复编码解码','中等','decodeNestedRepeats','s:string','string','合法编码 k[内容] 表示将内容重复 k 次，可嵌套；字母直接保留。返回解码后的字符串。数字只作为括号前重复次数。',bracket_decode,[(('3[a2[c]]',),'accaccacc'),(('2[abc]3[cd]ef',),'abcabccdcdcdef')],encoded,'- 字符仅含小写字母、数字和方括号，编码合法。\n- `0 ≤ |s| ≤ 500`，`1 ≤ k ≤ 100`，嵌套深度≤20，解码长度≤10000。','用栈保存进入括号前的文本和重复次数，O(解码长度×嵌套深度)。',['栈'])
    put('删除连续 K 个相同字符','中等','removeAdjacentGroups','s:string,k:int','string','重复删除任意连续 k 个相同字符，直到不能删除。返回最终字符串；删除后新相邻字符继续合并。',remove_k_adjacent,[(('deeedbbcccbdaa',3),'aa'),(('abcd',2),'abcd')],[(s,RNG.randint(1,5)) for (s,) in texts],sb+'\n- `1 ≤ k ≤ 10000`。','栈存储字符和连续次数，达到 k 即出栈，O(n)。',['栈'])
    put('带退格字符串比较','简单','backspaceStringsEqual','s:string,t:string','boolean','# 表示删除此前尚未删除的最后一个字符；没有字符时忽略。判断两个字符串处理后是否相等。',backspace_equal,[(('ab#c','ad#c'),True),(('a#c','b'),False)],[(s,t) for (s,),(t,) in zip(strings(14,'ab#',0,18),strings(14,'ab#',0,18))],'- 仅小写字母及 #，`0 ≤ |s|,|t| ≤ 10000`。','栈模拟退格，或逆向双指针跳过删除字符，O(n+m)。',['栈','双指针'])
    put('反转句子中的单词顺序','简单','reverseSentenceWords','s:string','string','s 包含英文字母和空格。反转单词顺序，单词内部字符顺序不变；结果用一个空格连接，去除首尾及多余空格。',reverse_words,[(('  the sky  is blue ',),'blue is sky the'),(('   ',),'')],strings(15,'abc   ',0,30),'- `0 ≤ |s| ≤ 10000`。','分词后逆序连接，O(n)。')
    put('拼接整数形成最大数','中等','largestConcatenatedNumber','nums:int[]','string','将全部非负整数按某个顺序拼接，返回数值最大的十进制字符串。全为 0 时仅返回 0，空数组返回空字符串。',largest_concatenation,[(([3,30,34,5,9],),'9534330'),(([0,0],),'0')],arrays(15,0,15,0,999),'- `0 ≤ n ≤ 1000`，`0 ≤ nums[i] ≤ 10^9`。','按 a+b 与 b+a 的字典序比较进行排序，O(n log n × 位数)。',['排序','贪心'])
    word_sets=[([s for (s,) in strings(10,'abc',0,8)],) for _ in range(14)]
    put('字母异位词分组数量','简单','anagramGroupCount','words:string[]','int','字母计数完全相同的词属于一组，返回分组数量。相同单词可重复出现，空字符串之间属于同组。',anagram_groups,[((['eat','tea','tan','ate','nat','bat'],),3),(([],),0)],word_sets,'- `0 ≤ n ≤ 1000`，单词仅含小写字母且长度≤100。','字符频率或排序后的字符串作为哈希键，O(总字符数 log 单词长)。',['哈希表'])
    dictionaries=[(s,sorted(set(w for (w,) in strings(10,'ab',1,4)))) for (s,) in strings(15,'ab',0,14)]
    wb='- 小写字母，`0 ≤ |s| ≤ 200`，`0 ≤ |words| ≤ 50`；词非空且长度≤20，可有重复，重复词只计一次。'
    put('词典拆分可行性','中等','canSegmentWords','s:string,words:string[]','boolean','能否将 s 完整拆成词典中的词？每个词可重复使用，空 s 可由空序列表示。',word_break,[(('leetcode',['leet','code']),True),(('catsandog',['cats','dog','sand','and','cat']),False)],dictionaries,wb,'前缀可行性 DP，O(n² × 子串构造成本)。',['动态规划','哈希表'])
    put('词典拆分方案数','困难','wordSegmentationWays','s:string,words:string[]','int','将 s 完整拆成词典词，统计不同切分位置方案数，对 1000000007 取模。重复词不重复计数，空 s 有一个空方案。',word_break_ways,[(('aaaa',['a','aa']),5),(('',[]),1)],dictionaries+[('a'*200,['a','aa','aaa'])],wb,'前缀计数 DP，O(n² × 子串构造成本)。',['动态规划','哈希表'])
