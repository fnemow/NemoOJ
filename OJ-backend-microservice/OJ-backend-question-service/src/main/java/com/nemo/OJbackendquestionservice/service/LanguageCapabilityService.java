package com.nemo.OJbackendquestionservice.service;
import com.nemo.OJserviceclient.JudgeFeignClient;
import com.nemo.OJbackendmodel.model.dto.question.ProblemSpec;
import com.nemo.OJbackendmodel.model.entity.Question;
import com.nemo.OJbackendmodel.model.execution.LanguageCompatibility;
import com.nemo.OJbackendcommon.common.ErrorCode;
import com.nemo.OJbackendcommon.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.core.env.Environment;
import java.util.*;
@Service
public class LanguageCapabilityService {
    private final JudgeFeignClient judge;
    private final Environment env;
    public LanguageCapabilityService(JudgeFeignClient judge,Environment env) { this.judge=judge;this.env=env; }
    public List<Map<String,Object>> available(ProblemSpec spec) {
        return available(spec,spec==null ? "ACM" : "FUNCTION");
    }
    public List<Map<String,Object>> available(ProblemSpec spec,String requestedMode) {
        String mode=normalizeMode(requestedMode,spec);
        boolean function="FUNCTION".equals(mode);
        if(function && spec==null) return List.of();
        ProblemSpec compatibilitySpec=function ? spec : null;
        Set<String> enabled=new HashSet<>(Arrays.stream(env.getProperty("judge.language.enabled","java").split(",")).map(String::trim).toList());
        List<Map<String,Object>> result=new ArrayList<>();
        List<Map<String,Object>> remote;
        try { remote=judge.capabilities(); } catch(RuntimeException e) { throw new BusinessException(ErrorCode.SYSTEM_ERROR,"判题运行时暂不可用"); }
        if(remote==null) throw new BusinessException(ErrorCode.SYSTEM_ERROR,"判题运行时暂不可用");
        for(var capability:remote) {
            String language=String.valueOf(capability.get("language"));
            if(!enabled.contains(language) || !Boolean.TRUE.equals(capability.get("imageReady"))) continue;
            if(!(capability.get("modes") instanceof List<?> modes) || !modes.contains(mode)) continue;
            if(!LanguageCompatibility.modeEnabled(env,"judge",language,function)) continue;
            if(LanguageCompatibility.reason(language,compatibilitySpec)!=null) continue;
            result.add(new LinkedHashMap<>(capability));
        }
        return result;
    }
    public Map<String,Object> require(String language,ProblemSpec spec) {
        return require(language,spec,spec==null ? "ACM" : "FUNCTION");
    }
    public Map<String,Object> require(String language,ProblemSpec spec,String mode) {
        return available(spec,mode).stream().filter(c->language.equals(c.get("language"))).findFirst()
            .orElseThrow(()->new BusinessException(ErrorCode.SYSTEM_ERROR,"语言运行时暂不可用"));
    }
    public List<Map<String,Object>> publicCapabilities(ProblemSpec spec) {
        return publicCapabilities(spec,spec==null ? "ACM" : "FUNCTION");
    }
    public List<Map<String,Object>> publicCapabilities(ProblemSpec spec,String mode) {
        var result=available(spec,mode);
        for(var entry:result) {
            entry.remove("imageDigest");
            entry.put("available",true);
            entry.put("template",template(String.valueOf(entry.get("language")),"FUNCTION".equals(normalizeMode(mode,spec)) ? spec : null));
        }
        for(String language:List.of("java","python","cpp")) {
            if(result.stream().anyMatch(c->language.equals(c.get("language")))) continue;
            String reason=LanguageCompatibility.reason(language,"FUNCTION".equals(normalizeMode(mode,spec)) ? spec : null);
            result.add(new LinkedHashMap<>(Map.of("language",language,"available",false,"reason",reason==null ? "Language/mode is disabled or runtime unavailable" : reason)));
        }
        return result;
    }

