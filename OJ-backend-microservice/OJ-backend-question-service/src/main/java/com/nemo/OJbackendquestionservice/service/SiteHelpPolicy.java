package com.nemo.OJbackendquestionservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.nemo.OJbackendcommon.common.ErrorCode;
import com.nemo.OJbackendcommon.exception.BusinessException;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;

/** Fixed product instructions. No RAG, code access, executable tools or model-written answers. */
@Component
public class SiteHelpPolicy {
    public record Link(String label,String to) {}
    public record Guide(String key,String title,String text,List<Link> links) {}
    private static final Pattern CODE=Pattern.compile("```|(?im)^\\s*(?:class\\s+\\w+|def\\s+\\w+|#include|public\\s+(?:class|static)|return\\s+)|(?:分析|修复|优化|调试|解释|编写|生成|补全|实现).{0,20}(?:代码|算法|函数|程序|解法)|(?:代码|算法|函数|程序).{0,20}(?:分析|修复|优化|调试|解释)|(?:这道题|第\\s*\\d+\\s*题).{0,12}(?:怎么做|怎么解|思路|解法)|时间复杂度|空间复杂度|算法思路|解题思路|code review|debug my|write code");
    private final Map<String,Guide> guides=new LinkedHashMap<>();
    public SiteHelpPolicy(){
        add("problem_browse","查看题目","打开题库，点击题号或标题进入题目工作区。左侧查看题目描述、函数签名和样例，右侧编辑代码。", "/problems","打开题库");
        add("problem_search","搜索与筛选","在题库筛选栏右侧输入题号或标题；标签下拉框可选择标签，排序框可调整顺序。清除筛选后查看完整题库。","/problems","打开题库");
        add("submit_flow","提交题目","登录后打开题目，选择语言和 ACM/核心代码模式，编辑完成后点击“提交”。在“本次提交”查看状态；正式提交会计入提交记录。", "/problems","选择题目");
        add("submission_modes","选择提交模式","ACM 模式需要自行处理标准输入输出；核心代码模式按页面函数签名实现 Solution 方法，由平台 Runner 处理输入输出。先选择模式，再检查模板与签名。","/problems","打开题库");
        add("language_switch","切换语言","在代码编辑区上方的语言下拉框选择语言，可选语言以当前题目显示的列表为准。切换后检查文件名、函数签名和对应语言草稿。","/problems","打开题库");
        add("result_refresh","查看判题结果","提交后在“本次提交”点击“刷新结果”，也可进入提交详情查看状态。无需刷新整个浏览器；排队或判题中请等待终态。","/submissions","提交记录");
        add("submission_history","查看提交记录","登录后打开左侧“提交记录”，点击本人记录进入详情，可查看提交代码、判题结果和资源消耗。网站助手不会读取这些代码。","/submissions","提交记录");
        add("error_details","查看错误信息","在本次提交面板或提交详情中查看编译/运行错误详情。网站助手只说明查看位置；分析错误原因、修改代码请使用 AI 代码分析。","/submissions","查看提交详情");
        add("editor_draft","草稿保存","编辑器会自动保存草稿；重新打开相同题目、语言和模式时会尝试恢复。不同浏览器的本地草稿可能不同，请注意编辑器的保存状态提示。","/problems","打开题库");
        add("editor_tools","编辑器功能","桌面编辑器支持语法高亮与 Ctrl+Space 补全，上方“数据结构”可插入辅助类；插入保留原代码，桌面可撤销。手机使用轻量文本编辑器。","/problems","打开题库");
        add("ai_analysis","AI 代码分析入口","判题结束后，在本次提交或提交详情的“AI 代码分析”入口请求分析。它负责代码错误原因和优化建议；与本网站助手独立，代码不会从这里转交过去。","/submissions","进入提交记录");
        add("study_plan","学习计划","有效 VIP 用户可在“AI 训练”的学习计划分页选择关注标签和训练安排，生成题库练习计划并查看完成进度。","/study-plans","AI 训练");
        add("private_problems","私人题目","有效 VIP 用户可在“AI 训练”的私人题目分页填写学习目标，生成简单、中等、困难递进练习。每题验证通过后单独开放，仅本人可见，不加入公共题库。","/study-plans?tab=private","私人题目");
        add("vip_status","VIP 状态","在个人中心查看当前会员状态和有效期。VIP 由管理员设置；权限以服务端有效期为准，网站助手不能更改会员或账号权限。","/me","个人中心");
        add("login","登录流程","点击网站登录入口，输入现有账号和密码。登录异常、忘记密码或账号被限制时请联系管理员；不要在助手中发送密码或密钥。","/login","登录");
        add("registration","账号开通","是否开放注册以网站当前入口为准；没有注册入口时请联系管理员开通账号。网站助手不能创建账号、发放邀请码或绕过注册限制。","/","返回首页");
        add("feedback","人工反馈","在助手的“人工反馈”分页提交题目问题、系统故障或功能建议，并查看自己的处理状态和管理员回复。题目问题需要关联题号。","/feedback","我的反馈");
        add("limits","提交和问答限制","提交和网站问答均有限流；遇到请求过于频繁的提示，请按页面提示等待后重试。等待判题结果时可刷新结果，不必重复发送相同请求。","/submissions","提交记录");
    }
    private void add(String key,String title,String text,String to,String label){guides.put(key,new Guide(key,title,text,List.of(new Link(label,to))));}
    public List<Guide> catalog(){return List.copyOf(guides.values());}
    public List<Map<String,String>> choices(){return guides.values().stream().map(g->Map.of("key",g.key(),"scope",g.title())).toList();}
    public boolean isCodeQuestion(String message){return CODE.matcher(message.replaceAll("(?i)AI\\s*代码\\s*分析","AI分析入口")).find();}
    public Map<String,Object> redirectCode(){return Map.of("kind","CODE_REDIRECT","text","网站助手只回答网站使用和操作流程，不分析代码、解题思路或优化算法。请在判题结束后进入提交详情，使用独立的 AI 代码分析功能。","links",List.of(new Link("进入提交记录并发起 AI 分析","/submissions")));}
    public Map<String,Object> unavailable(){return Map.of("kind","UNAVAILABLE","text","AI 网站问答暂时不可用。可查看使用指南，或切换到“人工反馈”提交问题。","links",List.of());}
    public Map<String,Object> reply(JsonNode decision){
        if(!decision.isObject())throw new BusinessException(ErrorCode.PARAMS_ERROR);
        decision.fieldNames().forEachRemaining(key->{if(!Set.of("kind","sections").contains(key))throw new BusinessException(ErrorCode.PARAMS_ERROR,"网站问答不接受自由文本或代码输出");});
        String kind=decision.path("kind").asText();JsonNode selected=decision.path("sections");
        if(!selected.isArray()||selected.size()>3)throw new BusinessException(ErrorCode.PARAMS_ERROR);
        if(kind.equals("CODE_REDIRECT")){if(!selected.isEmpty())throw new BusinessException(ErrorCode.PARAMS_ERROR);return redirectCode();}
        if(kind.equals("UNSUPPORTED")){if(!selected.isEmpty())throw new BusinessException(ErrorCode.PARAMS_ERROR);return Map.of("kind",kind,"text","我只能回答本站的使用、题目查看、提交和反馈流程。此问题超出网站助手范围；网站故障可切换到“人工反馈”。","links",List.of());}
        if(!kind.equals("HELP")||selected.isEmpty())throw new BusinessException(ErrorCode.PARAMS_ERROR);
        Set<String> keys=new LinkedHashSet<>();for(JsonNode item:selected){if(!item.isTextual()||!guides.containsKey(item.asText()))throw new BusinessException(ErrorCode.PARAMS_ERROR);keys.add(item.asText());}
        List<Guide> matched=keys.stream().map(guides::get).toList();List<Link> links=matched.stream().flatMap(g->g.links().stream()).distinct().toList();
        String text=matched.stream().map(g->g.title()+"：\n"+g.text()).collect(java.util.stream.Collectors.joining("\n\n"));
        return Map.of("kind","HELP","text",text,"links",links);
    }
}
