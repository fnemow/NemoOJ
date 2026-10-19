"""Synthetic acceptance sources only. No production/user code is copied into evidence."""
from cloud_acceptance import CODES

FUNCTION = {
 3:CODES,
 4:{
 'java':'class Solution {public int maxSubArray(int[] nums){int b=nums[0],s=0;for(int x:nums){s=Math.max(x,s+x);b=Math.max(b,s);}return b;}}',
 'python':'class Solution:\n def maxSubArray(self, nums):\n  best=nums[0]; total=0\n  for x in nums:\n   total=max(x,total+x);best=max(best,total)\n  return best',
 'cpp':'#include <vector>\n#include <algorithm>\nusing namespace std;class Solution{public:int maxSubArray(vector<int>& nums){int best=nums[0],s=0;for(int x:nums){s=max(x,s+x);best=max(best,s);}return best;}};'},
 13:{
 'java':'class Solution {public ListNode reverseList(ListNode head){ListNode prev=null;while(head!=null){ListNode n=head.next;head.next=prev;prev=head;head=n;}return prev;}}',
 'python':'class Solution:\n def reverseList(self,head):\n  p=None\n  while head:\n   n=head.next;head.next=p;p=head;head=n\n  return p',
 'cpp':'class Solution{public:ListNode* reverseList(ListNode* head){ListNode* p=nullptr;while(head){auto n=head->next;head->next=p;p=head;head=n;}return p;}};'},
 16:{
 'java':'class Solution{public int maxDepth(TreeNode root){if(root==null)return 0;return 1+Math.max(maxDepth(root.left),maxDepth(root.right));}}',
 'python':'class Solution:\n def maxDepth(self,root):\n  return 0 if root is None else 1+max(self.maxDepth(root.left),self.maxDepth(root.right))',
 'cpp':'#include <algorithm>\nclass Solution{public:int maxDepth(TreeNode* root){return root?1+std::max(maxDepth(root->left),maxDepth(root->right)):0;}};'},
 19:{
 'java':'class Solution{public int trap(int[] height){int l=0,r=height.length-1,a=0,b=0,s=0;while(l<=r){if(a<=b){a=Math.max(a,height[l]);s+=a-height[l++];}else{b=Math.max(b,height[r]);s+=b-height[r--];}}return s;}}',
 'python':'class Solution:\n def trap(self,height):\n  l=0;r=len(height)-1;a=b=s=0\n  while l<=r:\n   if a<=b:\n    a=max(a,height[l]);s+=a-height[l];l+=1\n   else:\n    b=max(b,height[r]);s+=b-height[r];r-=1\n  return s',
 'cpp':'#include <vector>\n#include <algorithm>\nusing namespace std;class Solution{public:int trap(vector<int>& height){int l=0,r=(int)height.size()-1,a=0,b=0,s=0;while(l<=r){if(a<=b){a=max(a,height[l]);s+=a-height[l++];}else{b=max(b,height[r]);s+=b-height[r--];}}return s;}};'},
 21:{
 'java':'class Solution{public int minDistance(String word1,String word2){int[] d=new int[word2.length()+1];for(int j=0;j<d.length;j++)d[j]=j;for(int i=1;i<=word1.length();i++){int last=d[0];d[0]=i;for(int j=1;j<d.length;j++){int old=d[j];d[j]=word1.charAt(i-1)==word2.charAt(j-1)?last:1+Math.min(last,Math.min(d[j],d[j-1]));last=old;}}return d[word2.length()];}}',
 'python':'class Solution:\n def minDistance(self,word1,word2):\n  d=list(range(len(word2)+1))\n  for i,a in enumerate(word1,1):\n   last=d[0];d[0]=i\n   for j,b in enumerate(word2,1):\n    old=d[j];d[j]=last if a==b else 1+min(last,d[j],d[j-1]);last=old\n  return d[-1]',
 'cpp':'#include <string>\n#include <vector>\n#include <algorithm>\nusing namespace std;class Solution{public:int minDistance(string word1,string word2){vector<int>d(word2.size()+1);for(int j=0;j<(int)d.size();j++)d[j]=j;for(int i=1;i<=(int)word1.size();i++){int last=d[0];d[0]=i;for(int j=1;j<(int)d.size();j++){int old=d[j];d[j]=word1[i-1]==word2[j-1]?last:1+min(last,min(d[j],d[j-1]));last=old;}}return d.back();}};'}
}

ACM = {
 3:{
 'python':'import sys,bisect\na=list(map(int,sys.stdin.read().split()));n,t=a[:2];v=a[2:];p=bisect.bisect_left(v,t);print(p if p<n and v[p]==t else -1)',
 'java':'import java.util.*;public class Main{public static void main(String[]x){Scanner s=new Scanner(System.in);int n=s.nextInt(),t=s.nextInt();int[]a=new int[n];for(int i=0;i<n;i++)a[i]=s.nextInt();int p=Arrays.binarySearch(a,t);System.out.println(p<0?-1:p);}}',
 'cpp':'#include <iostream>\n#include <vector>\n#include <algorithm>\nusing namespace std;int main(){int n,t;cin>>n>>t;vector<int>a(n);for(auto&x:a)cin>>x;auto p=lower_bound(a.begin(),a.end(),t);cout<<(p!=a.end()&&*p==t?p-a.begin():-1);}'},
 13:{
 'python':'import sys\na=list(map(int,sys.stdin.read().split()));print(*a[1:][::-1])',
 'java':'import java.util.*;public class Main{public static void main(String[]x){Scanner s=new Scanner(System.in);int n=s.nextInt();int[]a=new int[n];for(int i=0;i<n;i++)a[i]=s.nextInt();for(int i=n-1;i>=0;i--)System.out.print(a[i]+" ");}}',
 'cpp':'#include <iostream>\n#include <vector>\nusing namespace std;int main(){int n;cin>>n;vector<int>a(n);for(auto&x:a)cin>>x;for(int i=n-1;i>=0;i--)cout<<a[i]<<" ";}'},
}
ERRORS={
 'compile_error':{'python':'def broken(:','java':'class Solution { invalid };','cpp':'class Solution { invalid };'},
 'runtime_error':{'python':'class Solution:\n def search(self,nums,target): raise RuntimeError("acceptance")','java':'class Solution{public int search(int[] nums,int target){throw new RuntimeException("acceptance");}}','cpp':'#include <stdexcept>\n#include <vector>\nclass Solution{public:int search(std::vector<int>& nums,int target){throw std::runtime_error("acceptance");}};'},
 'time_limit_exceeded':{'python':'class Solution:\n def search(self,nums,target):\n  while True: pass','java':'class Solution{public int search(int[] nums,int target){while(true){}}}','cpp':'#include <vector>\nclass Solution{public:int search(std::vector<int>& nums,int target){while(true){volatile int x=0;x++;}}};'}
}

SCENARIOS = [(n,'FUNCTION','accepted') for n in FUNCTION] + [(n,'ACM','accepted') for n in ACM] + [(3,'FUNCTION',v) for v in ERRORS]

def scenario(sequence):
    # Rotate languages within each scenario over successive complete cycles.
    number,mode,expected=SCENARIOS[sequence%len(SCENARIOS)]
    language=['java','python','cpp'][(sequence//len(SCENARIOS)+sequence%len(SCENARIOS))%3]
    source=ERRORS[expected] if expected in ERRORS else (FUNCTION if mode=='FUNCTION' else ACM)[number]
    return number,mode,expected,language,source[language]
