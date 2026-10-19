package com.nemo.OJbackendquestionservice.controller;

import com.nemo.OJbackendcommon.common.*;
import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendquestionservice.model.SubmissionActivityVO;
import com.nemo.OJbackendquestionservice.service.SubmissionActivityService;
import com.nemo.OJserviceclient.UserFeignClient;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/question_submit/my/activity")
public class SubmissionActivityController {
    private final UserFeignClient users;
    private final SubmissionActivityService activity;
    public SubmissionActivityController(UserFeignClient users, SubmissionActivityService activity) {
        this.users=users; this.activity=activity;
    }
    @GetMapping
    public BaseResponse<SubmissionActivityVO> activity(@RequestParam(required=false) String month,
            HttpServletRequest request,HttpServletResponse response) {
        var user=users.getLoginUser(request);
        if(user==null || user.getId()==null) throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        if(!"user".equals(user.getUserRole())) throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        response.setHeader("Cache-Control","private, no-store");
        return ResultUtils.success(activity.month(user.getId(),month));
    }
}
