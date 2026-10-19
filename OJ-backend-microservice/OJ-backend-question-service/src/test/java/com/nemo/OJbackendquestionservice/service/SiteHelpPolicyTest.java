package com.nemo.OJbackendquestionservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nemo.OJbackendcommon.exception.BusinessException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SiteHelpPolicyTest {
    SiteHelpPolicy policy=new SiteHelpPolicy();ObjectMapper json=new ObjectMapper();
    @Test void codeRequestsRedirectButNavigationStillWorks(){
        for(String text:new String[]{"帮我优化代码","分析这段代码","class Solution { return 1; }","```python\nprint(1)\n```","这道题怎么解","解释算法思路"})assertTrue(policy.isCodeQuestion(text),text);
        for(String text:new String[]{"代码编辑器怎么切换语言","在哪里看编译错误详情","AI代码分析入口在哪里","AI 代码分析怎么打开","怎么提交题目"})assertFalse(policy.isCodeQuestion(text),text);
    }
    @Test void responseOnlyComesFromApprovedText() throws Exception {
        var answer=policy.reply(json.readTree("{\"kind\":\"HELP\",\"sections\":[\"submit_flow\",\"result_refresh\",\"submit_flow\"]}"));
        assertTrue(answer.get("text").toString().contains("刷新结果"));assertEquals("HELP",answer.get("kind"));
        assertEquals(18,policy.catalog().size());
    }
    @Test void arbitraryModelCodeTextAndUnknownKeysAreRejected() throws Exception {
        assertThrows(BusinessException.class,()->policy.reply(json.readTree("{\"kind\":\"HELP\",\"sections\":[\"submit_flow\"],\"text\":\"class Solution {}\"}")));
        assertThrows(BusinessException.class,()->policy.reply(json.readTree("{\"kind\":\"HELP\",\"sections\":[\"solve_problem\"]}")));
        assertThrows(BusinessException.class,()->policy.reply(json.readTree("{\"kind\":\"CODE_REDIRECT\",\"sections\":[\"submit_flow\"]}")));
    }
    @Test void onlyFixedRedirectIsReturnedForCode() throws Exception {
        var answer=policy.reply(json.readTree("{\"kind\":\"CODE_REDIRECT\",\"sections\":[]}"));
        assertEquals(policy.redirectCode(),answer);assertTrue(answer.get("text").toString().contains("独立"));
        assertEquals(1,((java.util.List<?>)answer.get("links")).size());
    }
}
