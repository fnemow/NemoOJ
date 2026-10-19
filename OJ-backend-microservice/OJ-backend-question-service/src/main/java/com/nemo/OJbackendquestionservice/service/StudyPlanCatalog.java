package com.nemo.OJbackendquestionservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nemo.OJbackendcommon.common.ErrorCode;
import com.nemo.OJbackendcommon.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Hard catalog constraints, shared by task creation and final publication. */
@Service
public class StudyPlanCatalog {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public StudyPlanCatalog(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    public List<Map<String,Object>> online() {
        return jdbc.query("SELECT p.problemNo,q.title,q.tags,q.problemVersion FROM question_public_number p JOIN question q ON q.id=p.currentQuestionId WHERE p.status='ONLINE' AND q.isPublished=1 AND q.isDelete=0 ORDER BY p.problemNo",
            (rs,row)-> { Map<String,Object> v=new LinkedHashMap<>(); v.put("problemNo",rs.getLong(1)); v.put("title",rs.getString(2));
                List<String> tags=tags(rs.getString(3)); v.put("tags",tags); v.put("version",rs.getInt(4));
                v.put("difficulty",tags.contains("困难")?"HARD":tags.contains("中等")?"MEDIUM":"EASY");return v; });
    }
    public Set<Long> accepted(long owner) {
        return new HashSet<>(jdbc.query("SELECT DISTINCT q.problemNo FROM question_submit s JOIN question q ON q.id=s.questionId WHERE s.userId=? AND s.problemSource='PUBLIC' AND s.status=2 AND s.isDelete=0 AND JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(s.judgeInfo),s.judgeInfo,'{}'),'$.message'))='accepted' AND q.problemNo IS NOT NULL",
            (rs,row)->rs.getLong(1),owner));
    }
    public List<Map<String,Object>> topics(long owner,boolean review) {
        Set<Long> done=accepted(owner); Map<String,int[]> counts=new TreeMap<>();
        for(var p:online()) for(String tag:algorithmTags(p.get("tags"))) { int[] c=counts.computeIfAbsent(tag,k->new int[2]);c[0]++;if(!done.contains(((Number)p.get("problemNo")).longValue()))c[1]++; }
        return counts.entrySet().stream().map(e->Map.<String,Object>of("tag",e.getKey(),"total",e.getValue()[0],"accepted",e.getValue()[0]-e.getValue()[1],"available",review?e.getValue()[0]:e.getValue()[1])).toList();
    }
    public List<Map<String,Object>> candidates(long owner,List<String> selected,boolean review,int days) {
        Set<String> valid=new HashSet<>(); for(var p:online())valid.addAll(algorithmTags(p.get("tags")));
        if(selected.isEmpty()||selected.size()>6||!valid.containsAll(selected))throw new BusinessException(ErrorCode.PARAMS_ERROR,"请选择 1–6 个当前题库中的算法标签");
        Set<Long> done=review?Set.of():accepted(owner);
        List<Map<String,Object>> result=online().stream().filter(p->!done.contains(((Number)p.get("problemNo")).longValue()))
            .filter(p->algorithmTags(p.get("tags")).stream().anyMatch(selected::contains)).toList();
        if(days>0)for(String tag:selected)if(result.stream().noneMatch(p->algorithmTags(p.get("tags")).contains(tag)))throw new BusinessException(ErrorCode.PARAMS_ERROR,"主题「"+tag+"」没有可用题目，请允许复习或调整标签");
        if(result.size()<days)throw new BusinessException(ErrorCode.PARAMS_ERROR,"可用题目仅 "+result.size()+" 道，至少需要 "+days+" 道；请缩短周期、增加主题或允许复习");
        return result;
    }
    public static List<String> algorithmTags(Object raw) {
        if(!(raw instanceof List<?> values))return List.of();
        return values.stream().map(String::valueOf).filter(s->!Set.of("简单","中等","困难","Easy","Medium","Hard").contains(s)).distinct().toList();
    }
    private List<String> tags(String raw) { try{return json.readValue(raw,new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){});}catch(Exception e){return List.of();} }
}
