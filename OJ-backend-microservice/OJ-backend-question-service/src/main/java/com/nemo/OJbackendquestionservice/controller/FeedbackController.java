package com.nemo.OJbackendquestionservice.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nemo.OJbackendcommon.annotation.AuthCheck;
import com.nemo.OJbackendcommon.common.BaseResponse;
import com.nemo.OJbackendcommon.common.ErrorCode;
import com.nemo.OJbackendcommon.common.ResultUtils;
import com.nemo.OJbackendcommon.constant.UserConstant;
import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendmodel.model.dto.feedback.FeedbackAddRequest;
import com.nemo.OJbackendmodel.model.dto.feedback.FeedbackHandleRequest;
import com.nemo.OJbackendmodel.model.dto.feedback.FeedbackQueryRequest;
import com.nemo.OJbackendmodel.model.entity.User;
import com.nemo.OJbackendmodel.model.vo.FeedbackVO;
import com.nemo.OJbackendquestionservice.service.FeedbackService;
import com.nemo.OJserviceclient.UserFeignClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/")
public class FeedbackController {
    private final FeedbackService feedbackService;
    private final UserFeignClient userFeignClient;

    public FeedbackController(FeedbackService feedbackService, UserFeignClient userFeignClient) {
        this.feedbackService = feedbackService;
        this.userFeignClient = userFeignClient;
    }

    @PostMapping("/feedback/add")
    public BaseResponse<Long> add(@RequestBody FeedbackAddRequest body, HttpServletRequest request) {
        User user = requireFeedbackUser(request);
        return ResultUtils.success(feedbackService.createFeedback(body, user));
    }

    @PostMapping("/feedback/my/list/page")
    public BaseResponse<Page<FeedbackVO>> myList(@RequestBody FeedbackQueryRequest body,
                                                  HttpServletRequest request) {
        User user = requireFeedbackUser(request);
        return ResultUtils.success(feedbackService.listFeedback(body, user.getId()));
    }

    @GetMapping("/feedback/get")
    public BaseResponse<FeedbackVO> myDetail(@RequestParam("id") Long id, HttpServletRequest request) {
        if (id == null || id <= 0) throw new BusinessException(ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(feedbackService.getFeedback(id, requireFeedbackUser(request), false));
    }

    @PostMapping("/admin/feedback/list/page")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<FeedbackVO>> adminList(@RequestBody FeedbackQueryRequest body) {
        return ResultUtils.success(feedbackService.listFeedback(body, null));
    }

    @GetMapping("/admin/feedback/detail")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<FeedbackVO> adminDetail(@RequestParam("id") Long id, HttpServletRequest request) {
        if (id == null || id <= 0) throw new BusinessException(ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(feedbackService.getFeedback(id, userFeignClient.getLoginUser(request), true));
    }

    @PostMapping("/admin/feedback/handle")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<FeedbackVO> handle(@RequestBody FeedbackHandleRequest body, HttpServletRequest request) {
        return ResultUtils.success(feedbackService.handleFeedback(body, userFeignClient.getLoginUser(request)));
    }

    private User requireFeedbackUser(HttpServletRequest request) {
        User user = userFeignClient.getLoginUser(request);
        if (UserConstant.ADMIN_ROLE.equals(user.getUserRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN_ERROR, "管理员账号只能处理反馈");
        }
        return user;
    }
}
