"""Independent small-input oracles, in addition to the 280 hand examples."""
import copy
import itertools
import json
import math
from collections import Counter
from pathlib import Path
from generate_catalog_306 import load


def subarrays(a):return [a[i:j] for i in range(len(a)) for j in range(i+1,len(a)+1)]


def subsets(a):return [[a[i] for i in range(len(a)) if mask>>i&1] for mask in range(1<<len(a))]


def floyd(n,edges,directed=False):
    distance=[[0 if u==v else 10**12 for v in range(n)] for u in range(n)]
    for row in edges:
        u,v=row[:2];w=row[2] if len(row)>2 else 1;distance[u][v]=min(distance[u][v],w)
        if not directed:distance[v][u]=min(distance[v][u],w)
    for k in range(n):
        for i in range(n):
            for j in range(n):distance[i][j]=min(distance[i][j],distance[i][k]+distance[k][j])
    return distance


def brute_components(n,e):
    d=floyd(n,e);return len({tuple(v for v in range(n) if d[u][v]<10**12) for u in range(n)})


def brute_scc(n,e):
    d=floyd(n,e,True);return len({tuple(v for v in range(n) if d[u][v]<10**12 and d[v][u]<10**12) for u in range(n)})


def brute_bipartite(n,e):return any(all(((mask>>u)^(mask>>v))&1 for u,v in e) for mask in range(1<<n))


def brute_topo(n,e):
    for order in itertools.permutations(range(n)):
        positions={x:i for i,x in enumerate(order)}
        if all(positions[u]<positions[v] for u,v in e):return list(order)
    return []


def brute_pack(w,v,c):
    return max([sum(v[i] for i in range(len(w)) if mask>>i&1) for mask in range(1<<len(w))
                if sum(w[i] for i in range(len(w)) if mask>>i&1)<=c],default=0)


def brute_rob(a,circular=False):
    choices=[]
    for mask in range(1<<len(a)):
        if mask&(mask<<1):continue
        if circular and len(a)>1 and mask&1 and mask>>(len(a)-1)&1:continue
        choices.append(sum(a[i] for i in range(len(a)) if mask>>i&1))
    return max(choices,default=0)


def brute_stock(prices, transactions=None, cooldown=False, fee=0):
    from functools import lru_cache
    limit=len(prices) if transactions is None else transactions
    @lru_cache(None)
    def walk(day,holding,sold,blocked):
        if day==len(prices):return -10**12 if holding else 0
        best=walk(day+1,holding,sold,False)
        if holding and sold<limit:best=max(best,prices[day]-fee+walk(day+1,False,sold+1,cooldown))
        if not holding and not blocked and sold<limit:best=max(best,-prices[day]+walk(day+1,True,sold,False))
        return best
    return walk(0,False,0,False)


def brute_word_ways(s,words):
    if not s:return 1
    return sum(brute_word_ways(s[len(w):],words) for w in set(words) if s.startswith(w))%1000000007


def brute_pivots(a):return [i for i in range(len(a)) if sum(a[:i])==sum(a[i+1:])]


def brute_rectangles(matrix):
    n=len(matrix);m=len(matrix[0]);answer=[]
    for a in range(n):
        for b in range(m):
            for c in range(a,n):
                for d in range(b,m):answer.append(sum(matrix[i][j] for i in range(a,c+1) for j in range(b,d+1)))
    return answer


def brute_operations(a,operations,interval=False):
    a=a[:];answer=[]
    for row in operations:
        if row[0]==1:answer.append(sum(a[row[1]:row[2]+1]))
        elif interval:
            for i in range(row[1],row[2]+1):a[i]+=row[3]
        else:a[row[1]]=row[2]
    return answer


def brute_ship(a,days):
    for capacity in range(max(a),sum(a)+1):
        count=1;load=0
        for x in a:
            if load+x>capacity:count+=1;load=0
            load+=x
        if count<=days:return capacity


def brute_split(a,k):
    answer=sum(a)
    for cuts in itertools.combinations(range(1,len(a)),k-1):
        borders=(0,)+cuts+(len(a),);answer=min(answer,max(sum(a[borders[i]:borders[i+1]]) for i in range(k)))
    return answer


def brute_balloons(a):
    from functools import lru_cache
    @lru_cache(None)
    def solve(values):
        return max((values[i]*(values[i-1] if i else 1)*(values[i+1] if i+1<len(values) else 1)+solve(values[:i]+values[i+1:]) for i in range(len(values))),default=0)
    return solve(tuple(a))


