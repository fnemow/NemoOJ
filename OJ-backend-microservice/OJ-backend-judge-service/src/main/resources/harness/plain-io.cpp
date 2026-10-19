#include <sstream>
#include <iomanip>
inline string plain_element(const string& t) {
    if(t.size()>2 && t.substr(t.size()-2)=="[]") return t.substr(0,t.size()-2);
    if(t.rfind("list<",0)==0) return t.substr(5,t.size()-6);
    return "";
}
inline json plain_read(const string& t) {
    string line; if(!getline(cin,line)) throw runtime_error("missing input"); if(!line.empty()&&line.back()=='\r') line.pop_back();
    string e=plain_element(t); bool nodes=t=="list_node<int>"||t=="tree_node<int>";
    if(!e.empty()||nodes) {
        int n=stoi(line); if(nodes&&n==-1) return nullptr;
        if(n<0||n>10000) throw runtime_error("length"); json a=json::array();
        for(int i=0;i<n;i++) {
            if(t=="tree_node<int>") { string v; if(!getline(cin,v)) throw runtime_error("missing node"); if(!v.empty()&&v.back()=='\r') v.pop_back(); if(v=="null") a.push_back(nullptr); else a.push_back(stoi(v)); }
            else a.push_back(plain_read(e.empty()?"int":e));
        } return a;
    }
    if(t=="string") return line;
    if(t=="boolean") { if(line!="true"&&line!="false") throw runtime_error("boolean"); return line=="true"; }
    if(t=="double") return stod(line);
    return stoll(line);
}
inline string plain_format(const json& v,const string& t) {
    string e=plain_element(t); bool nodes=t=="list_node<int>"||t=="tree_node<int>";
    if(!e.empty()||nodes) {
        if(nodes&&v.is_null()) return "-1\n";
        string out=to_string(v.size())+"\n"; for(const auto& x:v) out+=(nodes&&x.is_null())?"null\n":plain_format(x,e.empty()?"int":e); return out;
    }
    if(t=="double") { ostringstream out; out<<scientific<<setprecision(16)<<v.get<double>(); return out.str()+"\n"; }
    return (t=="string"?v.get<string>():v.dump())+"\n";
}
