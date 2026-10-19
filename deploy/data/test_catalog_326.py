"""Independent small-input oracles for all twenty newly authored string problems."""
import itertools
import json
import re
from collections import Counter
from functools import lru_cache
from pathlib import Path
from catalog_326_strings import load


def prefix(words):
    if not words:return ''
    return max((words[0][:k] for k in range(len(words[0])+1) if all(w.startswith(words[0][:k]) for w in words)),key=len)


def shape(sequence):return [sequence.index(x) for x in sequence]


def integer(s):
    match=re.match(r' *([+-]?[0-9]+)',s)
    return max(-2**31,min(2**31-1,int(match.group(1)))) if match else 0


def valid(s):
    balance=0
    for c in s:
        balance+=1 if c=='(' else -1
        if balance<0:return False
    return balance==0


def substrings(s):return [s[i:j] for i in range(len(s)) for j in range(i+1,len(s)+1)]


def insertions(s):
    prefixes=[sum(1 if c=='(' else -1 for c in s[:i]) for i in range(len(s)+1)]
    left=-min(prefixes);right=prefixes[-1]+left
    return left+right


def versions(a,b):
    x=list(map(int,a.split('.')));y=list(map(int,b.split('.')));n=max(len(x),len(y));x += [0]*(n-len(x));y += [0]*(n-len(y))
    return (x>y)-(x<y)


def deletion(a,b):
    def subsequences(s):return {''.join(s[i] for i in range(len(s)) if mask>>i&1) for mask in range(1<<len(s))}
    common=subsequences(a)&subsequences(b)
    keep=max(sum(map(ord,t)) for t in common)
    return sum(map(ord,a+b))-2*keep


def decode(s):
    def visit(t):
        if not t:return 1
        if t[0]=='0':return 0
        return visit(t[1:])+(visit(t[2:]) if len(t)>1 and int(t[:2])<=26 else 0)
    return visit(s)%1000000007 if s else 0


def ipv4(s):
    answer=[]
    for i,j,k in itertools.combinations(range(1,len(s)),3):
        pieces=[s[:i],s[i:j],s[j:k],s[k:]]
        if all(len(p)<=3 and int(p)<=255 and (len(p)==1 or p[0]!='0') for p in pieces):answer.append('.'.join(pieces))
    return sorted(answer)


@lru_cache(None)
def all_scrambles(s):
    if len(s)<=1:return {s}
    answer=set()
    for i in range(1,len(s)):
        for x in all_scrambles(s[:i]):
            for y in all_scrambles(s[i:]):answer.update((x+y,y+x))
    return answer


def concatenations(s,words):
    if not words:return []
    size=len(words[0]);width=size*len(words)
    return [i for i in range(len(s)-width+1) if Counter(s[j:j+size] for j in range(i,i+width,size))==Counter(words)]


ORACLES={
 'common_prefix':prefix,
 'first_unique':lambda s:next((i for i,c in enumerate(s) if s.count(c)==1),-1),
 'cleaned_palindrome':lambda s:(lambda t:t==t[::-1])(re.sub('[^A-Za-z0-9]','',s).lower()),
 'isomorphic':lambda s,t:len(s)==len(t) and shape(s)==shape(t),
 'word_pattern':lambda p,s:len(p)==len(s.split()) and shape(p)==shape(s.split()),
 'add_decimal':lambda a,b:str(int(a)+int(b)),
 'multiply_decimal':lambda a,b:str(int(a)*int(b)),
 'compare_versions':versions,'text_integer':integer,
 'longest_valid_parentheses':lambda s:max((len(t) for t in substrings(s) if valid(t)),default=0),
 'parentheses_insertions':insertions,
 'exact_distinct_substrings':lambda s,k:sum(len(set(t))==k for t in substrings(s)),
 'repeated_substring_length':lambda s:max((len(t) for t,n in Counter(substrings(s)).items() if n>=2),default=0),
 'distinct_substring_count':lambda s:len(set(substrings(s))),
 'lexicographic_rotation':lambda s:min((s[i:]+s[:i] for i in range(len(s))),default=''),
 'minimum_delete_cost':deletion,'digit_decodings':decode,'restore_ipv4':ipv4,
 'scrambled_equal':lambda a,b:len(a)==len(b) and b in all_scrambles(a),
 'concatenated_word_positions':concatenations,
}


def main():
    coverage={};checks=0
    for p in load():
        name=p['solver'].__name__;oracle=ORACLES[name]
        for case in p['spec']['judgeCases']:
            args=list(json.loads(case['input']).values())
            sizes=[len(x) for x in args if isinstance(x,(str,list))]
            limit=7 if name=='scrambled_equal' else 10 if name=='minimum_delete_cost' else 30
            if name not in ('add_decimal','multiply_decimal','text_integer','compare_versions') and any(n>limit for n in sizes):continue
            assert oracle(*args)==json.loads(case['output']),(p['title'],args)
            checks+=1;coverage[name]=coverage.get(name,0)+1
        assert coverage.get(name,0)>=8,(name,coverage.get(name))
    result={'passed':True,'independentOracleProblems':len(coverage),'independentOracleChecks':checks,'coverage':coverage}
    target=Path(__file__).parents[2]/'build/catalog-326/local-oracle-report.json';target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(json.dumps(result,indent=2),encoding='utf-8');print(json.dumps({k:v for k,v in result.items() if k!='coverage'}))


if __name__=='__main__':main()
