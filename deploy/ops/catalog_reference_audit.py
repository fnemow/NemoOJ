"""Independent brute-force checks of representative catalog reference algorithms.
Synthetic inputs only; no database writes and no hidden-case output.
"""
import itertools,json,sys
from collections import Counter
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'data'))
import generate_hundred_plus_catalog as catalog

counts=Counter()
def check(name,actual,expected):
 counts[name]+=1
 if actual!=expected:raise AssertionError(name+' reference differs from brute-force oracle')

def main(output):
 for size in range(7):
  for values in itertools.product(range(3),repeat=size):
   a=list(values)
   check('largestRectangle',catalog.largest_rectangle_area(a),max([0]+[min(a[l:r])*(r-l) for l in range(size) for r in range(l+1,size+1)]))
   check('trapWater',catalog.trap_water(a),sum(max(0,min(max(a[:i+1]),max(a[i:]))-v) for i,v in enumerate(a)))
   check('sumSubarrayMinimum',catalog.sum_subarray_extreme(a),sum(min(a[l:r]) for l in range(size) for r in range(l+1,size+1)))
   check('has132',catalog.has_132(a),any(a[i]<a[k]<a[j] for i in range(size) for j in range(i+1,size) for k in range(j+1,size)))
   expected=[next((v for v in a[i+1:] if v>a[i]),0) for i in range(size)]
   check('linkedNextGreater',catalog.next_greater_nodes(a),expected)
   for target in [0,1,3]:
    check('subarraySum',catalog.subarray_sum_count(a,target),sum(sum(a[l:r])==target for l in range(size) for r in range(l+1,size+1)))
 for size in range(7):
  for chars in itertools.product('abc',repeat=size):
   text=''.join(chars)
   segments=[text[l:r] for l in range(size) for r in range(l+1,size+1)]
   check('longestUnique',catalog.longest_unique(text),max([0]+[len(s) for s in segments if len(set(s))==len(s)]))
   for pattern in ['ab','aa','d']:
    needed=Counter(pattern);options=[s for s in segments if not (needed-Counter(s))]
    expected=min(options,key=len) if options else ''
    check('minimumWindow',catalog.minimum_window(text,pattern),expected)
 for bits in itertools.product([0,1],repeat=6):
  matrix=[list(bits[:3]),list(bits[3:])]
  best=max([0]+[side*side for side in [1,2] for row in range(3-side) for col in range(4-side)
                if all(matrix[r][c] for r in range(row,row+side) for c in range(col,col+side))])
  check('largestSquare',catalog.largest_square(matrix),best)
 for n in range(1,8):
  expected=sum(len({r-c for r,c in enumerate(p)})==n and len({r+c for r,c in enumerate(p)})==n for p in itertools.permutations(range(n)))
  check('nQueens',catalog.n_queens(n),expected)
 for n in range(1,16):
  level=list(range(1,n+1))
  def inorder(index):
   return [] if index>=n else inorder(2*index+1)+[level[index]]+inorder(2*index+2)
  check('binaryTreeInorder',catalog.inorder(catalog.tree_from_level(level)),inorder(0))
 for size in range(2,6):
  for values in itertools.product(range(4),repeat=size):
   a=list(values);pairs=[(a[i],a[j]) for i in range(size) for j in range(i+1,size)]
   triples=[(a[i],a[j],a[k]) for i in range(size) for j in range(i+1,size) for k in range(j+1,size)]
   check('maximumXorPair',catalog.max_xor_pair(a),max(x^y for x,y in pairs))
   check('maximumContainer',catalog.max_container(a),max((j-i)*min(a[i],a[j]) for i in range(size) for j in range(i+1,size)))
   for target in [0,3,7]:
    check('pairExists',catalog.pair_sum_exists(a,target),any(x+y==target for x,y in pairs))
    check('pairCount',catalog.count_pair_sum(a,target),sum(x+y==target for x,y in pairs))
    check('closestPair',catalog.closest_pair_gap(a,target),min(abs(x+y-target) for x,y in pairs))
    check('maximumPairBelow',catalog.max_pair_below(a,target),max([x+y for x,y in pairs if x+y<target],default=-1))
    check('tripletExists',catalog.triplet_sum_exists(a,target),any(sum(t)==target for t in triples))
    check('distinctTriplets',catalog.three_sum_count(a,target),len({tuple(sorted(t)) for t in triples if sum(t)==target}))
   check('inversions',catalog.inversion_count(a),sum(a[i]>a[j] for i in range(size) for j in range(i+1,size)))
 result={'checks':sum(counts.values()),'algorithms':dict(counts),'passed':True,'scope':'Representative algorithms, not a proof of all 166 problems','writesPerformed':False}
 Path(output).write_text(json.dumps(result),encoding='utf-8');print(json.dumps(result))
if __name__=='__main__':main(sys.argv[1])