    public Map<String,Object> problemCapabilities(Question question) {
        ProblemSpec spec=question.getProblemSpec();
        String defaultMode=spec==null ? "ACM" : "FUNCTION";
        Map<String,List<Map<String,Object>>> byMode=new LinkedHashMap<>();
        byMode.put("ACM",publicCapabilities(spec,"ACM"));
        if(spec!=null) byMode.put("FUNCTION",publicCapabilities(spec,"FUNCTION"));
        List<Map<String,Object>> languages=new ArrayList<>();
        for(String id:List.of("java","python","cpp")) {
            List<String> modes=new ArrayList<>();
            Map<String,Object> selected=null;
            for(var modeEntry:byMode.entrySet()) {
                Map<String,Object> item=modeEntry.getValue().stream().filter(v->id.equals(v.get("language"))).findFirst().orElse(null);
                if(item!=null && Boolean.TRUE.equals(item.get("available"))) {
                    modes.add(modeEntry.getKey());
                    if(selected==null || defaultMode.equals(modeEntry.getKey())) selected=item;
                }
            }
            boolean enabled=!modes.isEmpty();
            Map<String,Object> language=new LinkedHashMap<>();
            language.put("id",id);
            language.put("displayName",displayName(id));
            language.put("runtimeId",enabled ? String.valueOf(selected.getOrDefault("runtimeId",id)) : "");
            language.put("enabled",enabled);
            language.put("modes",modes);
            if(!enabled) language.put("disabledReason","运行环境暂不可用");
            language.put("maxCodeBytes",524288);
            long baseTime=question.getJudgeConfig()!=null && question.getJudgeConfig().getTimeLimit()!=null
                    ? question.getJudgeConfig().getTimeLimit() : 10000L;
            long memory=question.getJudgeConfig()!=null && question.getJudgeConfig().getMemoryLimit()!=null
                    ? question.getJudgeConfig().getMemoryLimit() : 262144L;
            long output=question.getJudgeConfig()!=null && question.getJudgeConfig().getOutputLimitBytes()!=null
                    ? question.getJudgeConfig().getOutputLimitBytes() : 1048576L;
            double factor=enabled && selected.get("runtimeFactor") instanceof Number n ? n.doubleValue() : 1.0;
            language.put("effectiveLimits",Map.of("timeLimitMs",Math.toIntExact((long)Math.ceil(baseTime*factor)),
                    "memoryLimitKB",Math.toIntExact(memory),"outputLimitBytes",Math.toIntExact(output)));
            languages.add(language);
        }
        String recommended=languages.stream().filter(v->Boolean.TRUE.equals(v.get("enabled")))
                .map(v->String.valueOf(v.get("id"))).findFirst().orElse(null);
        String fingerprint=Integer.toHexString(Objects.hash(question.getId(),question.getProblemVersion(),
                languages.stream().map(v->List.of(v.get("id"),v.get("runtimeId"),v.get("enabled"))).toList()));
        Map<String,Object> response=new LinkedHashMap<>();
        response.put("questionId",question.getId());
        response.put("problemVersion",question.getProblemVersion());
        response.put("mode",defaultMode);
        response.put("availableModes",byMode.keySet().stream().filter(mode->languages.stream().anyMatch(v->((List<?>)v.get("modes")).contains(mode))).toList());
        response.put("capabilitiesVersion",fingerprint);
        response.put("expiresAt",java.time.OffsetDateTime.now(java.time.ZoneOffset.ofHours(8)).plusSeconds(30).toString());
        response.put("recommendedLanguage",recommended);
        response.put("languages",languages);
        return response;
    }

