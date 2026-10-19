"""Full-judge fixtures: every new Python solution and independent Java/C++ codecs."""
import json
from pathlib import Path
from catalog_326_strings import load
from catalog_306_common import reference_code


JAVA = {
 'longestArrayCommonPrefix': ('String', '''if(words.length==0)return "";String p=words[0];for(String w:words){int i=0;while(i<p.length()&&i<w.length()&&p.charAt(i)==w.charAt(i))i++;p=p.substring(0,i);}return p;''', ''),
 'countExactlyKDistinctSubstrings': ('long', 'return atMost(s,k)-atMost(s,k-1);', '''private long atMost(String s,int k){if(k<0)return 0;int[] freq=new int[128];int left=0,distinct=0;long count=0;for(int right=0;right<s.length();right++){if(freq[s.charAt(right)]++==0)distinct++;while(distinct>k){if(--freq[s.charAt(left++)]==0)distinct--;}count+=right-left+1;}return count;}'''),
 'restoreIPv4Addresses': ('String[]', '''List<String> out=new ArrayList<>();dfs(s,0,new ArrayList<String>(),out);Collections.sort(out);return out.toArray(new String[0]);''', '''private void dfs(String s,int at,List<String> parts,List<String> out){int remaining=4-parts.size();if(s.length()-at<remaining||s.length()-at>3*remaining)return;if(remaining==0){out.add(String.join(".",parts));return;}for(int size=1;size<=3&&at+size<=s.length();size++){String p=s.substring(at,at+size);if(size>1&&p.charAt(0)=='0')break;if(Integer.parseInt(p)>255)break;parts.add(p);dfs(s,at+size,parts,out);parts.remove(parts.size()-1);}}'''),
}
CPP = {
 'longestArrayCommonPrefix': ('string', '''if(words.empty())return "";string p=words[0];for(auto& w:words){size_t i=0;while(i<p.size()&&i<w.size()&&p[i]==w[i])i++;p.resize(i);}return p;''', ''),
 'countExactlyKDistinctSubstrings': ('long long', 'return atMost(s,k)-atMost(s,k-1);', '''long long atMost(const string& s,int k){if(k<0)return 0;int freq[128]={},left=0,distinct=0;long long count=0;for(int right=0;right<(int)s.size();right++){if(freq[(unsigned char)s[right]]++==0)distinct++;while(distinct>k){if(--freq[(unsigned char)s[left++]]==0)distinct--;}count+=right-left+1;}return count;}'''),
 'restoreIPv4Addresses': ('vector<string>', '''vector<string> parts,out;dfs(s,0,parts,out);sort(out.begin(),out.end());return out;''', '''void dfs(const string& s,int at,vector<string>& parts,vector<string>& out){int remaining=4-(int)parts.size(),length=(int)s.size()-at;if(length<remaining||length>3*remaining)return;if(remaining==0){out.push_back(parts[0]+"."+parts[1]+"."+parts[2]+"."+parts[3]);return;}for(int size=1;size<=3&&at+size<=(int)s.size();size++){string p=s.substr(at,size);if(size>1&&p[0]=='0')break;if(stoi(p)>255)break;parts.push_back(p);dfs(s,at+size,parts,out);parts.pop_back();}}'''),
}


def code(problem, language, mode):
    spec = problem['spec']; method = spec['methodName']; params = spec['parameters']
    names = ','.join(p['name'] for p in params)
    if language == 'java':
        types = {'string': 'String', 'string[]': 'String[]', 'int': 'int'}
        ret, body, helpers = JAVA[method]
        solution = 'class Solution { public ' + ret + ' ' + method + '(' + ','.join(types[p['type']] + ' ' + p['name'] for p in params) + '){' + body + '}' + helpers + '}'
        if mode == 'FUNCTION': return solution
        reads = 'BufferedReader in=new BufferedReader(new InputStreamReader(System.in));'
        for p in params:
            n, kind = p['name'], p['type']
            if kind == 'string': reads += 'String ' + n + '=in.readLine();'
            elif kind == 'int': reads += 'int ' + n + '=Integer.parseInt(in.readLine().trim());'
            else: reads += 'int count_' + n + '=Integer.parseInt(in.readLine().trim());String[] ' + n + '=new String[count_' + n + '];for(int i=0;i<count_' + n + ';i++)' + n + '[i]=in.readLine();'
        call = 'new Solution().' + method + '(' + names + ')'
        output = 'System.out.println(String.join(" ",' + call + '));' if ret == 'String[]' else 'System.out.println(' + call + ');'
        return 'import java.util.*;import java.io.*;\n' + solution + '\npublic class Main {public static void main(String[] args)throws Exception{' + reads + output + '}}'
    types = {'string': 'string', 'string[]': 'vector<string>&', 'int': 'int'}
    ret, body, helpers = CPP[method]
    solution = 'class Solution {public: ' + ret + ' ' + method + '(' + ','.join(types[p['type']] + ' ' + p['name'] for p in params) + '){' + body + '}' + helpers + '};'
    if mode == 'FUNCTION': return solution
    reads = 'ios::sync_with_stdio(false);cin.tie(nullptr);string line;'
    for p in params:
        n, kind = p['name'], p['type']
        if kind == 'string': reads += 'string ' + n + ';getline(cin,' + n + ');'
        elif kind == 'int': reads += 'getline(cin,line);int ' + n + '=stoi(line);'
        else: reads += 'getline(cin,line);int count_' + n + '=stoi(line);vector<string> ' + n + '(count_' + n + ');for(auto& x:' + n + ')getline(cin,x);'
    output = 'auto result=Solution().' + method + '(' + names + ');'
    output += 'for(size_t i=0;i<result.size();i++){if(i)cout<<" ";cout<<result[i];}cout<<"\\n";' if ret == 'vector<string>' else 'cout<<result<<"\\n";'
    return '#include <bits/stdc++.h>\nusing namespace std;\n' + solution + '\nint main(){' + reads + output + '}'


def main():
    fixtures = []
    for n, problem in enumerate(load(), 307):
        for mode in ('FUNCTION', 'ACM'):
            fixtures.append({'problemNo': n, 'language': 'python', 'mode': mode, 'code': reference_code(problem, mode), 'expected': 'accepted'})
        if problem['spec']['methodName'] in JAVA:
            for language in ('java', 'cpp'):
                for mode in ('FUNCTION', 'ACM'):
                    fixtures.append({'problemNo': n, 'language': language, 'mode': mode, 'code': code(problem, language, mode), 'expected': 'accepted'})
    assert len(fixtures) == 52
    path = Path(__file__).parents[2] / 'build/catalog-326/smoke-fixtures.json'
    path.write_text(json.dumps(fixtures, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({'formalFixtures': len(fixtures), 'pythonEveryProblemBothModes': 40, 'javaCppRepresentativeBothModes': 12}))


if __name__ == '__main__': main()
