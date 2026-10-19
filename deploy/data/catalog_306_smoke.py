"""Generate independent Java/C++ acceptance solutions for seven representative topics."""
import json
from pathlib import Path
from generate_catalog_306 import load
from catalog_306_common import reference_code

JAVA={
 'connectedComponents':('int','''List<List<Integer>> g=new ArrayList<>();for(int i=0;i<n;i++)g.add(new ArrayList<>());for(int[] e:edges){g.get(e[0]).add(e[1]);g.get(e[1]).add(e[0]);}boolean[] seen=new boolean[n];int count=0;for(int s=0;s<n;s++){if(seen[s])continue;count++;Deque<Integer> q=new ArrayDeque<>();q.add(s);seen[s]=true;while(!q.isEmpty()){int u=q.remove();for(int v:g.get(u))if(!seen[v]){seen[v]=true;q.add(v);}}}return count;'''),
 'zeroOnePack':('int','''int[] dp=new int[capacity+1];for(int i=0;i<weights.length;i++)for(int c=capacity;c>=weights[i];c--)dp[c]=Math.max(dp[c],dp[c-weights[i]]+values[i]);return dp[capacity];'''),
 'allPatternPositions':('int[]','''List<Integer> a=new ArrayList<>();for(int i=0;i+pattern.length()<=s.length();i++)if(s.startsWith(pattern,i))a.add(i);int[] r=new int[a.size()];for(int i=0;i<r.length;i++)r[i]=a.get(i);return r;'''),
 'maximumSatisfiedChildren':('int','''Arrays.sort(children);Arrays.sort(cookies);int i=0;for(int x:cookies)if(i<children.length&&x>=children[i])i++;return i;'''),
 'staticRangeSums':('long[]','''long[] p=new long[nums.length+1];for(int i=0;i<nums.length;i++)p[i+1]=p[i]+nums[i];long[] r=new long[queries.length];for(int i=0;i<r.length;i++)r[i]=p[queries[i][1]+1]-p[queries[i][0]];return r;'''),
 'greatestCommonDivisor':('int','''while(b!=0){int t=a%b;a=b;b=t;}return a;'''),
 'lowerBoundPosition':('int','''int l=0,r=nums.length;while(l<r){int m=l+(r-l)/2;if(nums[m]<target)l=m+1;else r=m;}return l;'''),
}
CPP={
 'connectedComponents':('int','''vector<vector<int>> g(n);for(auto e:edges){g[e[0]].push_back(e[1]);g[e[1]].push_back(e[0]);}vector<int> seen(n);int count=0;for(int s=0;s<n;s++){if(seen[s])continue;count++;queue<int> q;q.push(s);seen[s]=1;while(!q.empty()){int u=q.front();q.pop();for(int v:g[u])if(!seen[v]){seen[v]=1;q.push(v);}}}return count;'''),
 'zeroOnePack':('int','''vector<int> dp(capacity+1);for(int i=0;i<(int)weights.size();i++)for(int c=capacity;c>=weights[i];c--)dp[c]=max(dp[c],dp[c-weights[i]]+values[i]);return dp[capacity];'''),
 'allPatternPositions':('vector<int>','''vector<int> r;for(size_t i=0;i+pattern.size()<=s.size();i++)if(s.compare(i,pattern.size(),pattern)==0)r.push_back(i);return r;'''),
 'maximumSatisfiedChildren':('int','''sort(children.begin(),children.end());sort(cookies.begin(),cookies.end());int i=0;for(int x:cookies)if(i<(int)children.size()&&x>=children[i])i++;return i;'''),
 'staticRangeSums':('vector<long long>','''vector<long long> p(nums.size()+1),r;for(size_t i=0;i<nums.size();i++)p[i+1]=p[i]+nums[i];for(auto q:queries)r.push_back(p[q[1]+1]-p[q[0]]);return r;'''),
 'greatestCommonDivisor':('int','''while(b){int t=a%b;a=b;b=t;}return a;'''),
 'lowerBoundPosition':('int','''return lower_bound(nums.begin(),nums.end(),target)-nums.begin();'''),
}