ORACLES={
 'components':brute_components,
 'reachable':lambda n,e,s,t:floyd(n,e,True)[s][t]<10**12,
 'hop_distance':lambda n,e,s,t:(lambda x:-1 if x>=10**12 else x)(floyd(n,e)[s][t]),
 'bipartite':brute_bipartite,
 'topological_order':brute_topo,
 'dijkstra':lambda n,e,s:[-1 if x>=10**12 else x for x in floyd(n,e,True)[s]],
 'network_delay':lambda n,e,s:(lambda a:-1 if max(a)>=10**12 else max(a))(floyd(n,e,True)[s]),
 'tree_diameter':lambda n,e:max(map(max,floyd(n,e))),
 'tree_centers':lambda n,e:(lambda eccentric:[i for i,v in enumerate(eccentric) if v==min(eccentric)])(list(map(max,floyd(n,e)))),
 'bridges':lambda n,e:sum(brute_components(n,e[:i]+e[i+1:])>brute_components(n,e) for i in range(len(e))),
 'scc_count':brute_scc,
 'knapsack':brute_pack,
 'equal_partition':lambda a:any(sum(x)*2==sum(a) for x in subsets(a)),
 'stone_difference':lambda a:min(abs(sum(a)-2*sum(x)) for x in subsets(a)),
 'target_signs':lambda a,t:sum(sum(v*s for v,s in zip(a,signs))==t for signs in itertools.product((-1,1),repeat=len(a)))%1000000007,
 'robber':brute_rob,
 'circular_robber':lambda a:brute_rob(a,True),
 'stock_one':lambda a:brute_stock(a,1),
 'stock_unlimited':brute_stock,
 'stock_two':lambda a:brute_stock(a,2),
 'stock_cooldown':lambda a:brute_stock(a,cooldown=True),
 'stock_fee':lambda a,f:brute_stock(a,fee=f),
 'distinct_subsequences':lambda s,t:sum(''.join(x)==t for x in subsets(s))%1000000007,
 'burst_balloons':brute_balloons,
 'all_matches':lambda s,p:[i for i in range(len(s)+1) if s.startswith(p,i)],
 'longest_border':lambda s:max([i for i in range(1,len(s)) if s[:i]==s[-i:]],default=0),
 'minimum_period':lambda s:next((i for i in range(1,len(s)+1) if len(s)%i==0 and s[:i]*(len(s)//i)==s),0),
 'rotation_offset':lambda s,t:next((i for i in range(max(1,len(s))) if s[i:]+s[:i]==t),-1),
 'shortest_prefix_palindrome':lambda s:next((s[i:][::-1]+s for i in range(len(s),-1,-1) if s[i:][::-1]+s==(s[i:][::-1]+s)[::-1]),s),
 'longest_palindrome':lambda s:max([x for x in subarrays(s) if x==x[::-1]],key=len,default=''),
 'palindrome_count':lambda s:sum(x==x[::-1] for x in subarrays(s)),
 'palindrome_subsequence':lambda s:max(len(x) for x in subsets(s) if x==x[::-1]),
 'palindrome_insertions':lambda s:len(s)-max(len(x) for x in subsets(s) if x==x[::-1]),
 'common_substring':lambda s,t:max([len(x) for x in subarrays(s) if x in t],default=0),
 'word_break':lambda s,w:brute_word_ways(s,w)>0,
 'word_break_ways':brute_word_ways,
 'range_sums':lambda a,q:[sum(a[l:r+1]) for l,r in q],
 'range_gcds':lambda a,q:[math.gcd(*a[l:r+1]) for l,r in q],
 'range_minima':lambda a,q:[min(a[l:r+1]) for l,r in q],
 'point_updates':brute_operations,
 'dynamic_range_add':lambda a,o:brute_operations(a,o,True),
 'divisible_subarrays':lambda a,k:sum(sum(x)%k==0 for x in subarrays(a)),
 'longest_target_sum':lambda a,t:max([len(x) for x in subarrays(a) if sum(x)==t],default=0),
 'balanced_binary':lambda a:max([len(x) for x in subarrays(a) if x.count(0)==x.count(1)],default=0),
 'pivot_indices':brute_pivots,
 'products_except_self':lambda a:[math.prod(a[:i]+a[i+1:]) for i in range(len(a))],
 'count_smaller_right':lambda a:[sum(x<v for x in a[i+1:]) for i,v in enumerate(a)],
 'count_range_sums':lambda a,l,r:sum(l<=sum(x)<=r for x in subarrays(a)),
 'shortest_signed_subarray':lambda a,t:min([len(x) for x in subarrays(a) if sum(x)>=t],default=-1),
 'maximum_rectangle_sum':lambda m:max(brute_rectangles(m)),
 'submatrix_target_count':lambda m,t:brute_rectangles(m).count(t),
 'gas_start':lambda gas,cost:next((i for i in range(len(gas)) if all(sum((gas+gas)[i:i+j])-sum((cost+cost)[i:i+j])>=0 for j in range(1,len(gas)+1))),-1),
 'largest_kth':lambda a,k:sorted(a,reverse=True)[k-1],
 'top_frequencies':lambda a,k:[x for x,c in sorted(Counter(a).items(),key=lambda item:(-item[1],item[0]))[:k]],
 'twice_stream_medians':lambda a:[2*sorted(a[:i])[i//2] if i%2 else sorted(a[:i])[i//2-1]+sorted(a[:i])[i//2] for i in range(1,len(a)+1)],
 'sorted_matrix_kth':lambda m,k:sorted(x for row in m for x in row)[k-1],
 'modular_power':lambda a,e,m:pow(a,e,m),
 'modular_inverse':lambda a,m:next((i for i in range(m) if a*i%m==1),-1),
 'factorial_zeroes':lambda n:len(str(math.factorial(n)))-len(str(math.factorial(n)).rstrip('0')),
 'perfect_number':lambda n:sum(i for i in range(1,n) if n%i==0)==n,
 'divisor_count':lambda n:sum(n%i==0 for i in range(1,n+1)),
 'totient':lambda n:sum(math.gcd(n,i)==1 for i in range(1,n+1)),
 'interval_primes':lambda l,r:sum(n>=2 and all(n%d for d in range(2,math.isqrt(n)+1)) for n in range(l,r+1)),
 'count_digit_one':lambda n:sum(str(i).count('1') for i in range(n+1)),
 'floor_quotient_sum':lambda n:sum(n//i for i in range(1,n+1)),
 'two_congruences':lambda a,m,b,n:next((x for x in range(math.lcm(m,n)) if x%m==a and x%n==b),-1),
 'lower_bound_index':lambda a,t:next((i for i,v in enumerate(a) if v>=t),len(a)),
 'upper_bound_index':lambda a,t:next((i for i,v in enumerate(a) if v>t),len(a)),
 'rotated_search':lambda a,t:a.index(t) if t in a else -1,
 'rotated_minimum':min,
 'mountain_peak':lambda a:a.index(max(a)),
 'floor_sqrt':math.isqrt,
 'kth_missing_positive':lambda a,k:[i for i in range(1,max(a,default=0)+k+1) if i not in a][k-1],
 'equal_value_range':lambda a,t:[a.index(t),len(a)-1-a[::-1].index(t)] if t in a else [-1,-1],
 'duplicated_number':lambda a:next(x for x,c in Counter(a).items() if c>1),
 'first_missing_positive':lambda a:next(i for i in range(1,len(a)+2) if i not in a),
 'multiplication_kth':lambda n,m,k:sorted(i*j for i in range(1,n+1) for j in range(1,m+1))[k-1],
 'shipping_capacity':brute_ship,
 'split_largest_sum':brute_split,
 'maximum_min_distance':lambda a,k:max(min(y-x for x,y in zip(c,c[1:])) for c in itertools.combinations(sorted(a),k)),
 'kth_pair_distance':lambda a,k:sorted(abs(x-y) for x,y in itertools.combinations(a,2))[k-1],
 'important_reverse_pairs':lambda a:sum(a[i]>2*a[j] for i in range(len(a)) for j in range(i+1,len(a))),
 'maximum_product':lambda a:max(map(math.prod,subarrays(a))),
 'circular_maximum_sum':lambda a:max(sum((a+a)[i:i+length]) for i in range(len(a)) for length in range(1,len(a)+1)),
}


def main():
    checks=0;covered={};skipped=0
    for problem in load():
        name=problem['solver'].__name__;oracle=ORACLES.get(name)
        if oracle is None:continue
        for case in problem['spec']['judgeCases']:
            args=list(json.loads(case['input']).values())
            # Brute-force oracles intentionally stop at small sizes; large legal
            # boundary cases are checked separately by reference IO and runtime.
            if any(isinstance(x,(str,list)) and len(x)>12 for x in args):skipped+=1;continue
            if name=='topological_order' and args[0]>6:continue
            if name=='bipartite' and args[0]>12:continue
            if name in ('factorial_zeroes','perfect_number','divisor_count','totient','count_digit_one','floor_quotient_sum') and args[0]>1000:continue
            if name=='interval_primes' and args[1]-args[0]>300:continue
            if name=='burst_balloons' and len(args[0])>8:continue
            if name=='split_largest_sum' and len(args[0])>10:continue
            actual=oracle(*copy.deepcopy(args));expected=json.loads(case['output'])
            assert actual==expected,(problem['title'],args,actual,expected)
            checks+=1;covered[name]=covered.get(name,0)+1
    result={'passed':True,'independentOracleProblems':len(covered),'independentOracleChecks':checks,'coveredSolvers':covered}
    target=Path(__file__).parents[2]/'build'/'catalog-306'/'local-oracle-report.json';target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in result.items() if k!='coveredSolvers'}))


if __name__=='__main__':main()
