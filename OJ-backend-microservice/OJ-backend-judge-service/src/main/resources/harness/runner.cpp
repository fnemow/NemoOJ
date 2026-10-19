#include <nlohmann/json.hpp>
// Common C++17 algorithm libraries are visible before the user's Solution class.
#include <algorithm>
#include <array>
#include <bitset>
#include <cassert>
#include <climits>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <functional>
#include <iomanip>
#include <iterator>
#include <list>
#include <map>
#include <memory>
#include <numeric>
#include <set>
#include <sstream>
#include <stack>
#include <tuple>
#include <unordered_map>
#include <utility>
#include <iostream>
#include <vector>
#include <string>
#include <queue>
#include <unordered_set>
#include <cmath>
#include <limits>
#include <type_traits>
#include <chrono>
#include <cstdio>
#include <unistd.h>
#include "nodes.hpp"
using namespace std;
using json = nlohmann::json;
namespace nemo_protocol {
inline int fd=[] { int value=dup(STDOUT_FILENO); if(value<0 || dup2(STDERR_FILENO,STDOUT_FILENO)<0) std::abort(); return value; }();
}
#include "solution.hpp"
namespace nemo {
inline void require(bool ok) { if (!ok) throw runtime_error("Invalid value or node limit"); }
template<class T> struct Codec {
    static T read(const json& v) {
        if constexpr (is_same_v<T, bool>) { require(v.is_boolean()); }
        else if constexpr (is_integral_v<T>) {
            require(v.is_number_integer());
            if (v.is_number_unsigned()) require(v.get<uint64_t>() <= uint64_t(numeric_limits<T>::max()));
            else { auto n=v.get<int64_t>(); require(n>=numeric_limits<T>::min() && n<=numeric_limits<T>::max()); }
        } else if constexpr (is_floating_point_v<T>) { require(v.is_number() && isfinite(v.get<double>())); }
        else if constexpr (is_same_v<T,string>) { require(v.is_string()); }
        return v.get<T>();
    }
    static json write(const T& value) {
        if constexpr (is_floating_point_v<T>) require(isfinite(value));
        if constexpr (is_integral_v<T> && !is_same_v<T,bool>) {
            if constexpr (is_unsigned_v<T>) require(value<=uint64_t(numeric_limits<int64_t>::max()));
            return json(static_cast<int64_t>(value));
        } else return value;
    }
};
template<class T> struct Codec<vector<T>> {
    static vector<T> read(const json& v) {
        require(v.is_array()); vector<T> result;
        for (const auto& item:v) result.push_back(Codec<T>::read(item));
        return result;
    }
    template<class U> static json write(const vector<U>& v) {
        json out=json::array();
        for (size_t i=0;i<v.size();++i) {
            // int64_t can alias long on Linux while the public template uses
            // long long. Validate the wire value before converting each item.
            auto item=Codec<U>::write(static_cast<U>(v[i]));
            out.push_back(Codec<T>::write(Codec<T>::read(item)));
        }
        return out;
    }
};
template<> struct Codec<ListNode*> {
    static ListNode* read(const json& v) {
        if (v.is_null()) return nullptr;
        require(v.is_array() && v.size()<=10000);
        ListNode dummy; auto tail=&dummy;
        for (const auto& item:v) { tail->next=new ListNode(Codec<int32_t>::read(item)); tail=tail->next; }
        return dummy.next;
    }
    static json write(ListNode* node) {
        if (!node) return nullptr;
        json result=json::array(); unordered_set<ListNode*> seen;
        while(node) {
            require(seen.size()<10000 && seen.insert(node).second);
            result.push_back(node->val); node=node->next;
        }
        return result;
    }
};
template<> struct Codec<TreeNode*> {
    static TreeNode* read(const json& v) {
        if(v.is_null()) return nullptr;
        require(v.is_array() && v.size()<=20001);
        if(v.empty()) return nullptr;
        if(v[0].is_null()) { for(const auto& item:v) require(item.is_null()); return nullptr; }
        auto root=new TreeNode(Codec<int32_t>::read(v[0]));
        queue<pair<TreeNode*,int>> q; q.push({root,1}); size_t i=1,count=1;
        while(!q.empty() && i<v.size()) {
            auto [node,depth]=q.front(); q.pop();
            for(auto slot:{&node->left,&node->right}) {
                if(i>=v.size()) break;
                if(!v[i].is_null()) {
                    require(++count<=10000 && depth<1000);
                    *slot=new TreeNode(Codec<int32_t>::read(v[i])); q.push({*slot,depth+1});
                }
                ++i;
            }
        }
        while(i<v.size()) require(v[i++].is_null());
        return root;
    }
    static json write(TreeNode* root) {
        json result=json::array(); if(!root) return nullptr;
        queue<pair<TreeNode*,int>> q; unordered_set<TreeNode*> seen; q.push({root,1});
        while(!q.empty()) {
            auto [node,depth]=q.front(); q.pop();
            if(!node) {result.push_back(nullptr); continue;}
            require(depth<=1000 && seen.size()<10000 && seen.insert(node).second);
            result.push_back(node->val); q.push({node->left,depth+1}); q.push({node->right,depth+1});
        }
        while(!result.empty() && result.back().is_null()) result.erase(result.size()-1);
        return result;
    }
};
}
int main() {
    int protocol=nemo_protocol::fd;
    if(protocol<0) return 1;
    json response;
    try {
        json input=json::parse(cin); nemo::require(input.is_object());
        Solution solution;
__BODY__
    } catch(const std::exception& e) { response={{"status","RUNTIME_ERROR"},{"error",e.what()}}; }
      catch(...) { response={{"status","RUNTIME_ERROR"},{"error","Unknown exception"}}; }
    string wire;
    try { wire=response.dump(); } catch(...) { wire="{\"status\":\"RUNTIME_ERROR\"}"; }
    size_t offset=0;
    while(offset<wire.size()) { auto n=write(protocol,wire.data()+offset,wire.size()-offset); if(n<=0) return 1; offset+=n; }
    close(protocol);
}