def code(problem,language,mode):
    if language=='python':return reference_code(problem,mode)
    signature=problem['spec'];method=signature['methodName'];params=signature['parameters'];names=','.join(p['name'] for p in params)
    if language=='java':
        types={'int':'int','int[]':'int[]','int[][]':'int[][]','string':'String'}
        ret,body=JAVA[method];solution='class Solution { public '+ret+' '+method+'('+','.join(types[p['type']]+' '+p['name'] for p in params)+'){'+body+'}}'
        if mode=='FUNCTION':return 'import java.util.*;\n'+solution
        reads='Scanner in=new Scanner(System.in);'
        for p in params:
            name=p['name'];kind=p['type']
            if kind=='string':reads+='String '+name+'=in.nextLine();'
            elif kind=='int':reads+='int '+name+'=in.nextInt();'
            elif kind=='int[]':reads+='int n_'+name+'=in.nextInt();int[] '+name+'=new int[n_'+name+'];for(int i=0;i<n_'+name+';i++)'+name+'[i]=in.nextInt();'
            else:reads+='int r_'+name+'=in.nextInt(),c_'+name+'=in.nextInt();int[][] '+name+'=new int[r_'+name+'][c_'+name+'];for(int i=0;i<r_'+name+';i++)for(int j=0;j<c_'+name+';j++)'+name+'[i][j]=in.nextInt();'
        invoke='new Solution().'+method+'('+names+')'
        output=(''+ret+' result='+invoke+';StringBuilder out=new StringBuilder();for(int i=0;i<result.length;i++){if(i>0)out.append(" ");out.append(result[i]);}System.out.println(out);') if ret.endswith('[]') else 'System.out.println('+invoke+');'
        return 'import java.util.*;\n'+solution+'\npublic class Main {public static void main(String[] args){'+reads+output+'}}'
    types={'int':'int','int[]':'vector<int>&','int[][]':'vector<vector<int>>&','string':'string'}
    ret,body=CPP[method];header='#include <bits/stdc++.h>\nusing namespace std;\n'
    solution='class Solution {public: '+ret+' '+method+'('+','.join(types[p['type']]+' '+p['name'] for p in params)+'){'+body+'}};'
    if mode=='FUNCTION':return header+solution
    reads='ios::sync_with_stdio(false);cin.tie(nullptr);'
    for p in params:
        name=p['name'];kind=p['type']
        if kind=='string':reads+='string '+name+';getline(cin,'+name+');'
        elif kind=='int':reads+='int '+name+';cin>>'+name+';'
        elif kind=='int[]':reads+='int n_'+name+';cin>>n_'+name+';vector<int> '+name+'(n_'+name+');for(auto& x:'+name+')cin>>x;'
        else:reads+='int r_'+name+',c_'+name+';cin>>r_'+name+'>>c_'+name+';vector<vector<int>> '+name+'(r_'+name+',vector<int>(c_'+name+'));for(auto& row:'+name+')for(auto& x:row)cin>>x;'
    output='auto result=Solution().'+method+'('+names+');'
    if ret.startswith('vector'):output+='for(size_t i=0;i<result.size();i++){if(i)cout<<" ";cout<<result[i];}cout<<"\\n";'
    else:output+='cout<<result<<"\\n";'
    return header+solution+'\nint main(){'+reads+output+'}'


def main():
    fixtures=[]
    for i,problem in enumerate(load()):
        if i%20:continue
        for language in ('java','python','cpp'):
            for mode in ('FUNCTION','ACM'):
                fixtures.append({'problemNo':167+i,'language':language,'mode':mode,'code':code(problem,language,mode),'expected':'accepted'})
    fixtures.extend([
        {'problemNo':167,'language':'java','mode':'FUNCTION','code':'class Solution { syntax error }','expected':'compile_error'},
        {'problemNo':167,'language':'python','mode':'FUNCTION','code':'class Solution:\n    def connectedComponents(self,n,edges):\n        return 0\n','expected':'wrong_answer'},
        {'problemNo':167,'language':'python','mode':'FUNCTION','code':'class Solution:\n    def connectedComponents(self,n,edges):\n        return 1//0\n','expected':'runtime_error'},
    ])
    target=Path(__file__).parents[2]/'build'/'catalog-306'/'smoke-fixtures.json'
    target.write_text(json.dumps(fixtures,ensure_ascii=False),encoding='utf-8')
    print(json.dumps({'acceptanceFixtures':len(fixtures),'positive':42,'negative':3}))


if __name__=='__main__':main()