    public Map<String,Object> codeTemplate(Question question,String language,Integer requestedVersion) {
        return codeTemplate(question,language,requestedVersion,question.getProblemSpec()==null ? "ACM" : "FUNCTION");
    }
    public Map<String,Object> codeTemplate(Question question,String language,Integer requestedVersion,String requestedMode) {
        if(requestedVersion==null || !requestedVersion.equals(question.getProblemVersion()))
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"题目版本已更新","PROBLEM_VERSION_CHANGED");
        String mode=normalizeMode(requestedMode,question.getProblemSpec());
        if("FUNCTION".equals(mode) && question.getProblemSpec()==null)
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"该题目未配置核心代码签名");
        ProblemSpec modeSpec="FUNCTION".equals(mode) ? question.getProblemSpec() : null;
        Map<String,Object> capability=require(language,question.getProblemSpec(),mode);
        Map<String,Object> response=new LinkedHashMap<>();
        response.put("questionId",question.getId());
        response.put("problemVersion",question.getProblemVersion());
        response.put("language",language);
        response.put("mode",mode);
        response.put("fileName",fileName(language,"FUNCTION".equals(mode)));
        response.put("templateVersion",String.valueOf(capability.getOrDefault("runtimeId",language))+("ACM".equals(mode)?"-acm-basic-v4":"-v2"));
        response.put("code",template(language,modeSpec));
        response.put("instructions","ACM".equals(mode)
                ? "请提交包含标准输入输出入口的完整程序，按题目约定自行处理输入输出。" : "请只实现 Solution 类中的指定方法，无需编写 main；常用标准库和节点类型由系统自动提供，也可自行导入其他标准库。" );
        return response;
    }

    private String normalizeMode(String requestedMode,ProblemSpec spec) {
        String mode=requestedMode==null || requestedMode.isBlank() ? (spec==null ? "ACM" : "FUNCTION") : requestedMode.trim().toUpperCase(Locale.ROOT);
        if(!Set.of("ACM","FUNCTION").contains(mode))
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"执行模式错误");
        return mode;
    }

    public List<Map<String,Object>> previewTemplates(ProblemSpec spec) {
        List<Map<String,Object>> result=new ArrayList<>();
        for(var item:publicCapabilities(spec)) {
            if(!Boolean.TRUE.equals(item.get("available"))) continue;
            String language=String.valueOf(item.get("language"));
            result.add(Map.of("language",language,"fileName",fileName(language,spec!=null),
                    "code",template(language,spec)));
        }
        return result;
    }

    public String template(String language,ProblemSpec spec) {
        if(spec==null) return AcmStarterTemplates.code(language);
        List<String> args=new ArrayList<>();
        for(var parameter:spec.getParameters()) {
            String type=type(language,parameter.getType());
            boolean mutable=parameter.getType().endsWith("[]") || parameter.getType().equals("list<int>");
            args.add(language.equals("python") ? parameter.getName() : type+(language.equals("cpp") && mutable ? "& " : " ")+parameter.getName());
        }
        if(language.equals("python")) return "class Solution:\n    def "+spec.getMethodName()+"(self"+(args.isEmpty()?"":", "+String.join(", ",args))+"):\n        pass\n";
        String signature=type(language,spec.getReturnType())+" "+spec.getMethodName()+"("+String.join(", ",args)+")";
        if(language.equals("cpp")) return "class Solution {\npublic:\n    "+signature+" {\n        \n    }\n};\n";
        return "class Solution {\n    public "+signature+" {\n        \n    }\n}\n";
    }
    private String displayName(String language) { return switch(language) {
        case "python" -> "Python 3.12"; case "cpp" -> "C++17"; default -> "Java 8"; }; }
    private String fileName(String language,boolean function) { return switch(language) {
        case "python" -> function ? "solution.py" : "main.py";
        case "cpp" -> function ? "solution.hpp" : "main.cpp";
        default -> function ? "Solution.java" : "Main.java"; }; }
    private String type(String lang,String value) {
        if(lang.equals("python")) return "";
        if(com.nemo.OJbackendmodel.model.dto.question.CustomStructureContract.isNode(value))return com.nemo.OJbackendmodel.model.dto.question.CustomStructureContract.name(value)+(lang.equals("cpp")?"*":"");
        if(value.endsWith("[]")) return lang.equals("cpp") ? "std::vector<"+type(lang,value.substring(0,value.length()-2))+">" : type(lang,value.substring(0,value.length()-2))+"[]";
        return switch(value) {
            case "string" -> lang.equals("cpp") ? "std::string" : "String";
            case "boolean" -> lang.equals("cpp") ? "bool" : "boolean";
            case "int" -> "int";
            case "long" -> lang.equals("cpp") ? "long long" : "long";
            case "list<int>" -> lang.equals("cpp") ? "std::vector<int>" : "List<Integer>";
            case "list_node<int>" -> lang.equals("cpp") ? "ListNode*" : "ListNode";
            case "tree_node<int>" -> lang.equals("cpp") ? "TreeNode*" : "TreeNode";
            default -> value;
        };
    }
}
