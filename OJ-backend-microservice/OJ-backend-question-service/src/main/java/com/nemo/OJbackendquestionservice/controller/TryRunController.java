package com.nemo.OJbackendquestionservice.controller;

import com.nemo.OJbackendcommon.common.*;
import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendcommon.ratelimit.RateLimitGuard;
import com.nemo.OJbackendmodel.model.dto.question.*;
import com.nemo.OJbackendmodel.model.entity.Question;
import com.nemo.OJbackendmodel.model.vo.TryRunResult;
import com.nemo.OJbackendquestionservice.service.*;
import com.nemo.OJserviceclient.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@RestController
@RequestMapping("/try_run")
public class TryRunController {
    private final UserFeignClient users;
    private final JudgeFeignClient judge;
    private final ProblemLifecycleService lifecycle;
    private final LanguageCapabilityService languages;
    private final RateLimitGuard rates;
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private static final String SLOT="try-run:global-slot";
    private static final DefaultRedisScript<Long> RELEASE=new DefaultRedisScript<>("if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end",Long.class);
    public TryRunController(UserFeignClient users,JudgeFeignClient judge,ProblemLifecycleService lifecycle,
            LanguageCapabilityService languages,RateLimitGuard rates,StringRedisTemplate redis,JdbcTemplate jdbc) {
        this.users=users;this.judge=judge;this.lifecycle=lifecycle;this.languages=languages;this.rates=rates;this.redis=redis;this.jdbc=jdbc;
    }
    @PostMapping
    public BaseResponse<TryRunResult> run(@RequestBody TryRunRequest request,HttpServletRequest http) {
        var user=users.getLoginUser(http);
        if(user==null) throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        if(!"user".equals(user.getUserRole())) throw new BusinessException(ErrorCode.NO_AUTH_ERROR,"仅普通用户可试运行代码");
        if(request==null || request.getQuestionId()==null || request.getQuestionId()<=0 || request.getProblemVersion()==null
                || !List.of("java","python","cpp").contains(request.getLanguage()==null?"":request.getLanguage())
                || !List.of("ACM","FUNCTION").contains(request.getExecutionMode()==null?"":request.getExecutionMode())
                || request.getCode()==null || request.getCode().isBlank() || request.getCode().getBytes(StandardCharsets.UTF_8).length>524288)
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"试运行参数无效或代码过大");
        Question question=lifecycle.resolveOnline(request.getQuestionId());
        if(!Objects.equals(request.getProblemVersion(),question.getProblemVersion()))
            throw new BusinessException(ErrorCode.OPERATION_ERROR,"题目版本已更新，请刷新后重试");
        String input=selectInput(question,request);
        var runtime=languages.require(request.getLanguage(),question.getProblemSpec(),request.getExecutionMode());
        rates.checkTryRun(http,user.getId());
        Integer pending=jdbc.queryForObject("SELECT COUNT(*) FROM question_submit WHERE isDelete=0 AND status IN (0,1)",Integer.class);
        if(pending!=null && pending>0) throw new BusinessException(ErrorCode.OPERATION_ERROR,"正式判题正在进行，请稍后试运行");
        String token=UUID.randomUUID().toString();
        try {
            if(!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(SLOT,token,Duration.ofSeconds(180))))
                throw new BusinessException(ErrorCode.OPERATION_ERROR,"已有代码正在试运行，请稍后重试");
        } catch(org.springframework.dao.DataAccessException error) {throw new BusinessException(ErrorCode.OPERATION_ERROR,"试运行暂不可用");}
        boolean completed=false;
        try {
            var internal=new TryRunExecutionRequest();internal.setQuestion(question);internal.setLanguage(request.getLanguage());
            internal.setExecutionMode(request.getExecutionMode());internal.setCode(request.getCode());internal.setInput(input);
            internal.setRuntimeId(String.valueOf(runtime.get("runtimeId")));
            var result=judge.tryRun(internal);completed=true;
            if(result==null || result.getVerdict()==null) throw new BusinessException(ErrorCode.OPERATION_ERROR,"试运行未返回完整结果，请稍后重试");
            return ResultUtils.success(result);
        } catch(feign.FeignException error) {throw new BusinessException(ErrorCode.OPERATION_ERROR,"试运行响应尚未确认，请稍后重试");}
        finally {
            // A timed-out HTTP request may still be executing remotely. Keep its slot until expiry.
            if(completed) try {redis.execute(RELEASE,List.of(SLOT),token);} catch(org.springframework.dao.DataAccessException ignored) { /* Expiry releases the slot. */ }
        }
    }
    static String selectInput(Question question,TryRunRequest request) {
        boolean core="FUNCTION".equals(request.getExecutionMode());
        if(core && question.getProblemSpec()==null) throw new BusinessException(ErrorCode.PARAMS_ERROR,"题目不支持核心代码模式");
        String input=request.getInput();
        if(request.getSampleIndex()!=null) {
            if(input!=null) throw new BusinessException(ErrorCode.PARAMS_ERROR,"样例与自定义输入不能同时指定");
            var cases=core && question.getProblemSpec().getJudgeCases()!=null?question.getProblemSpec().getJudgeCases():question.getJudgeCase();
            var samples=cases==null?List.<JudgeCase>of():cases.stream().filter(c->Boolean.FALSE.equals(c.getIsHidden())).toList();
            int index=request.getSampleIndex();
            if(index<0 || index>=samples.size()) throw new BusinessException(ErrorCode.PARAMS_ERROR,"公开样例不存在");
            input=samples.get(index).getInput();
        }
        if(input==null || input.getBytes(StandardCharsets.UTF_8).length>65536) throw new BusinessException(ErrorCode.PARAMS_ERROR,"输入不得超过 64 KiB");
        if(core) {
            try {
                var root=new com.fasterxml.jackson.databind.ObjectMapper().readTree(input);
                if(root==null || !root.isObject() || root.size()!=question.getProblemSpec().getParameters().size()) throw new IllegalArgumentException();
                for(var parameter:question.getProblemSpec().getParameters()) if(!root.has(parameter.getName())) throw new IllegalArgumentException();
            } catch(Exception error) {throw new BusinessException(ErrorCode.PARAMS_ERROR,"核心代码输入须为 JSON 对象，字段名与函数参数名一致");}
        }
        return input;
    }
}
